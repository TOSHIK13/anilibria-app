package ru.radiationx.data.external

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** Итог последнего чтения списка сервиса: сколько записей сервиса и сколько из них связано с релизами AniLiberty. */
data class PullStats(val at: Long, val linked: Int, val total: Int)

/** Результат согласования одного тайтла при чтении списка (для [ExternalSyncState.recordPulled]). */
data class PulledTitle(
    val malId: Int,
    val releaseId: Int?,
    val baseLocal: SyncSnapshot,
    val baseRemote: SyncSnapshot,
)

/**
 * Состояние синхронизации одного тайтла с сервисом. [base] — «последнее согласованное состояние»
 * для трёхстороннего слияния (`локальное|удалённое`, см. [SyncSnapshot.serialize]).
 */
data class TitleSyncState(
    val serviceId: String,
    val malId: Int,
    val releaseId: Int?,
    /** Статус на стороне сервиса при последней синхронизации (PLANNING/CURRENT/…), null — записи нет. */
    val remoteStatus: String?,
    val remoteProgress: Int,
    /** epoch ms последней удачной синхронизации; 0 — ещё не было. */
    val syncedAt: Long,
    /** В очереди есть неотправленное изменение. */
    val pending: Boolean,
    /** Текст последней ошибки отправки; null — ошибок нет. */
    val error: String?,
    val base: String? = null,
) {
    /**
     * База слияния (локальная, удалённая). Нет [base] (записано отправкой этапа 3): удалённая берётся
     * из [remoteStatus]/[remoteProgress], локальная считается равной текущей [local]. Синхронизаций
     * не было — обе пустые.
     */
    fun baseSnapshots(local: SyncSnapshot): Pair<SyncSnapshot, SyncSnapshot> {
        val parts = base?.split('|')
        if (parts != null && parts.size == 2) {
            val l = SyncSnapshot.parse(parts[0])
            val r = SyncSnapshot.parse(parts[1])
            if (l != null && r != null) return l to r
        }
        if (syncedAt > 0) return local to SyncSnapshot.ofRemote(remoteStatus, remoteProgress)
        return SyncSnapshot.ABSENT to SyncSnapshot.ABSENT
    }
}

/** Хранилище [TitleSyncState] по (serviceId, malId) + время последней синхронизации сервиса. Файл `filesDir/external/sync_state.json`. */
class ExternalSyncState @Inject constructor(context: Context) {

    private val file = ExternalJsonFile(File(context.filesDir, "external/sync_state.json"))
    private val lock = Any()
    private val titles = MutableStateFlow<Map<String, TitleSyncState>>(emptyMap())
    private val lastSync = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val pullStats = MutableStateFlow<Map<String, PullStats>>(emptyMap())

    /** Все состояния тайтлов (ключ `serviceId:malId`). */
    val all: StateFlow<Map<String, TitleSyncState>> = titles.asStateFlow()

    init {
        val json = file.read()
        json?.optJSONArray("titles")?.let { arr ->
            val map = LinkedHashMap<String, TitleSyncState>()
            for (i in 0 until arr.length()) {
                parse(arr.optJSONObject(i))?.also { map[key(it.serviceId, it.malId)] = it }
            }
            titles.value = map
        }
        json?.optJSONObject("lastSync")?.let { o ->
            lastSync.value = o.keys().asSequence().associateWith { o.optLong(it) }
        }
        json?.optJSONObject("pull")?.let { o ->
            pullStats.value = o.keys().asSequence().mapNotNull { k ->
                o.optJSONObject(k)?.let { k to PullStats(it.optLong("at"), it.optInt("linked"), it.optInt("total")) }
            }.toMap()
        }
    }

    fun get(serviceId: String, malId: Int): TitleSyncState? = titles.value[key(serviceId, malId)]

    fun observe(serviceId: String, malId: Int): Flow<TitleSyncState?> =
        titles.map { it[key(serviceId, malId)] }.distinctUntilChanged()

    /** Состояние по релизу AniLiberty (для значков на карточке). */
    fun observeRelease(serviceId: String, releaseId: Int): Flow<TitleSyncState?> =
        titles.map { map -> map.values.firstOrNull { it.serviceId == serviceId && it.releaseId == releaseId } }
            .distinctUntilChanged()

    fun observeLastSyncAt(serviceId: String): Flow<Long> = lastSync.map { it[serviceId] ?: 0L }.distinctUntilChanged()

    fun lastSyncAt(serviceId: String): Long = lastSync.value[serviceId] ?: 0L

    fun observePullStats(serviceId: String): Flow<PullStats?> = pullStats.map { it[serviceId] }.distinctUntilChanged()

    fun lastPullAt(serviceId: String): Long = pullStats.value[serviceId]?.at ?: 0L

    /**
     * Записывает итог чтения списка: базы слияния по тайтлам, время (учитывается в «Последней
     * синхронизации») и счётчики для плитки «Тайтлов связано». Одна запись файла на весь проход.
     */
    fun recordPulled(serviceId: String, items: List<PulledTitle>, linked: Int, total: Int, now: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            val map = this.titles.value.toMutableMap()
            items.forEach { t ->
                val k = key(serviceId, t.malId)
                val current = map[k] ?: TitleSyncState(serviceId, t.malId, t.releaseId, null, 0, 0, false, null)
                map[k] = current.copy(
                    releaseId = t.releaseId ?: current.releaseId,
                    remoteStatus = t.baseRemote.status?.name,
                    remoteProgress = t.baseRemote.progress,
                    syncedAt = now,
                    base = t.baseLocal.serialize() + "|" + t.baseRemote.serialize(),
                )
            }
            this.titles.value = map
            lastSync.value = lastSync.value + (serviceId to now)
            pullStats.value = pullStats.value + (serviceId to PullStats(now, linked, total))
            save()
        }

