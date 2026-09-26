package ru.radiationx.anilibria.screen.player

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.radiationx.data.player.PlayerCacheDataSourceProvider

data class HlsPlaylistCacheDescriptor(
    val playlistUrl: String,
    val segments: List<HlsPlaylistSegment>,
)

data class HlsPlaylistSegment(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val expectedLengthBytes: Long?,
    val byteRangeOffset: Long = 0L,
)

data class HlsCacheInspection(
    val cachedPositionMs: Long = 0L,
    val currentBytes: Long = 0L,
    val nextBytes: Long = 0L,
    val otherBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val currentSegmentsCached: Int = 0,
    val currentSegmentsTotal: Int = 0,
    val nextSegmentsCached: Int = 0,
    val nextSegmentsTotal: Int = 0,
)

const val PLAYER_NET_TAG = "PlayerNet"

@SuppressLint("UnsafeOptInUsageError")
object HlsPlaylistCacheInspector {

    /**
     * Плейлист грузится через тот же DataSource, что и у плеера (OkHttp/SSL/UA и дисковый кэш),
     * поэтому плеер потом получает его из кэша.
     */
    suspend fun loadDescriptor(
        url: String,
        dataSourceFactory: DataSource.Factory,
    ): HlsPlaylistCacheDescriptor? = withContext(Dispatchers.IO) {
        try {
            loadDescriptorInternal(url, dataSourceFactory)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            Log.w(PLAYER_NET_TAG, "playlist load failed url=$url", ex)
            null
        }
    }

    private fun loadDescriptorInternal(
        url: String,
        dataSourceFactory: DataSource.Factory,
    ): HlsPlaylistCacheDescriptor? {
        var resolvedPlaylistUrl = url
        var playlist = loadPlaylist(url, dataSourceFactory)
        if (playlist is HlsMultivariantPlaylist) {
            val mediaUrl = playlist.mediaPlaylistUrls.firstOrNull() ?: return null
            resolvedPlaylistUrl = UriUtil.resolveToUri(url, mediaUrl.toString()).toString()
            playlist = loadPlaylist(resolvedPlaylistUrl, dataSourceFactory)
        }
        val mediaPlaylist = playlist as? HlsMediaPlaylist ?: return null
        val segments = mediaPlaylist.segments.map { segment ->
            val absoluteUri = UriUtil.resolveToUri(resolvedPlaylistUrl, segment.url)
            HlsPlaylistSegment(
                key = absoluteUri.toString(),
                startMs = segment.relativeStartTimeUs / 1_000L,
                endMs = (segment.relativeStartTimeUs + segment.durationUs) / 1_000L,
                expectedLengthBytes = segment.byteRangeLength.takeIf { it > 0L },
                byteRangeOffset = segment.byteRangeOffset.coerceAtLeast(0L),
            )
        }
        return HlsPlaylistCacheDescriptor(
            playlistUrl = resolvedPlaylistUrl,
            segments = segments,
        )
    }

    private fun loadPlaylist(url: String, dataSourceFactory: DataSource.Factory): HlsPlaylist {
        val uri = Uri.parse(url)
        DataSourceInputStream(dataSourceFactory.createDataSource(), DataSpec(uri)).use { input ->
            return HlsPlaylistParser().parse(uri, input)
        }
    }
}

/**
 * Инкрементальная проверка кэша: сегменты, уже найденные полностью закэшированными,
 * повторно не опрашиваются (кроме периодического полного пересчёта на случай вытеснения из LRU).
 * Вызывать не с main-потока.
 */
class HlsCacheInspectionTracker {

    private var current = DescriptorState(null)
    private var next = DescriptorState(null)
    private var lastFullRescanAt = 0L

    @Synchronized
    fun reset() {
        current = DescriptorState(null)
        next = DescriptorState(null)
        lastFullRescanAt = 0L
    }

    @Synchronized
    fun inspect(
        cacheProvider: PlayerCacheDataSourceProvider,
        currentDescriptor: HlsPlaylistCacheDescriptor?,
        nextDescriptor: HlsPlaylistCacheDescriptor?,
        positionMs: Long,
    ): HlsCacheInspection {
        val now = SystemClock.elapsedRealtime()
        val fullRescan = now - lastFullRescanAt >= FULL_RESCAN_INTERVAL_MS
        if (fullRescan) {
            lastFullRescanAt = now
        }
        if (current.descriptor !== currentDescriptor || fullRescan) {
            current = DescriptorState(currentDescriptor)
        }
        if (next.descriptor !== nextDescriptor || fullRescan) {
            next = DescriptorState(nextDescriptor)
        }
        current.refresh(cacheProvider)
        next.refresh(cacheProvider)

        val totalBytes = cacheProvider.getCacheSpaceBytes()
        val currentBytes = current.bytes.sum()
        val nextBytes = next.bytes.sum()
        return HlsCacheInspection(
            cachedPositionMs = current.cachedPositionMs(positionMs),
            currentBytes = currentBytes,
            nextBytes = nextBytes,
            otherBytes = (totalBytes - currentBytes - nextBytes).coerceAtLeast(0L),
            totalBytes = totalBytes,
            currentSegmentsCached = current.cached.count { it },
            currentSegmentsTotal = current.cached.size,
            nextSegmentsCached = next.cached.count { it },
            nextSegmentsTotal = next.cached.size,
        )
    }

    private class DescriptorState(val descriptor: HlsPlaylistCacheDescriptor?) {
        private val segments = descriptor?.segments.orEmpty()
        val cached = BooleanArray(segments.size)
        val bytes = LongArray(segments.size)

        fun refresh(cacheProvider: PlayerCacheDataSourceProvider) {
            segments.forEachIndexed { index, segment ->
                if (cached[index]) {
                    return@forEachIndexed
                }
                cached[index] = cacheProvider.isSegmentFullyCached(
                    key = segment.key,
                    position = segment.byteRangeOffset,
                    expectedLengthBytes = segment.expectedLengthBytes,
                )
                bytes[index] = cacheProvider.getCachedBytesForKey(segment.key)
            }
        }

        fun cachedPositionMs(positionMs: Long): Long {
            val startIndex = segments.indexOfFirst { positionMs < it.endMs }
            if (startIndex < 0) {
                return 0L
            }
            var cachedPositionMs = positionMs.coerceAtLeast(0L)
            for (index in startIndex until segments.size) {
                if (!cached[index]) {
                    break
                }
                cachedPositionMs = maxOf(cachedPositionMs, segments[index].endMs)
            }
            return cachedPositionMs
        }
    }

    private companion object {
        private const val FULL_RESCAN_INTERVAL_MS = 60_000L
    }
}
