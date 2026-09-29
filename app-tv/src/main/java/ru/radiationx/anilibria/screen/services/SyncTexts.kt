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
