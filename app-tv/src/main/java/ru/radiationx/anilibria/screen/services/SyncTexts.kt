package ru.radiationx.anilibria.screen.services

import ru.radiationx.data.external.SyncOverview
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

fun changesText(n: Int): String = "$n " + plural(n, "изменение", "изменения", "изменений")

fun errorsText(n: Int): String = "$n " + plural(n, "ошибка", "ошибки", "ошибок")

private val RU = Locale("ru")

/** Плитка «Последняя синхронизация»: сегодня — HH:mm, раньше — dd.MM, HH:mm; 0 — «—». */
fun lastSyncText(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (atMs <= 0) return "—"
    val a = Calendar.getInstance().apply { timeInMillis = atMs }
    val n = Calendar.getInstance().apply { timeInMillis = nowMs }
    val sameDay = a.get(Calendar.YEAR) == n.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM, HH:mm", RU).format(Date(atMs))
}

/** «только что», «5 мин назад», «3 ч назад», «2 дн назад». */
fun agoText(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val min = ((nowMs - atMs) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 24 * 60 -> "${min / 60} ч назад"
        else -> "${min / (24 * 60)} дн назад"
    }
}

/** Статус на экране сервиса: null — обычный (по состоянию входа). */
fun overviewStatus(o: SyncOverview): Pair<String, Boolean>? = when {
    o.errors > 0 -> "Ошибка отправки · нужен повтор: ${changesText(o.errors)}" to true
    o.queued > 0 -> "В очереди ${changesText(o.queued)}" to false
    else -> null
}

/** Статус записи AniList по-русски (PLANNING/CURRENT/… → «Запланировано»/«Смотрю»/…). */
fun remoteStatusText(status: String?): String = when (status) {
    "PLANNING" -> "Запланировано"
    "CURRENT", "REPEATING" -> "Смотрю"
    "COMPLETED" -> "Просмотрено"
    "PAUSED" -> "Отложено"
    "DROPPED" -> "Брошено"
    null -> "Не в списке"
    else -> status
}

/** «5/11», у фильма — «Фильм», без данных о числе серий — «5». */
fun progressText(progress: Int, total: Int?, isMovie: Boolean): String = when {
    isMovie -> "Фильм"
    total != null && total > 0 -> "$progress/$total"
    else -> progress.toString()
}

/** «Отмечено в AniList · 5/11» / «… · Фильм». */
fun sentNoticeText(e: ru.radiationx.data.external.SyncSentEvent): String =
    "Отмечено в AniList · " + progressText(e.progress, e.totalEpisodes, e.isMovie)

/** Заголовок группы журнала: «Сегодня», «Вчера», «12 сентября». */
fun dayHeaderText(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    fun dayOf(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diffDays = Math.round((dayOf(nowMs) - dayOf(atMs)) / 86_400_000.0)
    return when (diffDays) {
        0L -> "Сегодня"
        1L -> "Вчера"
        else -> SimpleDateFormat("d MMMM", RU).format(Date(atMs))
    }
}

fun timeText(atMs: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(atMs))

/** Свёрнутая фраза журнала: что сделано простыми словами. */
fun journalSummaryText(e: ru.radiationx.data.external.JournalEntry): String =
    ru.radiationx.data.external.JournalTexts.summary(e)

/** Пункты раскрытой строки журнала. */
fun journalDetailLines(e: ru.radiationx.data.external.JournalEntry) =
    ru.radiationx.data.external.JournalTexts.details(e)

/** Мелкая сноска журнала: MAL id, ссылка, код HTTP. */
fun journalFootnote(e: ru.radiationx.data.external.JournalEntry): String? =
    ru.radiationx.data.external.JournalTexts.footnote(e)