    /** Забывает всё о сервисе (отключение аккаунта): базы слияния, время синхронизации, счётчики. */
    fun clearService(serviceId: String) = synchronized(lock) {
        titles.value = titles.value.filterValues { it.serviceId != serviceId }
        lastSync.value = lastSync.value - serviceId
        pullStats.value = pullStats.value - serviceId
        save()
    }

    /** Обновляет только счётчики «Тайтлов связано» и время проверки (без баз слияния и «Последней синхронизации»). */
    fun recordStats(serviceId: String, linked: Int, total: Int, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        pullStats.value = pullStats.value + (serviceId to PullStats(now, linked, total))
        save()
    }

    /** Количество тайтлов, синхронизированных с сервисом (для «Тайтлов связано», этап 4). */
    fun count(serviceId: String): Int = titles.value.values.count { it.serviceId == serviceId }

    fun markPending(serviceId: String, malId: Int, releaseId: Int?) = mutate(serviceId, malId, releaseId) {
        it.copy(pending = true, error = null)
    }

    fun markSynced(
        serviceId: String,
        malId: Int,
        releaseId: Int?,
        remoteStatus: String?,
        remoteProgress: Int,
        now: Long = System.currentTimeMillis(),
        stillPending: Boolean = false,
    ) = synchronized(lock) {
        mutateLocked(serviceId, malId, releaseId) {
            // base сбрасывается: следующее чтение возьмёт базу из только что подтверждённого состояния сервиса
            it.copy(remoteStatus = remoteStatus, remoteProgress = remoteProgress, syncedAt = now, pending = stillPending, error = null, base = null)
        }
        lastSync.value = lastSync.value + (serviceId to now)
        save()
    }

    fun markError(serviceId: String, malId: Int, releaseId: Int?, error: String?) = mutate(serviceId, malId, releaseId) {
        it.copy(error = error)
    }

    fun clearPending(serviceId: String, malId: Int) = synchronized(lock) {
        val current = titles.value[key(serviceId, malId)] ?: return@synchronized
        titles.value = titles.value + (key(serviceId, malId) to current.copy(pending = false, error = null))
        save()
    }

    /** Снимает «в очереди» со всех тайтлов сервиса (очередь очищена). */
    fun clearAllPending(serviceId: String) = synchronized(lock) {
        titles.value = titles.value.mapValues { (_, v) -> if (v.serviceId == serviceId) v.copy(pending = false, error = null) else v }
        save()
    }

    private fun mutate(serviceId: String, malId: Int, releaseId: Int?, transform: (TitleSyncState) -> TitleSyncState) =
        synchronized(lock) {
            mutateLocked(serviceId, malId, releaseId, transform)
            save()
        }

    private fun mutateLocked(serviceId: String, malId: Int, releaseId: Int?, transform: (TitleSyncState) -> TitleSyncState) {
        val k = key(serviceId, malId)
        val current = titles.value[k] ?: TitleSyncState(serviceId, malId, releaseId, null, 0, 0, false, null)
        titles.value = titles.value + (k to transform(current.copy(releaseId = releaseId ?: current.releaseId)))
    }

    private fun save() {
        val json = JSONObject()
            .put("titles", org.json.JSONArray().also { arr -> titles.value.values.forEach { arr.put(toJson(it)) } })
            .put("lastSync", JSONObject().also { o -> lastSync.value.forEach { (k, v) -> o.put(k, v) } })
            .put("pull", JSONObject().also { o ->
                pullStats.value.forEach { (k, v) -> o.put(k, JSONObject().put("at", v.at).put("linked", v.linked).put("total", v.total)) }
            })
        file.write(json)
    }

    private fun toJson(s: TitleSyncState) = JSONObject()
        .put("service", s.serviceId).put("mal", s.malId).put("release", s.releaseId ?: JSONObject.NULL)
        .put("status", s.remoteStatus ?: JSONObject.NULL).put("progress", s.remoteProgress)
        .put("syncedAt", s.syncedAt).put("pending", s.pending)
        .put("error", s.error ?: JSONObject.NULL).put("base", s.base ?: JSONObject.NULL)

    private fun parse(o: JSONObject?): TitleSyncState? = runCatching {
        TitleSyncState(
            serviceId = o!!.getString("service"),
            malId = o.getInt("mal"),
            releaseId = if (o.isNull("release")) null else o.getInt("release"),
            remoteStatus = if (o.isNull("status")) null else o.getString("status"),
            remoteProgress = o.optInt("progress"),
            syncedAt = o.optLong("syncedAt"),
            pending = o.optBoolean("pending"),
            error = if (o.isNull("error")) null else o.getString("error"),
            base = if (o.isNull("base")) null else o.getString("base"),
        )
    }.getOrNull()

    private fun key(serviceId: String, malId: Int) = "$serviceId:$malId"
}
