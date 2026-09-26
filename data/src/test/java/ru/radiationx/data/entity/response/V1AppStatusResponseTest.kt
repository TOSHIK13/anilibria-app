package ru.radiationx.data.entity.response

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.radiationx.data.entity.mapper.Fixtures
import ru.radiationx.data.entity.response.app.V1AppStatusResponse

class V1AppStatusResponseTest {

    @Test
    fun appStatus_isAlive() {
        val status = Fixtures.parse<V1AppStatusResponse>("v1/app_status.json")

        assertEquals(true, status.isAlive)
    }

    @Test
    fun appStatus_withoutFlag_isNull() {
        val status = Fixtures.moshi.adapter(V1AppStatusResponse::class.java).fromJson("{}")

        assertEquals(null, status?.isAlive)
    }
}
