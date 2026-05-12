package ru.radiationx.data.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import ru.radiationx.data.datasource.holders.PreferencesHolder
import java.io.File
import javax.inject.Inject

@SuppressLint("UnsafeOptInUsageError")
class PlayerCacheDataSourceProvider @Inject constructor(
    private val context: Context,
    private val preferencesHolder: PreferencesHolder,
) {

    @Volatile
    private var cacheHolder: CacheHolder? = null

    fun createCacheFactory(upstreamFactory: DataSource.Factory): DataSource.Factory {
        return DataSource.Factory {
            if (!isEnabled()) {
                return@Factory upstreamFactory.createDataSource()
            }
            val cache = getOrCreateCache()
            val cacheSink = CacheDataSink.Factory().setCache(cache)
            val downStreamFactory = FileDataSource.Factory()
            CacheDataSource.Factory()
                .setCache(cache)
                .setCacheWriteDataSinkFactory(cacheSink)
                .setCacheReadDataSourceFactory(downStreamFactory)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        }
    }

    fun createPrefetchCacheDataSource(upstreamFactory: DataSource.Factory): CacheDataSource? {
        if (!isEnabled()) {
            return null
        }
        val cache = getOrCreateCache()
        val cacheSink = CacheDataSink.Factory().setCache(cache)
        val downStreamFactory = FileDataSource.Factory()
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(cacheSink)
            .setCacheReadDataSourceFactory(downStreamFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSourceForDownloading()
    }

    fun isEnabled(): Boolean {
        return preferencesHolder.playerDiskCacheEnabled.value
    }

    fun getCacheSpaceBytes(): Long {
        if (!isEnabled() && cacheHolder == null) {
            return 0L
        }
        return getOrCreateCache().cacheSpace
    }

    fun getConfiguredMaxBytes(): Long {
        return preferencesHolder.playerDiskCacheSizeMb.value * BYTES_IN_MB
    }

    fun getCachedBytesForKey(key: String): Long {
        if (!isEnabled() && cacheHolder == null) {
            return 0L
        }
        return getOrCreateCache()
            .getCachedSpans(key)
            .sumOf { it.length.coerceAtLeast(0L) }
    }

    fun isSegmentFullyCached(
        key: String,
        expectedLengthBytes: Long?,
        position: Long = 0L,
    ): Boolean {
        if (!isEnabled() && cacheHolder == null) {
            return false
        }
        return if (expectedLengthBytes != null && expectedLengthBytes > 0L) {
            getOrCreateCache().isCached(key, position.coerceAtLeast(0L), expectedLengthBytes)
        } else {
            getCachedBytesForKey(key) > 0L
        }
    }

    @Synchronized
    fun refresh() {
        val maxBytes = getConfiguredMaxBytes()
        val current = cacheHolder
        if (current == null || current.maxBytes == maxBytes) {
            return
        }
        current.cache.release()
        cacheHolder = createCacheHolder(maxBytes)
    }

    @Synchronized
    private fun getOrCreateCache(): SimpleCache {
        val current = cacheHolder
        if (current != null) {
            return current.cache
        }
        val newHolder = createCacheHolder(getConfiguredMaxBytes())
        cacheHolder = newHolder
        return newHolder.cache
    }

    private fun createCacheHolder(maxBytes: Long): CacheHolder {
        val directory = File(context.cacheDir, "player")
        return CacheHolder(
            maxBytes = maxBytes,
            cache = SimpleCache(
                directory,
                LeastRecentlyUsedCacheEvictor(maxBytes),
                StandaloneDatabaseProvider(context)
            )
        )
    }

    private data class CacheHolder(
        val maxBytes: Long,
        val cache: SimpleCache,
    )

    private companion object {
        private const val BYTES_IN_MB = 1024L * 1024L
    }
}
