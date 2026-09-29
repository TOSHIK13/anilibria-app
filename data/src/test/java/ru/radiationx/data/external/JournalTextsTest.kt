package ru.radiationx.data.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTextsTest {

    private fun entry(
        result: JournalResult = JournalResult.DONE,
        dir: JournalDirection = JournalDirection.OUT,
        text: String = "",
        detail: String? = null,
        retryAt: Long? = null,
        time: Long = 1_000_000_000_000L,
        meta: JournalMeta = JournalMeta(),
    ) = JournalEntry(1, time, "anilist", dir, 10, 5, "Тайтл", text, detail, result, retryAt, 3, meta)

    private val al = { st: String?, p: Int -> JournalState(st, p, 11) }

    @Test
    fun `episode summary is human`() {
        val e = entry(meta = JournalMeta(origin = JournalOrigin.EPISODE, episodes = listOf(5), anilistAfter = al("CURRENT", 5)))
        assertEquals("Отметили серию 5 → в AniList прогресс 5/11", JournalTexts.summary(e))
    }

    @Test
    fun `merged episodes and details`() {
        val e = entry(
            meta = JournalMeta(
                origin = JournalOrigin.EPISODE, episodes = listOf(4, 5), mergedCount = 2,
                anilistBefore = al("CURRENT", 3), anilistAfter = al("CURRENT", 5),
            )
        )
        assertEquals("Отметили серии 4 и 5 → в AniList прогресс 5/11", JournalTexts.summary(e))
        val d = JournalTexts.details(e).associate { it.label to it.value }
        assertEquals("Вы отметили серию в плеере", d["Откуда"])
        assertEquals("Смотрю · 3/11 → Смотрю · 5/11", d["AniList"])
        assertEquals("Объединены 2 события (серии 4 и 5) в один запрос", d["Объединение"])
        assertEquals("Готово", d["Результат"])
    }

    @Test
    fun `movie shows Film not 1 of 1`() {
        val e = entry(meta = JournalMeta(origin = JournalOrigin.COLLECTION, localAfter = JournalState("COMPLETED", null), anilistAfter = JournalState("COMPLETED", 1, 1, true)))
        assertEquals("Коллекция «Просмотрено» → статус в AniList «Просмотрено»", JournalTexts.summary(e))
        assertEquals("Просмотрено · Фильм", JournalTexts.stateText(JournalState("COMPLETED", 1, 1, true), false))
    }

    @Test
    fun `collection and removal`() {
        val c = entry(meta = JournalMeta(origin = JournalOrigin.COLLECTION, localAfter = JournalState("PAUSED", null), anilistAfter = al("PAUSED", 3)))
        assertEquals("Коллекция «Отложено» → статус в AniList «Отложено»", JournalTexts.summary(c))
        val r = entry(meta = JournalMeta(origin = JournalOrigin.COLLECTION, localAfter = JournalState(null, null)))
        assertEquals("Коллекция снята → запись удалена из AniList", JournalTexts.summary(r))
    }

    @Test
    fun `incoming summary`() {
        val e = entry(
            dir = JournalDirection.IN,
            meta = JournalMeta(
                origin = JournalOrigin.REMOTE,
                localBefore = JournalState("CURRENT", 3), localAfter = JournalState("COMPLETED", 11),
            ),
        )
        assertEquals("Из AniList: «Просмотрено» → коллекция AniLiberty, отмечены серии 1–11", JournalTexts.summary(e))
    }

    @Test
    fun `check summary`() {
        val e = entry(dir = JournalDirection.CHECK, meta = JournalMeta(origin = JournalOrigin.SCHEDULED, changes = 1))
        assertEquals("Плановая проверка: изменений 1", JournalTexts.summary(e))
        val none = entry(dir = JournalDirection.CHECK, result = JournalResult.UP_TO_DATE, meta = JournalMeta(origin = JournalOrigin.MANUAL_SYNC, changes = 0))
        assertEquals("Проверка по запросу: изменений нет", JournalTexts.summary(none))
    }

    @Test
    fun `errors are decoded without http code`() {
        assertEquals("Вход в AniList истёк или токен отозван", JournalTexts.errorText(JournalErrorKind.AUTH))
        assertEquals("AniList попросил подождать (слишком много запросов)", JournalTexts.errorText(JournalErrorKind.RATE_LIMIT))
        assertEquals("Сервер AniList недоступен", JournalTexts.errorText(JournalErrorKind.SERVER))
        assertEquals("Нет подключения к интернету", JournalTexts.errorText(JournalErrorKind.NETWORK))
    }

    @Test
    fun `retry shows attempt and next time, http only in footnote`() {
        val retryAt = 1_000_000_000_000L + 14 * 60_000
        val e = entry(
            result = JournalResult.RETRY_AT, retryAt = retryAt,
            meta = JournalMeta(origin = JournalOrigin.EPISODE, httpCode = 429, errorKind = JournalErrorKind.RATE_LIMIT, attempt = 2, maxAttempts = 5, anilistId = 678),
        )
        val text = JournalTexts.resultText(e)
        assertTrue(text, text.startsWith("AniList попросил подождать (слишком много запросов) · попытка 2 из 5, следующая в "))
        assertFalse(text.contains("429"))
        assertEquals("MAL id 10 · anilist.co/anime/678 · HTTP 429", JournalTexts.footnote(e))
    }

    @Test
    fun `skipped reason and up to date`() {
        val s = entry(result = JournalResult.SKIPPED, dir = JournalDirection.IN, meta = JournalMeta(origin = JournalOrigin.REMOTE, reason = JournalReason.NOT_ON_ALILIBRIA))
        assertEquals("Пропущено: тайтла нет на AniLiberty (MAL id 10)", JournalTexts.resultText(s))
        val u = entry(result = JournalResult.UP_TO_DATE, meta = JournalMeta(origin = JournalOrigin.EPISODE))
        assertEquals("Уже совпадало — запрос не понадобился", JournalTexts.resultText(u))
    }

    @Test
    fun `event time shown only when it differs`() {
        val same = entry(meta = JournalMeta(origin = JournalOrigin.EPISODE, eventTime = 1_000_000_000_000L))
        assertTrue(JournalTexts.details(same).none { it.label == "Время" })
        val later = entry(meta = JournalMeta(origin = JournalOrigin.EPISODE, eventTime = 1_000_000_000_000L - 3_600_000))
        assertTrue(JournalTexts.details(later).any { it.label == "Время" })
    }

    @Test
    fun `old entry without meta falls back to stored text`() {
        val e = entry(text = "серия 5 → прогресс 5/11", detail = "серии 4 и 5 объединены в один запрос")
        assertEquals("Серия 5 → прогресс 5/11", JournalTexts.summary(e))
        val d = JournalTexts.details(e)
        assertEquals(listOf("Результат", "Подробности"), d.map { it.label })
        assertNull(JournalTexts.footnote(e.copy(malId = null)))
    }
}
