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

/** Откуда взялась запись: что запустило синхронизацию. */
enum class JournalOrigin { EPISODE, COLLECTION, REMOTE, FIRST_SYNC, SCHEDULED, MANUAL_SYNC, COMPARE_PUSH, ACCOUNT, USER }

/** Класс ошибки для расшифровки человеческим текстом. */
enum class JournalErrorKind { AUTH, RATE_LIMIT, SERVER, REJECTED, NETWORK, BAD_RESPONSE, OTHER }

/** Причина пропуска. */
enum class JournalReason { NOT_ON_ALILIBRIA, NOT_ON_ANILIST, NO_MAL_ID, RELEASE_NO_MAL, USER_SKIPPED, ACCOUNT_UNLINKED, FIRST_SYNC_PENDING, CHOICE_NOT_MADE }

/**
 * Состояние записи на одной стороне. [status] — имя статуса ([DesiredStatus] / статус AniList),
 * null — записи/коллекции нет. [progress] — просмотрено серий, null — неизвестно.
 */
data class JournalState(
    val status: String?,
    val progress: Int? = null,
    val total: Int? = null,
    val isMovie: Boolean = false,
)

/** Подробности записи; все поля необязательны (старые записи журнала их не содержат). */
data class JournalMeta(
    val origin: JournalOrigin? = null,
    /** Когда произошло событие (у отправки — раньше, чем время записи в журнале). */
    val eventTime: Long? = null,
    val anilistBefore: JournalState? = null,
    val anilistAfter: JournalState? = null,
    val localBefore: JournalState? = null,
    val localAfter: JournalState? = null,
    val mergedCount: Int? = null,
    /** Номера серий события/объединённых событий. */
    val episodes: List<Int>? = null,
    val httpCode: Int? = null,
    val errorKind: JournalErrorKind? = null,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val reason: JournalReason? = null,
    /** Для проверок/первой синхронизации: найдено/применено изменений. */
    val changes: Int? = null,
    /** Для проверок: сколько ушло в очередь отправки. */
    val queued: Int? = null,
    /** id тайтла на anilist.co (для ссылки-текста). */
    val anilistId: Long? = null,
)

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
    val meta: JournalMeta = JournalMeta(),
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
        meta: JournalMeta = JournalMeta(),
    ): JournalEntry = synchronized(lock) {
        val entry = JournalEntry(nextId++, now, serviceId, direction, malId, releaseId, title, text, detail, result, retryAt, itemId, meta)
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
            meta = JournalMeta(origin = JournalOrigin.USER, reason = JournalReason.USER_SKIPPED, eventTime = item.createdAt),
        )
    }

    private fun trim(list: List<JournalEntry>, now: Long): List<JournalEntry> =
        list.filter { now - it.time <= MAX_AGE_MS }.take(MAX_ENTRIES)

    private fun toJson(e: JournalEntry) = JSONObject()
        .put("id", e.id).put("time", e.time).put("service", e.serviceId).put("dir", e.direction.name)
        .put("mal", e.malId ?: JSONObject.NULL).put("release", e.releaseId ?: JSONObject.NULL)
        .put("title", e.title).put("text", e.text).put("detail", e.detail ?: JSONObject.NULL)
        .put("result", e.result.name).put("retryAt", e.retryAt ?: JSONObject.NULL).put("item", e.itemId ?: JSONObject.NULL)
        .put("meta", metaToJson(e.meta))

    private fun stateToJson(s: JournalState?): Any = if (s == null) JSONObject.NULL else JSONObject()
        .put("status", s.status ?: JSONObject.NULL).put("progress", s.progress ?: JSONObject.NULL)
        .put("total", s.total ?: JSONObject.NULL).put("movie", s.isMovie)

    private fun metaToJson(m: JournalMeta) = JSONObject()
        .put("origin", m.origin?.name ?: JSONObject.NULL).put("eventTime", m.eventTime ?: JSONObject.NULL)
        .put("alBefore", stateToJson(m.anilistBefore)).put("alAfter", stateToJson(m.anilistAfter))
        .put("localBefore", stateToJson(m.localBefore)).put("localAfter", stateToJson(m.localAfter))
        .put("merged", m.mergedCount ?: JSONObject.NULL)
        .put("episodes", m.episodes?.let { l -> JSONArray().also { a -> l.forEach { a.put(it) } } } ?: JSONObject.NULL)
        .put("http", m.httpCode ?: JSONObject.NULL).put("errorKind", m.errorKind?.name ?: JSONObject.NULL)
        .put("attempt", m.attempt ?: JSONObject.NULL).put("maxAttempts", m.maxAttempts ?: JSONObject.NULL)
        .put("reason", m.reason?.name ?: JSONObject.NULL)
        .put("anilistId", m.anilistId ?: JSONObject.NULL)
        .put("changes", m.changes ?: JSONObject.NULL).put("queued", m.queued ?: JSONObject.NULL)

    private fun parseState(o: JSONObject?): JournalState? = o?.let {
        JournalState(
            status = if (it.isNull("status")) null else it.getString("status"),
            progress = if (it.isNull("progress")) null else it.getInt("progress"),
            total = if (it.isNull("total")) null else it.getInt("total"),
            isMovie = it.optBoolean("movie", false),
        )
    }

    private fun parseMeta(o: JSONObject?): JournalMeta {
        if (o == null) return JournalMeta()
        fun int(k: String) = if (o.isNull(k)) null else o.optInt(k)
        return JournalMeta(
            origin = enumOrNull<JournalOrigin>(o.optString("origin", "")),
            eventTime = if (o.isNull("eventTime")) null else o.optLong("eventTime"),
            anilistBefore = parseState(o.optJSONObject("alBefore")),
            anilistAfter = parseState(o.optJSONObject("alAfter")),
            localBefore = parseState(o.optJSONObject("localBefore")),
            localAfter = parseState(o.optJSONObject("localAfter")),
            mergedCount = int("merged"),
            episodes = o.optJSONArray("episodes")?.let { a -> (0 until a.length()).map { a.getInt(it) } },
            httpCode = int("http"),
            errorKind = enumOrNull<JournalErrorKind>(o.optString("errorKind", "")),
            attempt = int("attempt"),
            maxAttempts = int("maxAttempts"),
            reason = enumOrNull<JournalReason>(o.optString("reason", "")),
            changes = int("changes"),
            queued = int("queued"),
            anilistId = if (o.isNull("anilistId")) null else o.optLong("anilistId"),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        if (name.isEmpty()) null else runCatching { enumValueOf<T>(name) }.getOrNull()

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
            meta = runCatching { parseMeta(o.optJSONObject("meta")) }.getOrDefault(JournalMeta()),
        )
    }.getOrNull()
}
