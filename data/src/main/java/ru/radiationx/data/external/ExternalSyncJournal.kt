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

enum class JournalDirection { OUT, IN, CHECK }

enum class JournalResult { DONE, RETRY_AT, ERROR, SKIPPED, UP_TO_DATE }

/** Запись журнала синхронизации. [itemId] — элемент очереди для «Повторить сейчас» / «Пропустить» (null — нет). */
data class JournalEntry(
    val id: Long,
    val time: Long,
    val serviceId: String,
    val direction: JournalDirection,
    val malId: Int?,
    val releaseId: Int?,
    val title: String,
    /** Что сделано: «серия 5 → прогресс 5/11», «коллекция «Отложено»». */
    val text: String,
    /** Подробности: объединение событий, код ошибки и попытка. */
    val detail: String?,
    val result: JournalResult,
    /** Для [JournalResult.RETRY_AT] — когда следующая попытка (epoch ms). */
    val retryAt: Long? = null,
    val itemId: Long? = null,
)

/**
 * Персистентный журнал синхронизации (≤ 500 записей, ≤ 30 дней), файл `filesDir/external/journal.json`.
 * Для экрана журнала (этап 3b): [observe], [retryNow], [skip].
 */
class ExternalSyncJournal @Inject constructor(
    context: Context,
    private val outbox: ExternalOutbox,
    private val syncState: ExternalSyncState,
) {

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val WEEK_MS = 7L * 24 * 3600 * 1000
    }

    private val file = ExternalJsonFile(File(context.filesDir, "external/journal.json"))
    private val lock = Any()
    private var nextId = 1L
    private val state = MutableStateFlow<List<JournalEntry>>(emptyList())

    /** Все записи, новые первыми. */
    val entries: StateFlow<List<JournalEntry>> = state.asStateFlow()

    /** Слушатель «Повторить сейчас» (engine будит цикл отправки). */
    @Volatile
    var onRetryRequested: (() -> Unit)? = null

    init {
        val json = file.read()
        val arr = json?.optJSONArray("entries")
        val list = arr?.let { a -> (0 until a.length()).mapNotNull { parse(a.optJSONObject(it)) } }.orEmpty()
        nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        state.value = trim(list, System.currentTimeMillis())
    }

    fun observe(serviceId: String): Flow<List<JournalEntry>> = state.map { l -> l.filter { it.serviceId == serviceId } }

    /** Ошибки (ERROR) за неделю до [now] — для подписи «N ошибок за неделю». */
    fun observeWeekErrors(serviceId: String, now: () -> Long = System::currentTimeMillis): Flow<Int> =
        state.map { l -> l.count { it.serviceId == serviceId && it.result == JournalResult.ERROR && now() - it.time <= WEEK_MS } }

    /**
     * Добавляет запись. Если для того же [JournalEntry.itemId] уже есть незавершённая запись
     * (RETRY_AT/ERROR), она заменяется, а не дублируется.
     */
    fun record(
        serviceId: String,
        direction: JournalDirection,
        malId: Int?,
        releaseId: Int?,
        title: String,
        text: String,
        result: JournalResult,
        detail: String? = null,
        retryAt: Long? = null,
        itemId: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): JournalEntry = synchronized(lock) {
        val entry = JournalEntry(nextId++, now, serviceId, direction, malId, releaseId, title, text, detail, result, retryAt, itemId)
        val base = if (itemId != null) {
            state.value.filterNot { it.itemId == itemId && (it.result == JournalResult.RETRY_AT || it.result == JournalResult.ERROR) }
        } else state.value
        val list = trim(listOf(entry) + base, now)
        state.value = list
        file.write(JSONObject().put("entries", JSONArray().also { arr -> list.forEach { arr.put(toJson(it)) } }))
        entry
    }

    /** «Повторить сейчас» для элемента очереди [itemId]. */
    fun retryNow(itemId: Long) {
        outbox.retryNow(itemId)
        onRetryRequested?.invoke()
    }

    /** «Пропустить»: элемент убирается из очереди, в журнале — запись SKIPPED. */
    fun skip(itemId: Long) {
        val item = outbox.remove(itemId) ?: return
        val other = outbox.items.value.any { it.serviceId == item.serviceId && it.malId == item.malId }
        if (!other) syncState.clearPending(item.serviceId, item.malId)
        record(
            item.serviceId, JournalDirection.OUT, item.malId, item.releaseId, item.title,
            "изменение пропущено пользователем", JournalResult.SKIPPED,
        )
    }

    private fun trim(list: List<JournalEntry>, now: Long): List<JournalEntry> =
        list.filter { now - it.time <= MAX_AGE_MS }.take(MAX_ENTRIES)

    private fun toJson(e: JournalEntry) = JSONObject()
        .put("id", e.id).put("time", e.time).put("service", e.serviceId).put("dir", e.direction.name)
        .put("mal", e.malId ?: JSONObject.NULL).put("release", e.releaseId ?: JSONObject.NULL)
        .put("title", e.title).put("text", e.text).put("detail", e.detail ?: JSONObject.NULL)
        .put("result", e.result.name).put("retryAt", e.retryAt ?: JSONObject.NULL).put("item", e.itemId ?: JSONObject.NULL)

    private fun parse(o: JSONObject?): JournalEntry? = runCatching {
        JournalEntry(
            id = o!!.getLong("id"),
            time = o.getLong("time"),
            serviceId = o.getString("service"),
            direction = JournalDirection.valueOf(o.getString("dir")),
            malId = if (o.isNull("mal")) null else o.getInt("mal"),
            releaseId = if (o.isNull("release")) null else o.getInt("release"),
            title = o.optString("title"),
            text = o.optString("text"),
            detail = if (o.isNull("detail")) null else o.getString("detail"),
            result = JournalResult.valueOf(o.getString("result")),
            retryAt = if (o.isNull("retryAt")) null else o.getLong("retryAt"),
            itemId = if (o.isNull("item")) null else o.getLong("item"),
        )
    }.getOrNull()
}
