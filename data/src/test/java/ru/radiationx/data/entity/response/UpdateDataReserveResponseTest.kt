package ru.radiationx.data.entity.response

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.entity.mapper.Fixtures
import ru.radiationx.data.entity.response.updater.UpdateDataRootResponse

/**
 * TV берёт обновления только из reserve `check-tv.json` (без legacy `query=app_update`).
 * Файл отдаётся как есть, без `{status, data}` обёртки, и должен парситься напрямую.
 */
class UpdateDataReserveResponseTest {

    @Test
    fun checkTvJson_parsesWithoutApiEnvelope() {
        val root = Fixtures.parse<UpdateDataRootResponse>("reserve/check_tv.json")

        assertEquals("8", root.update.code)
        assertEquals("1.3.2", root.update.name)
        assertTrue(root.update.links.isNotEmpty())
        assertTrue(root.update.links.first().url.orEmpty().endsWith(".apk"))
    }
}
