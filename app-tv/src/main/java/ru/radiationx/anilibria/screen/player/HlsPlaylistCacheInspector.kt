package ru.radiationx.anilibria.screen.player

import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.radiationx.data.player.PlayerCacheDataSourceProvider
import java.net.URL

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
    val currentSegmentsCached: Int = 0,
    val currentSegmentsTotal: Int = 0,
    val nextSegmentsCached: Int = 0,
    val nextSegmentsTotal: Int = 0,
)

object HlsPlaylistCacheInspector {

    suspend fun loadDescriptor(url: String): HlsPlaylistCacheDescriptor? = withContext(Dispatchers.IO) {
        runCatching {
            loadDescriptorInternal(url)
        }.getOrNull()
    }

    fun inspect(
        cacheProvider: PlayerCacheDataSourceProvider,
        current: HlsPlaylistCacheDescriptor?,
        next: HlsPlaylistCacheDescriptor?,
        positionMs: Long,
    ): HlsCacheInspection {
        val currentSegments = current?.segments.orEmpty()
        val nextSegments = next?.segments.orEmpty()

        val currentBytes = currentSegments.sumOf { segment ->
            cacheProvider.getCachedBytesForKey(segment.key)
        }
        val nextBytes = nextSegments.sumOf { segment ->
            cacheProvider.getCachedBytesForKey(segment.key)
        }
        val totalBytes = cacheProvider.getCacheSpaceBytes()
        val otherBytes = (totalBytes - currentBytes - nextBytes).coerceAtLeast(0L)

        val currentCachedSegments = currentSegments.count { segment ->
            isSegmentCached(cacheProvider, segment)
        }
        val nextCachedSegments = nextSegments.count { segment ->
            isSegmentCached(cacheProvider, segment)
        }

        return HlsCacheInspection(
            cachedPositionMs = calculateCachedPositionMs(
                cacheProvider = cacheProvider,
                descriptor = current,
                positionMs = positionMs,
            ),
            currentBytes = currentBytes,
            nextBytes = nextBytes,
            otherBytes = otherBytes,
            currentSegmentsCached = currentCachedSegments,
            currentSegmentsTotal = currentSegments.size,
            nextSegmentsCached = nextCachedSegments,
            nextSegmentsTotal = nextSegments.size,
        )
    }

    private fun calculateCachedPositionMs(
        cacheProvider: PlayerCacheDataSourceProvider,
        descriptor: HlsPlaylistCacheDescriptor?,
        positionMs: Long,
    ): Long {
        val segments = descriptor?.segments.orEmpty()
        if (segments.isEmpty()) {
            return 0L
        }
        val startIndex = segments.indexOfFirst { segment ->
            positionMs < segment.endMs
        }.takeIf { it >= 0 } ?: return 0L
        var cachedPositionMs = positionMs.coerceAtLeast(0L)
        for (index in startIndex until segments.size) {
            val segment = segments[index]
            if (!isSegmentCached(cacheProvider, segment)) {
                break
            }
            cachedPositionMs = maxOf(cachedPositionMs, segment.endMs)
        }
        return cachedPositionMs
    }

    private fun isSegmentCached(
        cacheProvider: PlayerCacheDataSourceProvider,
        segment: HlsPlaylistSegment,
    ): Boolean {
        return cacheProvider.isSegmentFullyCached(
            key = segment.key,
            position = segment.byteRangeOffset,
            expectedLengthBytes = segment.expectedLengthBytes,
        )
    }

    private fun loadDescriptorInternal(url: String): HlsPlaylistCacheDescriptor? {
        val resolvedPlaylistUrl = resolveMediaPlaylistUrl(url)
        val mediaPlaylist = loadPlaylist(resolvedPlaylistUrl) as? HlsMediaPlaylist ?: return null
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

    private fun resolveMediaPlaylistUrl(url: String): String {
        val playlist = loadPlaylist(url)
        return when (playlist) {
            is HlsMediaPlaylist -> url
            is HlsMultivariantPlaylist -> {
                val mediaUrl = playlist.mediaPlaylistUrls.firstOrNull() ?: return url
                UriUtil.resolveToUri(url, mediaUrl.toString()).toString()
            }
            else -> url
        }
    }

    private fun loadPlaylist(url: String): HlsPlaylist {
        val parser = HlsPlaylistParser()
        val uri = Uri.parse(url)
        URL(url).openStream().use { input ->
            return parser.parse(uri, input)
        }
    }
}
