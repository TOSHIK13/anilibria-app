package ru.radiationx.data.external

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Пункт раскрытой строки журнала: «Откуда: …». */
data class JournalDetailLine(val label: String, val value: String)

/**
 * Человеческие тексты журнала синхронизации (чистые функции, без Android): свёрнутая фраза,
 * пункты раскрытой строки и мелкая сноска. Старые записи без [JournalMeta] показываются по
 * сохранённым [JournalEntry.text]/[JournalEntry.detail].
 */
object JournalTexts {

    private val RU = Locale("ru")

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(ms))

    private fun status(raw: String?): String = raw?.let { AniListWriteRules.statusRu(it) } ?: ""

    private fun state(s: JournalState): String = when {
        s.isMovie -> "Фильм"
        s.total != null && s.total > 0 && s.progress != null -> "${s.progress}/${s.total}"
        else -> s.progress?.toString() ?: ""
    }

    /** «Смотрю · 5/11», «Не в списке» / «Не в коллекциях». */
    fun stateText(s: JournalState, local: Boolean): String {
        val st = s.status
        if (st == null) {
            val p = state(s)
            if (local && p.isNotEmpty()) return "просмотрено $p"
            return if (local) "Не в коллекциях" else "Не в списке"
        }
        val progress = state(s)
        return status(st) + if (progress.isNotEmpty()) " · $progress" else ""
    }

    /** «серия 5», «серии 4 и 5», «серии 4–7». */
    fun episodesText(eps: List<Int>, accusative: Boolean = false): String = when {
        eps.isEmpty() -> ""
        eps.size == 1 -> (if (accusative) "серию" else "серия") + " ${eps[0]}"
        eps.size == 2 -> "серии ${eps[0]} и ${eps[1]}"
        else -> "серии ${eps.first()}–${eps.last()}"
    }

    /** Расшифровка ошибки; без кода HTTP (он выводится отдельно, мелко). */
    fun errorText(kind: JournalErrorKind?, fallback: String? = null): String = when (kind) {
        JournalErrorKind.AUTH -> "Вход в AniList истёк или токен отозван"
        JournalErrorKind.RATE_LIMIT -> "AniList попросил подождать (слишком много запросов)"
        JournalErrorKind.SERVER -> "Сервер AniList недоступен"
        JournalErrorKind.REJECTED -> "AniList отклонил запрос"
        JournalErrorKind.NETWORK -> "Нет подключения к интернету"
        JournalErrorKind.BAD_RESPONSE -> "AniList вернул неожиданный ответ"
        JournalErrorKind.OTHER, null -> fallback?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить"
    }

    fun reasonText(reason: JournalReason, malId: Int?): String = when (reason) {
        JournalReason.NOT_ON_ALILIBRIA -> "тайтла нет на AniLiberty" + (malId?.let { " (MAL id $it)" } ?: "")
        JournalReason.NOT_ON_ANILIST -> "тайтла нет на AniList" + (malId?.let { " (MAL id $it)" } ?: "")
        JournalReason.NO_MAL_ID -> "у записи AniList нет MAL id"
        JournalReason.RELEASE_NO_MAL -> "у релиза нет MAL id"
        JournalReason.USER_SKIPPED -> "вы пропустили изменение"
        JournalReason.ACCOUNT_UNLINKED -> "аккаунт отключён, очередь очищена"
        JournalReason.FIRST_SYNC_PENDING -> "первая синхронизация ещё не выполнена"
        JournalReason.CHOICE_NOT_MADE -> "статусы расходятся, выбор не сделан"
    }

    fun originText(o: JournalOrigin): String = when (o) {
        JournalOrigin.EPISODE -> "Вы отметили серию в плеере"
        JournalOrigin.COLLECTION -> "Вы сменили коллекцию"
        JournalOrigin.REMOTE -> "Изменено в AniList (с другого устройства)"
        JournalOrigin.FIRST_SYNC -> "Первая синхронизация"
        JournalOrigin.SCHEDULED -> "Плановая проверка"
        JournalOrigin.MANUAL_SYNC -> "Вручную: Синхронизировать сейчас"
        JournalOrigin.COMPARE_PUSH -> "Отправка после сравнения списков"
        JournalOrigin.ACCOUNT -> "Отключение аккаунта"
        JournalOrigin.USER -> "Вы нажали «Пропустить»"
    }

    private fun capital(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Фраза свёрнутой строки (без названия тайтла). */
    fun summary(e: JournalEntry): String {
        val m = e.meta
        val origin = m.origin ?: return capital(e.text)
        val reason = m.reason
        return when {
            origin == JournalOrigin.USER -> "Изменение пропущено вами"
            origin == JournalOrigin.ACCOUNT -> "Аккаунт отключён — очередь очищена" + (m.changes?.let { " (не отправлено: $it)" } ?: "")
            e.direction == JournalDirection.CHECK -> checkSummary(e)
            e.result == JournalResult.SKIPPED && reason != null -> capital(reasonText(reason, e.malId)) + " — пропущено"
            e.direction == JournalDirection.IN -> inSummary(e)
            else -> outSummary(e)
        }
    }

    private fun checkSummary(e: JournalEntry): String {
        val m = e.meta
        val head = when (m.origin) {
            JournalOrigin.FIRST_SYNC -> "Первая синхронизация"
            JournalOrigin.MANUAL_SYNC -> "Проверка по запросу"
            else -> "Плановая проверка"
        }
        return when {
            e.result == JournalResult.ERROR && m.origin == JournalOrigin.FIRST_SYNC -> "$head: изменений ${m.changes ?: 0}, есть ошибки"
            e.result == JournalResult.ERROR -> "$head не выполнена: " + errorText(m.errorKind).replaceFirstChar { it.lowercase() }
            m.reason == JournalReason.CHOICE_NOT_MADE -> "Статусы расходятся, выбор не сделан — оставлено как есть"
            m.reason == JournalReason.FIRST_SYNC_PENDING -> "$head: найдено изменений ${m.changes ?: 0}, ничего не применялось (первая синхронизация не выполнена)"
            (m.changes ?: 0) == 0 -> "$head: изменений нет"
            else -> "$head: изменений ${m.changes}"
        }
    }

    private fun inSummary(e: JournalEntry): String {
        val m = e.meta
        if (e.result == JournalResult.ERROR) return "Не удалось применить изменение из AniList"
        val before = m.localBefore
        val after = m.localAfter ?: return capital(e.text)
        if (after.status == null && before?.status != null) return "Запись удалена в AniList → убрана из коллекций AniLiberty"
        val raised = after.progress != null && (before?.progress ?: 0) < after.progress && after.progress > 0
        val statusChanged = after.status != null && after.status != before?.status
        val head = if (statusChanged) "Из AniList: «${status(after.status)}» → коллекция AniLiberty" else "Из AniList: прогресс ${state(m.anilistAfter ?: after)} → серии AniLiberty"
        return head + if (raised) ", отмечены серии 1–${after.progress}" else ""
    }

    private fun outSummary(e: JournalEntry): String {
        val m = e.meta
        val local = m.localAfter
        val al = m.anilistAfter
        val done = e.result == JournalResult.DONE || e.result == JournalResult.UP_TO_DATE
        val tail = if (e.result == JournalResult.UP_TO_DATE) " (в AniList уже так)" else ""
        val progress = (al ?: local)?.let { state(it).takeIf { s -> s.isNotEmpty() } }
        return when (m.origin) {
            JournalOrigin.EPISODE -> {
                val movie = al?.isMovie == true
                val what = when {
                    movie -> "Отметили просмотр фильма"
                    m.episodes.isNullOrEmpty() -> "Отметили все серии"
                    else -> "Отметили " + episodesText(m.episodes, accusative = true)
                }
                val to = when {
                    !done -> ""
                    movie -> " → в AniList «Просмотрено»"
                    progress != null -> " → в AniList прогресс $progress" + if (al?.status == "COMPLETED") ", «Просмотрено»" else ""
                    else -> " → в AniList"
                }
                what + to + tail
            }

            JournalOrigin.COLLECTION -> {
                val st = local?.status
                if (st == null) "Коллекция снята → запись удалена из AniList$tail"
                else "Коллекция «${status(st)}»" + (if (done) " → статус в AniList «${status(al?.status ?: st)}»" else "") + tail
            }

            else -> {
                val st = local?.status ?: al?.status
                if (local != null && local.status == null && local.progress == null) "Запись удалена из AniList$tail"
                else "Отправка в AniList" + (st?.let { ": «${status(it)}»" } ?: "") + (progress?.let { ", прогресс $it" } ?: "") + tail
            }
        }
    }

    /** Пункты раскрытой строки. Пустой список — детали показывать нечем. */
    fun details(e: JournalEntry): List<JournalDetailLine> {
        val m = e.meta
        val out = ArrayList<JournalDetailLine>()
        m.origin?.let { out += JournalDetailLine("Откуда", originText(it)) }
        change("AniList", m.anilistBefore, m.anilistAfter, false)?.let { out += it }
        change("AniLiberty", m.localBefore, m.localAfter, true)?.let { out += it }
        val merged = m.mergedCount
        if (merged != null && merged > 1) {
            val eps = m.episodes.orEmpty()
            val which = if (eps.isNotEmpty()) " (" + episodesText(eps) + ")" else ""
            out += JournalDetailLine("Объединение", "Объединены $merged ${eventsWord(merged)}$which в один запрос")
        }
        out += JournalDetailLine("Результат", resultText(e))
        val ev = m.eventTime
        if (ev != null && clock(ev) != clock(e.time)) {
            out += JournalDetailLine("Время", "событие ${clock(ev)} · отправлено ${clock(e.time)}")
        }
        if (m.origin == null && !e.detail.isNullOrBlank() && e.result != JournalResult.ERROR && e.result != JournalResult.RETRY_AT) out += JournalDetailLine("Подробности", e.detail)
        return out
    }

    private fun eventsWord(n: Int): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> "событий"
            mod10 == 1 -> "событие"
            mod10 in 2..4 -> "события"
            else -> "событий"
        }
    }

    private fun change(label: String, before: JournalState?, after: JournalState?, local: Boolean): JournalDetailLine? {
        val text = when {
            before != null && after != null ->
                if (before == after) "без изменений: " + stateText(after, local)
                else stateText(before, local) + " → " + stateText(after, local)
            after != null -> stateText(after, local)
            before != null -> stateText(before, local)
            else -> return null
        }
        return JournalDetailLine(label, text)
    }

    /** Итог человеческим текстом; для ошибок — расшифровка + попытка и время следующей. */
    fun resultText(e: JournalEntry): String {
        val m = e.meta
        return when (e.result) {
            JournalResult.DONE -> "Готово"
            JournalResult.UP_TO_DATE -> if (e.direction == JournalDirection.CHECK) "Изменений нет" else "Уже совпадало — запрос не понадобился"
            JournalResult.SKIPPED -> "Пропущено" + (m.reason?.let { ": " + reasonText(it, e.malId) } ?: e.text.takeIf { m.origin == null && it.isNotBlank() }?.let { ": $it" }.orEmpty())
            JournalResult.RETRY_AT -> errorText(m.errorKind, e.detail) + attemptText(e, retry = true)
            JournalResult.ERROR -> errorText(m.errorKind, e.detail) + attemptText(e, retry = false)
        }
    }

    private fun attemptText(e: JournalEntry, retry: Boolean): String {
        val a = e.meta.attempt
        val max = e.meta.maxAttempts
        val parts = ArrayList<String>()
        if (a != null && max != null) parts += "попытка $a из $max"
        if (retry) e.retryAt?.let { parts += "следующая в ${clock(it)}" }
        else if (a != null) parts += "нужен повтор вручную"
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(", ")
    }

    /** Мелкая серая сноска: MAL id, ссылка-текст anilist.co, код HTTP. */
    fun footnote(e: JournalEntry): String? {
        val parts = ArrayList<String>()
        e.malId?.let { parts += "MAL id $it" }
        e.meta.anilistId?.let { parts += "anilist.co/anime/$it" }
        e.meta.httpCode?.let { parts += "HTTP $it" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
