package ru.radiationx.data.external

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** Сводка очереди сервиса для экрана сервиса и строки в настройках. */
data class SyncOverview(
    /** Изменений в очереди (включая ошибочные). */
    val queued: Int,
    /** Из них ждут ручного «Повторить сейчас»/«Пропустить». */
    val errors: Int,
    /** epoch ms последней удачной синхронизации, 0 — не было. */
    val lastSyncAt: Long,
    /** Ошибок в журнале за неделю. */
    val weekErrors: Int,
)

/** Событие «изменение отправлено» (для уведомления после серии). [totalEpisodes]/[isMovie] — из AniList. */
data class SyncSentEvent(
    val serviceId: String,
    val malId: Int,
    val releaseId: Int?,
    val title: String,
    val source: OutboxSource,
    val progress: Int,
    val totalEpisodes: Int?,
    val isMovie: Boolean,
)

/**
 * Обработчик очереди отправки в AniList: один корутинный цикл в собственном scope. Просыпается по
 * новому элементу, старту приложения, появлению сети, «Синхронизировать сейчас», новому входу и
 * таймеру `nextAttemptAt`. Пока токена нет или вход истёк — очередь на паузе, изменения копятся.
 */
class ExternalSyncEngine @Inject constructor(
    private val context: Context,
    private val outbox: ExternalOutbox,
    private val journal: ExternalSyncJournal,
    private val syncState: ExternalSyncState,
    private val store: ExternalTokenStore,
    private val lookup: AniListMediaLookup,
) {

    private val serviceId = AniListService.ID
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private val sent = MutableSharedFlow<SyncSentEvent>(extraBufferCapacity = 8)

    /** Успешно отправленные (DONE) изменения; без повтора для опоздавших подписчиков. */
    val sentEvents: SharedFlow<SyncSentEvent> = sent.asSharedFlow()

    fun observeOverview(): Flow<SyncOverview> = combine(
        outbox.observe(serviceId),
        syncState.observeLastSyncAt(serviceId),
        journal.observeWeekErrors(serviceId),
    ) { items, last, weekErrors ->
        SyncOverview(items.size, items.count { it.state == OutboxState.ERROR }, last, weekErrors)
    }

    /** «Синхронизировать сейчас»: запускает очередь немедленно, в т.ч. ошибочные элементы. */
    fun syncNow() {
        outbox.retryAll(serviceId)
        kicks.trySend(Unit)
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        journal.onRetryRequested = { kicks.trySend(Unit) }
        outbox.items.onEach { kicks.trySend(Unit) }.launchIn(scope)
        // новый вход (или обновление токена): копившиеся изменения уходят сразу
        store.observe(serviceId).drop(1).onEach {
            outbox.expedite(serviceId)
            kicks.trySend(Unit)
        }.launchIn(scope)
        registerNetworkCallback()
        outbox.expedite(serviceId)
        scope.launch {
            while (true) {
                val wait = runCatching { cycle() }
                    .onFailure { Timber.w(it, "external sync: cycle failed") }
                    .getOrNull() ?: DEFAULT_WAIT_MS
                withTimeoutOrNull(wait) { kicks.receive() }
            }
        }
        kicks.trySend(Unit)
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    outbox.expedite(serviceId)
                    kicks.trySend(Unit)
                }
            })
        }.onFailure { Timber.w(it, "external sync: network callback not registered") }
    }

    /** Отправляет всё, что созрело; возвращает, через сколько мс проснуться (null — ждать событий). */
    private suspend fun cycle(): Long? {
        while (true) {
            if (store.activeToken(serviceId) == null) return null
            val now = System.currentTimeMillis()
            val pending = outbox.items.value.filter { it.serviceId == serviceId && it.state == OutboxState.PENDING }
            val due = pending.filter { it.nextAttemptAt <= now }.minByOrNull { it.createdAt }
            if (due == null) {
                return pending.minOfOrNull { it.nextAttemptAt }?.let { (it - now).coerceAtLeast(1_000) }
            }
            process(due)
        }
    }

    private suspend fun process(item: OutboxItem) {
        try {
            when (val found = lookup.lookup(item.malId)) {
                MediaLookupResult.NotFound -> {
                    outbox.complete(item.id, item.version)
                    finishPending(item)
                    journal.record(
                        serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title,
                        "тайтла нет на AniList — пропущено", JournalResult.SKIPPED, itemId = item.id,
                    )
                }

                is MediaLookupResult.Found -> apply(item, found)
            }
        } catch (e: ExternalRateLimitException) {
            outbox.update(item.id) { it.copy(nextAttemptAt = e.untilMs, lastError = "HTTP 429") }
            journal.record(
                serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title,
                describe(item, null), JournalResult.RETRY_AT,
                detail = "Слишком много запросов (HTTP 429)", retryAt = e.untilMs, itemId = item.id,
            )
        } catch (e: ExternalHttpException) {
            if (store.activeToken(serviceId) == null) {
                // 401: вход истёк, очередь на паузе до нового входа
                journal.record(
                    serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title,
                    describe(item, null), JournalResult.ERROR,
                    detail = "Вход в AniList истёк (HTTP ${e.code}) · изменения ждут нового входа", itemId = item.id,
                )
            } else {
                fail(item, if (e.code >= 500) "Сервер AniList недоступен (HTTP ${e.code})" else "AniList отклонил запрос (HTTP ${e.code})")
            }
        } catch (e: IOException) {
            fail(item, "Нет связи с AniList")
        } catch (e: JSONException) {
            fail(item, "Неожиданный ответ AniList")
        }
    }

    private suspend fun apply(item: OutboxItem, found: MediaLookupResult.Found) {
        val plan = AniListWriteRules.plan(item.desired, found.media, found.entry)
        val text = describe(item, plan, found.media)
        val detail = SyncRules.mergeDetail(item)
        var status = found.entry?.status
        var progress = found.entry?.progress ?: 0
        val result = when (plan) {
            is SyncPlan.UpToDate -> JournalResult.UP_TO_DATE
            is SyncPlan.Delete -> {
                lookup.delete(plan.entryId)
                status = null
                progress = 0
                JournalResult.DONE
            }

            is SyncPlan.Save -> {
                val saved = lookup.save(found.media.id, plan.status, plan.progress)
                status = saved.status
                progress = saved.progress
                JournalResult.DONE
            }
        }
        val removed = outbox.complete(item.id, item.version)
        syncState.markSynced(serviceId, item.malId, item.releaseId, status, progress, stillPending = !removed)
        journal.record(
            serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title, text, result,
            detail = detail ?: (plan as? SyncPlan.UpToDate)?.reason?.takeIf { it != "уже актуально" }, itemId = item.id,
        )
        if (plan is SyncPlan.Save) {
            sent.tryEmit(
                SyncSentEvent(
                    serviceId, item.malId, item.releaseId, item.title, item.source,
                    progress, found.media.episodes, found.media.isMovie,
                )
            )
        }
    }

    private fun describe(item: OutboxItem, plan: SyncPlan?, media: MediaInfo? = lookup.cached(item.malId)): String =
        AniListWriteRules.describe(item, plan, media)

    private fun finishPending(item: OutboxItem) {
        if (outbox.items.value.none { it.serviceId == item.serviceId && it.malId == item.malId }) {
            syncState.clearPending(item.serviceId, item.malId)
        }
    }

    private fun fail(item: OutboxItem, message: String) {
        val attempts = item.attempts + 1
        val delayMs = SyncRules.backoffMs(attempts)
        val now = System.currentTimeMillis()
        if (delayMs == null) {
            outbox.update(item.id) { it.copy(attempts = attempts, lastError = message, state = OutboxState.ERROR) }
            syncState.markError(serviceId, item.malId, item.releaseId, message)
            journal.record(
                serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title, describe(item, null),
                JournalResult.ERROR, detail = "$message · попытка $attempts из ${SyncRules.MAX_ATTEMPTS} · нужен повтор вручную",
                itemId = item.id,
            )
        } else {
            val at = now + delayMs
            outbox.update(item.id) { it.copy(attempts = attempts, lastError = message, nextAttemptAt = at) }
            syncState.markError(serviceId, item.malId, item.releaseId, message)
            journal.record(
                serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title, describe(item, null),
                JournalResult.RETRY_AT, detail = "$message · попытка $attempts из ${SyncRules.MAX_ATTEMPTS}",
                retryAt = at, itemId = item.id,
            )
        }
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 60_000L
    }
}
