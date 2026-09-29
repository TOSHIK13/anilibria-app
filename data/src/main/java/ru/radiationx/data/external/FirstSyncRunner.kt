package ru.radiationx.data.external

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONException
import ru.radiationx.data.entity.domain.types.ReleaseId
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

enum class FirstSyncPhase { IDLE, COMPARING, APPLYING, SENDING, DONE, FAILED }

/** Ход первой синхронизации для экрана «Процесс». */
data class FirstSyncProgress(
    val phase: FirstSyncPhase = FirstSyncPhase.IDLE,
    /** Выполнено изменений (применено к AniLiberty + отправлено в AniList) из [total]. */
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /** Отправлено в AniList / поставлено в очередь всего. */
    val sent: Int = 0,
    val queued: Int = 0,
    /** Применено к AniLiberty / всего. */
    val received: Int = 0,
    val receivedTotal: Int = 0,
    val errors: Int = 0,
    val skipped: Int = 0,
    val remoteTotal: Int = 0,
    val linked: Int = 0,
    val dryRun: Boolean = false,
    val message: String? = null,
) {
    val running: Boolean get() = phase == FirstSyncPhase.COMPARING || phase == FirstSyncPhase.APPLYING || phase == FirstSyncPhase.SENDING
    val changes: Int get() = received + sent
}

/**
 * Выполняет план первой синхронизации: изменения AniLiberty — под [RemoteOrigin], отправка в AniList —
 * через [ExternalOutbox] (коалесинг, повторы, лимит запросов уже есть в движке). Работает в собственном
 * scope процесса и продолжается, если экран закрыт. Повторный запуск после обрыва безопасен: перед
 * выполнением сравнение делается заново, уже сделанное совпадает и пропускается.
 */
