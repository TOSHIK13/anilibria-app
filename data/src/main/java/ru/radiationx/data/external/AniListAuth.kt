package ru.radiationx.data.external

import org.json.JSONObject
import ru.radiationx.data.BuildConfig
import ru.radiationx.data.tracker.TrackerAccount
import ru.radiationx.data.tracker.TrackerState
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

data class AniListViewer(val id: Long, val name: String, val avatarUrl: String?)

sealed class AniListValidation {
    /** [token] — уже очищенный от пробелов/URL токен; [expiresAtSec] — `exp` из JWT. */
    class Ok(val token: String, val viewer: AniListViewer, val expiresAtSec: Long) : AniListValidation() {
        override fun toString() = "Ok(viewer=$viewer, expiresAtSec=$expiresAtSec)"
    }

    /** Токен не подошёл (не JWT, срок вышел, 400/401). */
    data class Invalid(val reason: String) : AniListValidation()

    /** Не удалось проверить: нет сети, лимит запросов и т. п. */
    data class NetworkError(val message: String) : AniListValidation()
}

/** Чистые функции разбора вставленного токена и расчёта состояния (тестируются без Android). */
object AniListTokens {

    const val EXPIRING_DAYS = 14
    private const val DAY_SEC = 86_400L
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val ACCESS_TOKEN = Regex("access_token=([^&\\s#\"']+)")
    private val EXP = Regex("\"exp\"\\s*:\\s*(\\d+)")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

    /** Вставили токен, URL или фрагмент `#access_token=...&token_type=Bearer...` — вернуть только токен. */
    fun extract(raw: String): String {
        ACCESS_TOKEN.find(raw)?.let { return it.groupValues[1] }
        return raw.filterNot { it.isWhitespace() || it == '"' || it == '\'' }
    }

    /** `exp` (секунды) из payload JWT; null — не JWT или нет `exp`. */
    fun jwtExpiresAtSec(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() || !BASE64URL.matches(it) }) return null
        val payload = decodeBase64Url(parts[1]) ?: return null
        return EXP.find(payload)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun decodeBase64Url(input: String): String? {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input) {
            val v = B64.indexOf(c)
            if (v < 0) return null
            buffer = ((buffer shl 6) or v) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Состояние сервиса по сохранённому токену на момент [nowMs]. */
    fun state(token: ExternalToken?, nowMs: Long): TrackerState {
        token ?: return TrackerState.NotLinked
        val account = TrackerAccount(token.name, token.avatarUrl)
        val exp = token.expiresAtSec
        val nowSec = nowMs / 1000
        if (token.revoked || (exp > 0 && exp <= nowSec)) return TrackerState.Expired(account)
        if (exp <= 0) return TrackerState.Linked(account, null)
        val daysLeft = ((exp - nowSec + DAY_SEC - 1) / DAY_SEC).toInt()
        return if (daysLeft <= EXPIRING_DAYS) {
            TrackerState.Expiring(account, daysLeft, exp * 1000)
        } else {
            TrackerState.Linked(account, exp * 1000)
        }
    }
}

/**
 * Вход в AniList по токену, вставленному пользователем (implicit grant, без сервера):
 * ТВ показывает [authorizeUrl], пользователь входит на телефоне, копирует токен со страницы
 * pin и вводит на ТВ. Токен живёт год, обновления нет. client secret не используется.
 * Токен никогда не пишется в лог.
 */
class AniListAuth @Inject constructor(
    private val service: AniListService,
    private val store: ExternalTokenStore,
    private val outbox: ExternalOutbox,
    private val journal: ExternalSyncJournal,
    private val syncState: ExternalSyncState,
    private val settings: ExternalServiceSettings,
) {

    val authorizeUrl: String =
        "https://anilist.co/api/v2/oauth/authorize?client_id=${BuildConfig.ANILIST_CLIENT_ID}&response_type=token"

    suspend fun validate(rawToken: String): AniListValidation {
        val token = AniListTokens.extract(rawToken)
        if (token.isEmpty()) return AniListValidation.Invalid("поле пустое")
        val exp = AniListTokens.jwtExpiresAtSec(token)
            ?: return AniListValidation.Invalid("это не похоже на токен AniList")
        if (exp * 1000 <= System.currentTimeMillis()) return AniListValidation.Invalid("срок токена истёк")
        val response = try {
            service.graphQl.query(VIEWER_QUERY, JSONObject(), token = token)
        } catch (e: ExternalHttpException) {
            return if (e.code == 400 || e.code == 401 || e.code == 403) {
                AniListValidation.Invalid("AniList отклонил токен (${e.code})")
            } else {
                AniListValidation.NetworkError("ответ AniList ${e.code}")
            }
        } catch (e: ExternalRateLimitException) {
            return AniListValidation.NetworkError("слишком много запросов, попробуйте позже")
        } catch (e: IOException) {
            return AniListValidation.NetworkError(e.message ?: "нет связи с AniList")
        }
        val viewer = response?.optJSONObject("data")?.optJSONObject("Viewer")
            ?: return AniListValidation.Invalid("AniList не вернул аккаунт")
        val name = viewer.optString("name")
        if (name.isEmpty()) return AniListValidation.Invalid("AniList не вернул аккаунт")
        val avatar = viewer.optJSONObject("avatar")?.optString("medium")?.takeIf { it.isNotEmpty() }
        return AniListValidation.Ok(token, AniListViewer(viewer.optLong("id"), name, avatar), exp)
    }

    /** Проверяет и сохраняет токен. */
    suspend fun link(rawToken: String): AniListValidation {
        val result = validate(rawToken)
        if (result is AniListValidation.Ok) save(result)
        return result
    }

    fun save(result: AniListValidation.Ok) {
        store.save(
            AniListService.ID,
            ExternalToken(
                token = result.token,
                expiresAtSec = result.expiresAtSec,
                viewerId = result.viewer.id,
                name = result.viewer.name,
                avatarUrl = result.viewer.avatarUrl,
                linkedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Стирает токен и очищает очередь неотправленных изменений (с записью в журнал). */
    fun unlink() {
        store.clear(AniListService.ID)
        // базы слияния принадлежат аккаунту: при новом входе список сравнивается заново (первая синхронизация)
        syncState.clearService(AniListService.ID)
        settings.setFirstSyncDone(AniListService.ID, false)
        val dropped = outbox.clear(AniListService.ID)
        if (dropped.isNotEmpty()) {
            syncState.clearAllPending(AniListService.ID)
            journal.record(
                AniListService.ID, JournalDirection.OUT, null, null, "AniList",
                "аккаунт отключён · очередь очищена", JournalResult.SKIPPED,
                detail = "не отправлено изменений: ${dropped.size}",
            )
        }
    }

    private companion object {
        const val VIEWER_QUERY = "query { Viewer { id name avatar { medium } } }"
    }
}
