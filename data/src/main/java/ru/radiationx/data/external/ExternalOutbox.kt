package ru.radiationx.data.external

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Персистентная очередь отправки изменений во внешние сервисы (общая, ключ элемента — пара
 * serviceId + malId). Файл `filesDir/external/outbox.json` пишется атомарно. События одной пары
 * сливаются в один элемент ([SyncRules.merge]).
 */
class ExternalOutbox @Inject constructor(context: Context) {

    private val file = ExternalJsonFile(File(context.filesDir, "external/outbox.json"))
    private val lock = Any()
    private var nextId = 1L
    private val state = MutableStateFlow<List<OutboxItem>>(emptyList())

    val items: StateFlow<List<OutboxItem>> = state.asStateFlow()

    init {
        val json = file.read()
        val list = json?.optJSONArray("items")
            ?.let { arr -> (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) } }
            .orEmpty()
        nextId = (json?.optLong("nextId") ?: 1L).coerceAtLeast((list.maxOfOrNull { it.id } ?: 0L) + 1)
        state.value = list
    }

    fun observe(serviceId: String): Flow<List<OutboxItem>> =
        state.map { list -> list.filter { it.serviceId == serviceId } }

    fun get(id: Long): OutboxItem? = state.value.firstOrNull { it.id == id }

    /** Ставит изменение в очередь; если для пары уже есть элемент — сливает. Возвращает итоговый элемент. */
    fun enqueue(
        serviceId: String,
        malId: Int,
        releaseId: Int?,
        title: String,
        desired: DesiredState,
        source: OutboxSource,
        episode: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): OutboxItem = synchronized(lock) {
        val fresh = OutboxItem(
            id = nextId,
            serviceId = serviceId,
            malId = malId,
            releaseId = releaseId,
            title = title,
            desired = desired,
            source = source,
            createdAt = now,
            episodes = listOfNotNull(episode),
        )
        val list = state.value
        val existing = list.firstOrNull { it.serviceId == serviceId && it.malId == malId }
        if (existing == null) {
            nextId++
            persist(list + fresh)
            fresh
        } else {
            // новое событие снимает паузу повтора: копилось из-за ошибки — пробуем сразу
            val merged = SyncRules.merge(existing, fresh).copy(nextAttemptAt = 0, attempts = 0, lastError = null)
            persist(list.map { if (it.id == existing.id) merged else it })
            merged
        }
    }

    /** Удаляет элемент, если он не менялся с версии [version] (иначе его снова отправят). */
    fun complete(id: Long, version: Int): Boolean = synchronized(lock) {
        val item = get(id) ?: return true
        if (item.version != version) return false
        persist(state.value.filter { it.id != id })
        true
    }

    fun remove(id: Long): OutboxItem? = synchronized(lock) {
        val item = get(id) ?: return null
        persist(state.value.filter { it.id != id })
        item
    }

    fun update(id: Long, transform: (OutboxItem) -> OutboxItem) = synchronized(lock) {
        persist(state.value.map { if (it.id == id) transform(it) else it })
    }

    /** «Повторить сейчас»: снимает ошибку и паузу. */
    fun retryNow(id: Long) = update(id) { it.copy(state = OutboxState.PENDING, nextAttemptAt = 0, attempts = 0) }

    /** Все элементы сервиса, включая ошибочные, — на немедленную отправку. */
    fun retryAll(serviceId: String) = synchronized(lock) {
        persist(state.value.map {
            if (it.serviceId == serviceId) it.copy(state = OutboxState.PENDING, nextAttemptAt = 0, attempts = 0) else it
        })
    }

    /** Появилась сеть: элементы, ждавшие backoff, пробуем сразу (лимит 429 учитывает клиент). */
    fun expedite(serviceId: String) = synchronized(lock) {
        persist(state.value.map {
            if (it.serviceId == serviceId && it.state == OutboxState.PENDING) it.copy(nextAttemptAt = 0) else it
        })
    }

    /** Очищает очередь сервиса, возвращает удалённые элементы. */
    fun clear(serviceId: String): List<OutboxItem> = synchronized(lock) {
        val removed = state.value.filter { it.serviceId == serviceId }
        if (removed.isNotEmpty()) persist(state.value.filter { it.serviceId != serviceId })
        removed
    }

    private fun persist(list: List<OutboxItem>) {
        state.value = list
        val json = JSONObject()
            .put("nextId", nextId)
            .put("items", JSONArray().also { arr -> list.forEach { arr.put(toJson(it)) } })
        file.write(json)
    }

    private fun toJson(i: OutboxItem) = JSONObject()
        .put("id", i.id).put("service", i.serviceId).put("mal", i.malId)
        .put("release", i.releaseId ?: JSONObject.NULL).put("title", i.title)
        .put("status", i.desired.status?.name ?: JSONObject.NULL)
        .put("progress", i.desired.progress ?: JSONObject.NULL)
        .put("total", i.desired.totalEpisodes ?: JSONObject.NULL)
        .put("source", i.source.name).put("createdAt", i.createdAt)
        .put("attempts", i.attempts).put("next", i.nextAttemptAt)
        .put("error", i.lastError ?: JSONObject.NULL).put("merged", i.mergedCount)
        .put("episodes", JSONArray(i.episodes)).put("state", i.state.name).put("version", i.version)

    private fun parse(o: JSONObject?): OutboxItem? = runCatching {
        OutboxItem(
            id = o!!.getLong("id"),
            serviceId = o.getString("service"),
            malId = o.getInt("mal"),
            releaseId = if (o.isNull("release")) null else o.getInt("release"),
            title = o.optString("title"),
            desired = DesiredState(
                status = if (o.isNull("status")) null else DesiredStatus.valueOf(o.getString("status")),
                progress = if (o.isNull("progress")) null else o.getInt("progress"),
                totalEpisodes = if (o.isNull("total")) null else o.getInt("total"),
            ),
            source = OutboxSource.valueOf(o.getString("source")),
            createdAt = o.optLong("createdAt"),
            attempts = o.optInt("attempts"),
            nextAttemptAt = o.optLong("next"),
            lastError = if (o.isNull("error")) null else o.getString("error"),
            mergedCount = o.optInt("merged", 1),
            episodes = o.optJSONArray("episodes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
            state = OutboxState.valueOf(o.optString("state", "PENDING")),
            version = o.optInt("version"),
        )
    }.getOrNull()
}
