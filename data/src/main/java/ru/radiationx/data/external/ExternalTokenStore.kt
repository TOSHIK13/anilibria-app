package ru.radiationx.data.external

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.radiationx.data.DataPreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Сохранённый вход во внешний сервис. [expiresAtSec] — `exp` из JWT (epoch, секунды), 0 — неизвестен. */
data class ExternalToken(
    val token: String,
    val expiresAtSec: Long,
    val viewerId: Long,
    val name: String,
    val avatarUrl: String?,
    val linkedAt: Long,
    /** Сервис ответил 401: токен отозван или недействителен, хотя срок ещё не вышел. */
    val revoked: Boolean = false,
) {
    // токен не должен попадать в логи даже через toString()
    override fun toString(): String =
        "ExternalToken(name=$name, viewerId=$viewerId, expiresAtSec=$expiresAtSec, revoked=$revoked)"
}

/**
 * Хранилище токенов внешних сервисов по id сервиса (`"anilist"`). Так же, как токен сессии
 * AniLiberty ([ru.radiationx.data.datasource.storage.AuthStorage]) — в приватных SharedPreferences.
 */
class ExternalTokenStore @Inject constructor(
    @DataPreferences private val prefs: SharedPreferences,
) {

    private companion object {
        fun key(id: String, field: String) = "external_${id}_$field"
    }

    private val states = ConcurrentHashMap<String, MutableStateFlow<ExternalToken?>>()

    private fun state(id: String): MutableStateFlow<ExternalToken?> =
        states.getOrPut(id) { MutableStateFlow(load(id)) }

    fun observe(id: String): Flow<ExternalToken?> = state(id).asStateFlow()

    fun get(id: String): ExternalToken? = state(id).value

    /** Токен для запросов: null, если не привязан, отозван или срок вышел. */
    fun activeToken(id: String, nowMs: Long = System.currentTimeMillis()): String? {
        val current = get(id) ?: return null
        if (current.revoked) return null
        if (current.expiresAtSec > 0 && current.expiresAtSec * 1000 <= nowMs) return null
        return current.token
    }

    fun save(id: String, value: ExternalToken) {
        prefs.edit(commit = true) {
            putString(key(id, "token"), value.token)
            putLong(key(id, "exp"), value.expiresAtSec)
            putLong(key(id, "viewer_id"), value.viewerId)
            putString(key(id, "name"), value.name)
            putString(key(id, "avatar"), value.avatarUrl)
            putLong(key(id, "linked_at"), value.linkedAt)
            putBoolean(key(id, "revoked"), value.revoked)
        }
        state(id).value = value
    }

    fun clear(id: String) {
        prefs.edit(commit = true) {
            listOf("token", "exp", "viewer_id", "name", "avatar", "linked_at", "revoked", "expired_notice")
                .forEach { remove(key(id, it)) }
        }
        state(id).value = null
    }

    /** Пометить токен отозванным (401). */
    fun markRevoked(id: String) {
        val current = get(id) ?: return
        if (current.revoked) return
        save(id, current.copy(revoked = true))
    }

    /** Для какого входа (по [ExternalToken.linkedAt]) уже показано уведомление «вход истёк». */
    fun expiredNoticeShownFor(id: String): Long = prefs.getLong(key(id, "expired_notice"), 0)

    fun markExpiredNoticeShown(id: String, linkedAt: Long) {
        prefs.edit { putLong(key(id, "expired_notice"), linkedAt) }
    }

    private fun load(id: String): ExternalToken? {
        val token = prefs.getString(key(id, "token"), null)?.takeIf { it.isNotEmpty() } ?: return null
        return ExternalToken(
            token = token,
            expiresAtSec = prefs.getLong(key(id, "exp"), 0),
            viewerId = prefs.getLong(key(id, "viewer_id"), 0),
            name = prefs.getString(key(id, "name"), null).orEmpty(),
            avatarUrl = prefs.getString(key(id, "avatar"), null),
            linkedAt = prefs.getLong(key(id, "linked_at"), 0),
            revoked = prefs.getBoolean(key(id, "revoked"), false),
        )
    }
}
