package ru.radiationx.data.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.tracker.TrackerState
import java.util.Base64

class AniListTokensTest {

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun jwt(exp: Long) = "${b64("{\"typ\":\"JWT\",\"alg\":\"RS256\"}")}.${b64("{\"aud\":\"52291\",\"exp\":$exp,\"sub\":\"1\"}")}.sig-nature_1"

    private fun stored(exp: Long, revoked: Boolean = false) =
        ExternalToken("t", exp, 1, "nick", null, 0, revoked)

    @Test
    fun `exp is read from jwt payload`() {
        assertEquals(1_800_000_000L, AniListTokens.jwtExpiresAtSec(jwt(1_800_000_000L)))
    }

    @Test
    fun `garbage is not a jwt`() {
        assertNull(AniListTokens.jwtExpiresAtSec("abc"))
        assertNull(AniListTokens.jwtExpiresAtSec("abc.def.ghi"))
        assertNull(AniListTokens.jwtExpiresAtSec("a b.c.d"))
    }

    @Test
    fun `extract trims whitespace and newlines`() {
        val token = jwt(1_800_000_000L)
        assertEquals(token, AniListTokens.extract("  ${token.substring(0, 20)}\n${token.substring(20)}\r\n"))
    }

    @Test
    fun `extract takes access_token from url or fragment`() {
        val token = jwt(1_800_000_000L)
        assertEquals(token, AniListTokens.extract("https://anilist.co/api/v2/oauth/pin#access_token=$token&token_type=Bearer&expires_in=31536000"))
        assertEquals(token, AniListTokens.extract("access_token=$token"))
    }

    @Test
    fun `state by expiration`() {
        val now = 1_700_000_000_000L
        val nowSec = now / 1000
        assertTrue(AniListTokens.state(null, now) is TrackerState.NotLinked)
        assertTrue(AniListTokens.state(stored(nowSec + 100 * 86_400L), now) is TrackerState.Linked)
        val expiring = AniListTokens.state(stored(nowSec + 3 * 86_400L - 10), now)
        assertEquals(3, (expiring as TrackerState.Expiring).daysLeft)
        assertTrue(AniListTokens.state(stored(nowSec + 14 * 86_400L), now) is TrackerState.Expiring)
        assertTrue(AniListTokens.state(stored(nowSec + 15 * 86_400L), now) is TrackerState.Linked)
        assertTrue(AniListTokens.state(stored(nowSec - 1), now) is TrackerState.Expired)
        assertTrue(AniListTokens.state(stored(nowSec + 100 * 86_400L, revoked = true), now) is TrackerState.Expired)
    }
}
