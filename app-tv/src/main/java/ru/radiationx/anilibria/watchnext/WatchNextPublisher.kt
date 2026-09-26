package ru.radiationx.anilibria.watchnext

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.radiationx.anilibria.screen.launcher.MainActivity
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * Публикует прогресс просмотра в системный ряд Android TV «Продолжить просмотр» (Watch Next).
 * Одна запись на релиз, ключ — internalProviderId = releaseId.
 * Никогда не бросает исключения (кроме отмены корутины), на API < 26 ничего не делает.
 */
class WatchNextPublisher @Inject constructor(
    private val context: Context,
) {

    companion object {
        const val ACTION_WATCH_NEXT = "ru.radiationx.anilibria.action.WATCH_NEXT"

        private const val URI_SCHEME = "anilibria-tv"
        private const val URI_HOST = "watchnext"

        private const val MIN_POSITION_MS = 10_000L
        private const val NEAR_END_RATIO = 0.02
        private const val THROTTLE_POSITION_MS = 30_000L
        private const val THROTTLE_TIME_MS = 30_000L

        private const val TAG = "WatchNext"

        fun buildUri(episodeId: EpisodeId): Uri = Uri.Builder()
            .scheme(URI_SCHEME)
            .authority(URI_HOST)
            .appendPath(episodeId.releaseId.id.toString())
            .appendPath(episodeId.id)
            .build()

        fun parseIntent(intent: Intent?): EpisodeId? {
            if (intent?.action != ACTION_WATCH_NEXT) return null
            return parseUri(intent.data)
        }

        private fun parseUri(uri: Uri?): EpisodeId? {
            uri ?: return null
            if (uri.scheme != URI_SCHEME || uri.host != URI_HOST) return null
            val segments = uri.pathSegments
            if (segments.size < 2) return null
            val releaseId = segments[0].toIntOrNull() ?: return null
            val ordinal = segments[1].takeIf { it.isNotBlank() } ?: return null
            return EpisodeId(ordinal, ReleaseId(releaseId))
        }
    }

    private data class LastWrite(
        val episodeId: EpisodeId,
        val type: Int,
        val positionMs: Long,
        val at: Long,
    )

    private val mutex = Mutex()
    private val lastWrites = mutableMapOf<ReleaseId, LastWrite>()

    private val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    suspend fun onEpisodeProgress(
        release: Release,
        episode: Episode,
        positionMs: Long,
        durationMs: Long,
        isViewed: Boolean,
    ) {
        if (!isSupported) return
        coRunCatching {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    publishLocked(release, episode, positionMs, durationMs, isViewed)
                }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "onEpisodeProgress failed release=${release.id.id}")
        }
    }

    suspend fun remove(releaseId: ReleaseId) {
        if (!isSupported) return
        coRunCatching {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    lastWrites.remove(releaseId)
                    findProgram(releaseId)?.let { deleteProgram(it.id) }
                }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "remove failed release=${releaseId.id}")
        }
    }

    suspend fun clearAll() {
        if (!isSupported) return
        coRunCatching {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    lastWrites.clear()
                    queryPrograms().forEach { deleteProgram(it.id) }
                }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "clearAll failed")
        }
    }

    private fun publishLocked(
        release: Release,
        episode: Episode,
        positionMs: Long,
        durationMs: Long,
        isViewed: Boolean,
    ) {
        val releaseId = release.id
        val nearEnd = durationMs > 0L && (durationMs - positionMs) <= (durationMs * NEAR_END_RATIO).toLong()
        val finished = isViewed || nearEnd

        val targetEpisode: Episode
        val type: Int
        val targetPosition: Long
        if (finished) {
            val episodes = release.episodes
            val index = episodes.indexOfFirst { it.id == episode.id }
            val next = if (index >= 0) episodes.getOrNull(index + 1) else null
            if (next == null) {
                Timber.tag(TAG).d("release=${releaseId.id} finished, no next episode -> remove")
                lastWrites.remove(releaseId)
                findProgram(releaseId)?.let { deleteProgram(it.id) }
                return
            }
            targetEpisode = next
            type = TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            targetPosition = 0L
        } else {
            if (positionMs < MIN_POSITION_MS || durationMs <= 0L) {
                return
            }
            targetEpisode = episode
            type = TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            targetPosition = positionMs
        }

        val now = System.currentTimeMillis()
        val last = lastWrites[releaseId]
        if (last != null &&
            last.episodeId == targetEpisode.id &&
            last.type == type &&
            kotlin.math.abs(last.positionMs - targetPosition) < THROTTLE_POSITION_MS &&
            now - last.at < THROTTLE_TIME_MS
        ) {
            return
        }

        val existing = findProgram(releaseId)
        if (existing != null && !existing.isBrowsable) {
            val existingEpisode = runCatching { parseUri(existing.intent.data) }.getOrNull()
            if (existingEpisode == targetEpisode.id) {
                // Пользователь убрал запись из ряда — не возвращаем её для той же серии.
                lastWrites[releaseId] = LastWrite(targetEpisode.id, type, targetPosition, now)
                return
            }
        }

        val program = buildProgram(release, targetEpisode, type, targetPosition, durationMs, now)
        when {
            existing == null -> insertProgram(program)
            !existing.isBrowsable -> {
                // Скрытую пользователем запись обновлять бесполезно (browsable ставит только система),
                // поэтому для новой серии пересоздаём её.
                deleteProgram(existing.id)
                insertProgram(program)
            }

            else -> updateProgram(existing.id, program)
        }
        lastWrites[releaseId] = LastWrite(targetEpisode.id, type, targetPosition, now)
        Timber.tag(TAG).d(
            "published release=${releaseId.id} episode=${targetEpisode.id.id} type=$type position=$targetPosition duration=$durationMs"
        )
    }

    @SuppressLint("RestrictedApi")
    private fun buildProgram(
        release: Release,
        episode: Episode,
        type: Int,
        positionMs: Long,
        durationMs: Long,
        now: Long,
    ): WatchNextProgram {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_WATCH_NEXT
            data = buildUri(episode.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(type)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setLastEngagementTimeUtcMillis(now)
            .setInternalProviderId(release.id.id.toString())
            .setIntent(intent)
        builder.setTitle(release.title ?: release.titleEng.orEmpty())
        episode.title?.takeIf { it.isNotBlank() }?.let { builder.setEpisodeTitle(it) }
        val ordinal = episode.id.id
        val numeric = ordinal.toFloatOrNull()?.toInt()
        if (numeric != null) {
            builder.setEpisodeNumber(ordinal, numeric)
        }
        release.poster?.takeIf { it.isNotBlank() }?.let {
            builder.setPosterArtUri(Uri.parse(it))
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
        if (durationMs > 0L) {
            builder.setDurationMillis(durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        }
        if (type == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE) {
            builder.setLastPlaybackPositionMillis(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        }
        return builder.build()
    }

    private fun queryPrograms(): List<WatchNextProgram> {
        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            null,
            null,
            null,
        ) ?: return emptyList()
        return cursor.use {
            val result = mutableListOf<WatchNextProgram>()
            while (it.moveToNext()) {
                result += WatchNextProgram.fromCursor(it)
            }
            result
        }
    }

    private fun findProgram(releaseId: ReleaseId): WatchNextProgram? {
        val key = releaseId.id.toString()
        return queryPrograms().firstOrNull { it.internalProviderId == key }
    }

    private fun insertProgram(program: WatchNextProgram) {
        context.contentResolver.insert(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            program.toContentValues(),
        )
    }

    private fun updateProgram(programId: Long, program: WatchNextProgram) {
        context.contentResolver.update(
            TvContractCompat.buildWatchNextProgramUri(programId),
            program.toContentValues(),
            null,
            null,
        )
    }

    private fun deleteProgram(programId: Long) {
        context.contentResolver.delete(
            TvContractCompat.buildWatchNextProgramUri(programId),
            null,
            null,
        )
    }
}
