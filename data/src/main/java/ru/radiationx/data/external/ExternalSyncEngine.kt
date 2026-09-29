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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** Записей сервиса с найденным релизом AniLiberty; -1 — список ещё не читали. */
    val linkedTitles: Int = -1,
    /** Всего записей в списке сервиса. */
    val totalTitles: Int = 0,
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
    private val pull: ExternalPullSync,
    private val settings: ExternalServiceSettings,
) {

    private val serviceId = AniListService.ID
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private val sent = MutableSharedFlow<SyncSentEvent>(extraBufferCapacity = 8)
    private val pullKicks = Channel<Unit>(Channel.CONFLATED)

    /** Успешно отправленные (DONE) изменения; без повтора для опоздавших подписчиков. */
    val sentEvents: SharedFlow<SyncSentEvent> = sent.asSharedFlow()

    fun observeOverview(): Flow<SyncOverview> = combine(
        outbox.observe(serviceId),
        syncState.observeLastSyncAt(serviceId),
        journal.observeWeekErrors(serviceId),
        syncState.observePullStats(serviceId),
    ) { items, last, weekErrors, stats ->
        SyncOverview(
            items.size, items.count { it.state == OutboxState.ERROR }, last, weekErrors,
            linkedTitles = stats?.linked ?: -1, totalTitles = stats?.total ?: 0,
        )
    }

    /** «Синхронизировать сейчас»: сначала уходит очередь (в т.ч. ошибочные элементы), затем читается список AniList. */
    fun syncNow() {
        outbox.retryAll(serviceId)
        kicks.trySend(Unit)
        scope.launch {
            // ждём, пока очередь не опустеет (или не упрётся в паузу повтора), и читаем список
            withTimeoutOrNull(SEND_WAIT_MS) {
                while (outbox.items.value.any { it.serviceId == serviceId && it.state == OutboxState.PENDING && it.nextAttemptAt <= System.currentTimeMillis() }) {
                    kotlinx.coroutines.delay(500)
                }
            }
            runPull(force = true, manual = true)
        }
    }

    private suspend fun runPull(force: Boolean, manual: Boolean): PullOutcome =
        runCatching { pull.pull(force, manual) }
            .onFailure { Timber.w(it, "external sync: pull failed") }
            .getOrDefault(PullOutcome.Failed)

    /**
     * Чтение списка AniList: при старте (не чаще раза в 30 мин) и каждые 6 ч, пока жив процесс;
     * повторная попытка через короткую паузу, если мешала очередь отправки или ещё не готов каталог.
     */
    private fun startPullTimer() {
        scope.launch {
            while (true) {
                val outcome = runPull(force = false, manual = false)
                val wait = when {
                    outcome is PullOutcome.Skipped && (outcome.reason == PullSkip.OUTBOX_BUSY || outcome.reason == PullSkip.BUSY) -> PULL_RETRY_MS
                    outcome is PullOutcome.Skipped && outcome.reason == PullSkip.NOT_READY -> PULL_NOT_READY_MS
                    outcome is PullOutcome.Skipped && outcome.reason == PullSkip.TOO_SOON ->
                        (ExternalPullSync.MIN_INTERVAL_MS - (System.currentTimeMillis() - syncState.lastPullAt(serviceId))).coerceAtLeast(PULL_RETRY_MS)
                    outcome == PullOutcome.Failed -> PULL_RETRY_LONG_MS
                    else -> PULL_PERIOD_MS
                }
                withTimeoutOrNull(wait) { pullKicks.receive() }
            }
        }
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
            pullKicks.trySend(Unit)
        }.launchIn(scope)
        // включили «Получать изменения» — читаем список без ожидания таймера
        settings.observe(serviceId).map { it.receiveChanges }.distinctUntilChanged().drop(1).onEach {
            if (it) pullKicks.trySend(Unit)
        }.launchIn(scope)
        startPullTimer()
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
                        meta = outMeta(item).copy(reason = JournalReason.NOT_ON_ANILIST),
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
                meta = outMeta(item).copy(httpCode = 429, errorKind = JournalErrorKind.RATE_LIMIT, attempt = item.attempts + 1, maxAttempts = SyncRules.MAX_ATTEMPTS),
            )
        } catch (e: ExternalHttpException) {
            if (store.activeToken(serviceId) == null) {
                // 401: вход истёк, очередь на паузе до нового входа
                journal.record(
                    serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title,
                    describe(item, null), JournalResult.ERROR,
                    detail = "Вход в AniList истёк (HTTP ${e.code}) · изменения ждут нового входа", itemId = item.id,
                    meta = outMeta(item).copy(httpCode = e.code, errorKind = JournalErrorKind.AUTH),
                )
            } else {
                fail(
                    item, if (e.code >= 500) "Сервер AniList недоступен (HTTP ${e.code})" else "AniList отклонил запрос (HTTP ${e.code})",
                    httpCode = e.code, kind = if (e.code >= 500) JournalErrorKind.SERVER else JournalErrorKind.REJECTED,
                )
            }
        } catch (e: IOException) {
            fail(item, "Нет связи с AniList", kind = JournalErrorKind.NETWORK)
        } catch (e: JSONException) {
            fail(item, "Неожиданный ответ AniList", kind = JournalErrorKind.BAD_RESPONSE)
        }
    }

    private suspend fun apply(item: OutboxItem, found: MediaLookupResult.Found) {
        val plan = AniListWriteRules.plan(item.desired, found.media, found.entry)
        val text = describe(item, plan, found.media)
        val detail = SyncRules.mergeDetail(item)
        val before = found.entry?.let { JournalState(it.status, it.progress, found.media.episodes, found.media.isMovie) }
            ?: JournalState(null, 0, found.media.episodes, found.media.isMovie)
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
            meta = outMeta(item).copy(
                anilistId = found.media.id,
                anilistBefore = before,
                anilistAfter = JournalState(status, progress, found.media.episodes, found.media.isMovie),
            ),
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

    private fun outMeta(item: OutboxItem) = JournalMeta(
        origin = when (item.source) {
            OutboxSource.EPISODE -> JournalOrigin.EPISODE
            OutboxSource.COLLECTION -> JournalOrigin.COLLECTION
            OutboxSource.MANUAL -> JournalOrigin.COMPARE_PUSH
        },
        eventTime = item.createdAt,
        localAfter = item.desired.let { d ->
            JournalState(d.status?.takeIf { it != DesiredStatus.REMOVE }?.name, d.progress, d.totalEpisodes)
        },
        mergedCount = item.mergedCount.takeIf { it > 1 },
        episodes = item.episodes.takeIf { it.isNotEmpty() },
    )

    private fun fail(item: OutboxItem, message: String, httpCode: Int? = null, kind: JournalErrorKind = JournalErrorKind.OTHER) {
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
                meta = outMeta(item).copy(httpCode = httpCode, errorKind = kind, attempt = attempts, maxAttempts = SyncRules.MAX_ATTEMPTS),
            )
        } else {
            val at = now + delayMs
            outbox.update(item.id) { it.copy(attempts = attempts, lastError = message, nextAttemptAt = at) }
            syncState.markError(serviceId, item.malId, item.releaseId, message)
            journal.record(
                serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title, describe(item, null),
                JournalResult.RETRY_AT, detail = "$message · попытка $attempts из ${SyncRules.MAX_ATTEMPTS}",
                retryAt = at, itemId = item.id,
                meta = outMeta(item).copy(httpCode = httpCode, errorKind = kind, attempt = attempts, maxAttempts = SyncRules.MAX_ATTEMPTS),
            )
        }
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 60_000L
        const val SEND_WAIT_MS = 120_000L
        const val PULL_PERIOD_MS = 6L * 3600 * 1000
        const val PULL_RETRY_MS = 2L * 60 * 1000
        const val PULL_NOT_READY_MS = 5L * 60 * 1000
        const val PULL_RETRY_LONG_MS = 30L * 60 * 1000
    }
}