class FirstSyncRunner @Inject constructor(
    private val pull: ExternalPullSync,
    private val outbox: ExternalOutbox,
    private val syncState: ExternalSyncState,
    private val journal: ExternalSyncJournal,
    private val settings: ExternalServiceSettings,
) {

    private val serviceId = AniListService.ID
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(FirstSyncProgress())
    private val finished = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    private var job: Job? = null

    @Volatile
    private var notifyOnFinish = false

    val progress: StateFlow<FirstSyncProgress> = state.asStateFlow()

    /** Завершение прогона, начатого со включённым [setNotifyOnFinish]: число изменений. */
    val finishedEvents: SharedFlow<Int> = finished.asSharedFlow()

    /** «Продолжить в фоне»: по завершении показать уведомление. */
    fun setNotifyOnFinish(value: Boolean) {
        notifyOnFinish = value
    }

    /** Экран закрыт после завершения/ошибки: возвращает состояние в исходное. */
    @Synchronized
    fun reset() {
        if (job?.isActive != true) state.value = FirstSyncProgress()
    }

    /**
     * Запускает выполнение. [report] — готовый отчёт (по умолчанию сравнение делается заново);
     * [dryRun] — только посчитать и показать ход, ничего не менять и не отправлять (debug).
     */
    @Synchronized
    fun start(
        policy: FirstSyncPolicy,
        choices: Map<Int, DesiredStatus>,
        report: FirstSyncReport? = null,
        dryRun: Boolean = false,
    ) {
        if (job?.isActive == true) return
        notifyOnFinish = false
        state.value = FirstSyncProgress(FirstSyncPhase.COMPARING, dryRun = dryRun)
        job = scope.launch {
            try {
                run(policy, choices, report, dryRun)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "first sync failed")
                state.value = state.value.copy(phase = FirstSyncPhase.FAILED, message = failText(e))
            }
        }
    }

    private fun failText(e: Exception) = when (e) {
        is ExternalHttpException -> "AniList отклонил запрос (HTTP ${e.code})"
        is IOException, is JSONException -> e.message ?: "Нет связи с AniList"
        else -> e.message ?: "Неизвестная ошибка"
    }

    private suspend fun run(policy: FirstSyncPolicy, choices: Map<Int, DesiredStatus>, given: FirstSyncReport?, dryRun: Boolean) {
        val report = given ?: pull.compare()
        val plan = FirstSyncPlanner.plan(report, policy, choices)
        val locals = plan.actions.filter { it.toLocal != null }
        val pushes = plan.actions.filter { it.toRemote != null }
        var cur = FirstSyncProgress(
            phase = FirstSyncPhase.APPLYING,
            total = locals.size + pushes.size, receivedTotal = locals.size, queued = pushes.size,
            skipped = plan.skipped + plan.conflicts, remoteTotal = report.remoteTotal, linked = report.linked, dryRun = dryRun,
        )
        state.value = cur

        val records = ArrayList<PulledTitle>()
        // база: всё, что совпало, осталось как есть или пропущено осознанно
        plan.actions.filter { it.flow == FirstSyncFlow.NONE || it.flow == FirstSyncFlow.CHOOSE }
            .forEach { records += PulledTitle(it.item.malId, it.item.releaseId, it.finalLocal, it.finalRemote) }

        // 1. изменения в AniLiberty
        for (a in locals) {
            cur = cur.copy(current = a.item.title)
            state.value = cur
            if (dryRun) {
                delay(DRY_STEP_MS)
                cur = cur.copy(received = cur.received + 1, done = cur.done + 1)
                state.value = cur
                continue
            }
            val rid = a.item.releaseId?.let { ReleaseId(it) }
            if (rid == null) {
                cur = cur.copy(errors = cur.errors + 1)
                state.value = cur
                continue
            }
            try {
                val title = pull.applyLocalChange(rid, a.localSetStatus, a.localRaiseTo) ?: a.item.title
                journal.record(
                    serviceId, JournalDirection.IN, a.item.malId, rid.id, title,
                    describeLocal(a), JournalResult.DONE,
                    detail = "первая синхронизация" + if (a.progressKept) " · прогресс в AniLiberty больше, чем в AniList — не уменьшен" else "",
                    meta = firstMeta(a),
                )
                records += PulledTitle(a.item.malId, rid.id, a.finalLocal, a.finalRemote)
                cur = cur.copy(received = cur.received + 1, done = cur.done + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "first sync: apply failed for mal %d", a.item.malId)
                journal.record(
                    serviceId, JournalDirection.IN, a.item.malId, rid.id, a.item.title,
                    "не удалось применить изменение из AniList", JournalResult.ERROR, detail = e.message,
                    meta = JournalMeta(origin = JournalOrigin.FIRST_SYNC, errorKind = if (e is java.io.IOException) JournalErrorKind.NETWORK else JournalErrorKind.OTHER),
                )
                cur = cur.copy(errors = cur.errors + 1)
            }
            state.value = cur
        }

        // 2. отправка в AniList: ставим в очередь, дальше работает движок
        val pushed = HashSet<Int>()
        for (a in pushes) {
            val desired = a.pushState() ?: continue
            if (!dryRun) {
                outbox.enqueue(serviceId, a.item.malId, a.item.releaseId, a.item.title, desired, OutboxSource.MANUAL)
                syncState.markPending(serviceId, a.item.malId, a.item.releaseId)
                records += PulledTitle(a.item.malId, a.item.releaseId, a.finalLocal, a.finalRemote)
            }
            pushed += a.item.malId
        }

        // 3. сохранение состояния: с этого момента прогон безопасно пережить обрыв
        cur = cur.copy(phase = FirstSyncPhase.SENDING, current = null)
        state.value = cur
        if (!dryRun) {
            plan.actions.filter { it.flow == FirstSyncFlow.SKIP }.forEach {
                journal.record(
                    serviceId, JournalDirection.IN, it.item.malId, null, it.item.title,
                    "нет на AniLiberty (MAL id ${it.item.malId}) — пропущено", JournalResult.SKIPPED, detail = "первая синхронизация",
                    meta = JournalMeta(origin = JournalOrigin.FIRST_SYNC, reason = JournalReason.NOT_ON_ALILIBRIA, anilistBefore = stateOf(it.item.remote, it.item)),
                )
            }
            plan.actions.filter { it.flow == FirstSyncFlow.CHOOSE }.forEach {
                journal.record(
                    serviceId, JournalDirection.CHECK, it.item.malId, it.item.releaseId, it.item.title,
                    "статусы расходятся, выбор не сделан — оставлено как есть", JournalResult.SKIPPED, detail = "первая синхронизация",
                    meta = JournalMeta(
                        origin = JournalOrigin.FIRST_SYNC, reason = JournalReason.CHOICE_NOT_MADE,
                        anilistAfter = stateOf(it.item.remote, it.item), localAfter = stateOf(it.item.local, it.item),
                    ),
                )
            }
            syncState.recordPulled(serviceId, records, report.linked, report.remoteTotal)
            settings.setFirstSyncDone(serviceId, true)
        }

        // 4. ждём, пока движок отправит всё, что поставили (или не упрётся в ошибку)
        if (dryRun) {
            for (i in 1..pushed.size) {
                delay(DRY_STEP_MS)
                cur = cur.copy(sent = i, done = cur.received + i, current = pushes.getOrNull(i - 1)?.item?.title)
                state.value = cur
            }
        } else if (pushed.isNotEmpty()) {
            val baseErrors = cur.errors
            outbox.items.first { items ->
                val mine = items.filter { it.serviceId == serviceId && it.malId in pushed }
                val errors = mine.count { it.state == OutboxState.ERROR }
                val sent = pushed.size - mine.size
                val next = mine.filter { it.state == OutboxState.PENDING }.minByOrNull { it.createdAt }
                cur = cur.copy(sent = sent, done = cur.received + sent + errors, current = next?.title, errors = baseErrors + errors)
                state.value = cur
                mine.none { it.state == OutboxState.PENDING }
            }
        }

        cur = cur.copy(phase = FirstSyncPhase.DONE, current = null, done = cur.total)
        state.value = cur
        if (!dryRun) {
            journal.record(
                serviceId, JournalDirection.CHECK, null, null, "AniList",
                "Первая синхронизация · изменений: ${cur.changes}", if (cur.errors > 0) JournalResult.ERROR else JournalResult.DONE,
                detail = "в AniLiberty: ${cur.received} · в AniList: ${cur.sent} · пропущено: ${cur.skipped} · ошибок: ${cur.errors}",
                meta = JournalMeta(origin = JournalOrigin.FIRST_SYNC, changes = cur.changes, queued = cur.sent),
            )
        }
        if (notifyOnFinish) finished.tryEmit(cur.changes)
    }

    private fun stateOf(s: SyncSnapshot?, item: FirstSyncItem): JournalState? =
        s?.let { JournalState(it.status?.name, it.progress, item.episodes, item.isMovie) }

    private fun firstMeta(a: FirstSyncAction) = JournalMeta(
        origin = JournalOrigin.FIRST_SYNC,
        anilistAfter = stateOf(a.item.remote, a.item),
        localBefore = stateOf(a.item.local, a.item),
        localAfter = stateOf(a.finalLocal, a.item),
    )

    private fun describeLocal(a: FirstSyncAction): String {
        val status = a.localSetStatus
        val raise = a.localRaiseTo
        return when {
            status != null -> "«${status.ru}» → коллекция AniLiberty" + (raise?.let { " · отмечены серии 1–$it" } ?: "")
            raise != null -> "прогресс $raise → отмечены серии 1–$raise"
            else -> ""
        }
    }

    private companion object {
        const val DRY_STEP_MS = 500L
    }
}
