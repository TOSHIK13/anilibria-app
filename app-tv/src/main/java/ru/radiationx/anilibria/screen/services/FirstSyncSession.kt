package ru.radiationx.anilibria.screen.services

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.radiationx.anilibria.BuildConfig
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.external.DesiredStatus
import ru.radiationx.data.external.ExternalPullSync
import ru.radiationx.data.external.FirstSyncItem
import ru.radiationx.data.external.FirstSyncPlan
import ru.radiationx.data.external.FirstSyncPlanner
import ru.radiationx.data.external.FirstSyncPolicy
import ru.radiationx.data.external.FirstSyncReport
import ru.radiationx.data.external.FirstSyncRunner
import ru.radiationx.data.external.SyncSnapshot
import ru.radiationx.data.repository.ReleaseRepository
import timber.log.Timber
import java.io.File
import javax.inject.Inject

sealed class ReportState {
    object Loading : ReportState()
    data class Ready(val report: FirstSyncReport) : ReportState()
    data class Error(val message: String) : ReportState()
}

/**
 * Состояние мастера первой синхронизации, общее для трёх экранов (первая, расхождения, процесс):
 * отчёт сравнения, выбранная политика, ручной выбор статуса в конфликтах и названия релизов.
 */
class FirstSyncSession @Inject constructor(
    private val context: Context,
    private val pull: ExternalPullSync,
    private val runner: FirstSyncRunner,
    private val releases: ReleaseRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var titlesJob: Job? = null

    val report = MutableStateFlow<ReportState>(ReportState.Loading)
    val policy = MutableStateFlow(FirstSyncPolicy.MERGE)
    val choices = MutableStateFlow<Map<Int, DesiredStatus>>(emptyMap())

    /** Русские названия релизов AniLiberty по id релиза. */
    val titles = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** debug: отчёт взят из `filesDir/external/fake_first_sync.json`, выполнение — dry-run. */
    @Volatile
    var fake = false
        private set

    val progress get() = runner.progress

    val plan: Flow<FirstSyncPlan?> = combine(report, policy, choices) { r, p, c ->
        (r as? ReportState.Ready)?.let { FirstSyncPlanner.plan(it.report, p, c) }
    }

    /** Новый заход в мастер: сбрасывает выбор и запускает сравнение (если не идёт выполнение). */
    fun begin() {
        if (runner.progress.value.running) return
        policy.value = FirstSyncPolicy.MERGE
        choices.value = emptyMap()
        titles.value = emptyMap()
        load()
    }

    fun load() {
        loadJob?.cancel()
        report.value = ReportState.Loading
        loadJob = scope.launch {
            try {
                val fakeReport = if (BuildConfig.DEBUG) readFake() else null
                fake = fakeReport != null
                report.value = ReportState.Ready(fakeReport ?: pull.compare())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "first sync: compare failed")
                report.value = ReportState.Error(e.message ?: "Не удалось получить список AniList")
            }
        }
    }

    fun choose(malId: Int, status: DesiredStatus) {
        choices.value = choices.value + (malId to status)
    }

    /** Подгружает русские названия для строк расхождений. */
    fun loadTitles() {
        val ready = report.value as? ReportState.Ready ?: return
        if (fake || titlesJob?.isActive == true) return
        val ids = with(ready.report) { differing + onlyRemote + onlyLocal }
            .mapNotNull { it.releaseId }
            .filter { it !in titles.value }
            .distinct()
        if (ids.isEmpty()) return
        titlesJob = scope.launch {
            ids.chunked(40).forEach { chunk ->
                try {
                    val loaded = releases.getShortReleasesById(chunk.map { ReleaseId(it) })
                    titles.value = titles.value + loaded.mapNotNull { r ->
                        (r.title ?: r.names.firstOrNull())?.let { r.id.id to it }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "first sync: titles not loaded")
                }
            }
        }
    }

    fun start() {
        val ready = report.value as? ReportState.Ready ?: return
        runner.start(policy.value, choices.value, if (fake) ready.report else null, dryRun = fake)
    }

    fun finish() = runner.reset()

    fun setNotifyOnFinish() = runner.setNotifyOnFinish(true)

    private fun readFake(): FirstSyncReport? {
        val file = File(context.filesDir, "external/fake_first_sync.json")
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val groups = HashMap<String, MutableList<FirstSyncItem>>()
        val items = json.getJSONArray("items")
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val item = FirstSyncItem(
                malId = o.getInt("mal"),
                releaseId = if (o.has("rel")) o.getInt("rel") else null,
                title = o.getString("title"),
                local = SyncSnapshot.parse(o.optString("l").takeIf { it.isNotEmpty() }),
                remote = SyncSnapshot.parse(o.optString("r").takeIf { it.isNotEmpty() }),
                episodes = if (o.has("eps")) o.getInt("eps") else null,
                isMovie = o.optBoolean("movie"),
            )
            groups.getOrPut(o.getString("g")) { ArrayList() } += item
        }
        return FirstSyncReport(
            matching = groups["matching"].orEmpty(),
            differing = groups["differing"].orEmpty(),
            onlyRemote = groups["remote"].orEmpty(),
            onlyLocal = groups["local"].orEmpty(),
            notInCatalog = groups["missing"].orEmpty(),
            noMalId = json.optInt("noMalId"),
            remoteTotal = json.optInt("remoteTotal"),
            createdAt = System.currentTimeMillis(),
        )
    }
}

/** Столбцы и подписи таблицы расхождений. */
object FirstSyncTexts {

    fun snapshotText(s: SyncSnapshot?, item: FirstSyncItem): String {
        val status = s?.status ?: return "—"
        val showProgress = s.progress > 0 || (item.isMovie && status == DesiredStatus.COMPLETED)
        val base = status.ru
        return if (showProgress) "$base · " + progressText(s.progress, item.episodes, item.isMovie) else base
    }

    /** Что изменится у стороны: статус и/или прогресс. */
    fun changeText(target: SyncSnapshot, side: SyncSnapshot?, item: FirstSyncItem): String {
        val parts = ArrayList<String>()
        val current = side?.takeIf { it.status != null }
        if (current == null || current.status != target.status) parts += target.status!!.ru
        if (target.status != DesiredStatus.COMPLETED && target.progress > 0 && target.progress != current?.progress) {
            parts += progressText(target.progress, item.episodes, item.isMovie)
        }
        return parts.joinToString(" · ").ifEmpty { "без изменений" }
    }
}
