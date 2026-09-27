package ru.radiationx.data.repository.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.datasource.remote.parsers.AuthParser
import ru.radiationx.data.entity.domain.auth.OtpInfo
import ru.radiationx.data.entity.domain.auth.OtpNotAcceptedException
import ru.radiationx.data.entity.domain.auth.isValidOtpCode
import ru.radiationx.data.repository.loadValidOtpInfo
import ru.radiationx.data.system.HttpException
import java.io.IOException
import java.util.Date

class OtpCodeLogicTest {

    private fun info(code: String) = OtpInfo(code, "", Date(), 0L)

    @Test
    fun validCode_onlySixDigits() {
        assertTrue(isValidOtpCode("027055"))
        assertFalse(isValidOtpCode("27055"))
        assertFalse(isValidOtpCode("1234567"))
        assertFalse(isValidOtpCode("12a456"))
        assertFalse(isValidOtpCode(""))
    }

    @Test
    fun loadValidOtpInfo_retriesUntilSixDigits() = runBlocking {
        val codes = ArrayDeque(listOf("27055", "1234", "742936"))
        val invalid = mutableListOf<Int>()
        val result = loadValidOtpInfo(
            load = { info(codes.removeFirst()) },
            onInvalid = { attempt, _ -> invalid += attempt },
        )
        assertEquals("742936", result.code)
        assertEquals(listOf(1, 2), invalid)
    }

    @Test
    fun loadValidOtpInfo_returnsLastAfterMaxAttempts() = runBlocking {
        var loads = 0
        val result = loadValidOtpInfo(
            maxAttempts = 5,
            load = { loads++; info("1234$loads") },
            onInvalid = { _, _ -> },
        )
        assertEquals(5, loads)
        assertEquals("12345", result.code)
    }

    private fun http(code: Int): HttpException {
        val request = Request.Builder().url("https://anilibria.top/api/v1/accounts/otp/login").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Server Error")
            .build()
        return HttpException(code, "Server Error", response)
    }

    @Test
    fun otpLoginError_mapsNotLinkedStatusesToNotAccepted() {
        val parser = AuthParser()
        listOf(401, 404, 500).forEach { code ->
            val mapped = parser.checkOtpLoginError(http(code))
            assertTrue("$code", mapped is OtpNotAcceptedException)
            assertEquals(AuthParser.OTP_NOT_ACCEPTED_MESSAGE, mapped.message)
        }
    }

    @Test
    fun otpLoginError_keepsOtherErrors() {
        val parser = AuthParser()
        val http403 = http(403)
        assertSame(http403, parser.checkOtpLoginError(http403))
        val io = IOException("timeout")
        assertSame(io, parser.checkOtpLoginError(io))
    }
}
