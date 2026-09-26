package ru.radiationx.anilibria.screen.player

import android.content.Context
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import ru.radiationx.data.player.PlayerCacheDataSourceProvider
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.player.PlayerBufferConfig
import ru.radiationx.data.player.PlayerDataSourceProvider
import java.util.UUID

class ComposePlayerHolder(
    private val dataSourceProvider: PlayerDataSourceProvider,
    private val cacheDataSourceProvider: PlayerCacheDataSourceProvider,
    private val preferencesHolder: PreferencesHolder,
) {

    private var mediaSession: MediaSession? = null

    /**
     * Общий для плеера и HLS prefetch-а: пока плеер грузит данные (PRIORITY_PLAYBACK),
     * загрузки с PRIORITY_DOWNLOAD получают PriorityTooLowException.
     */
    @get:UnstableApi
    val priorityTaskManager = PriorityTaskManager()

    /** Сетевой источник плеера (без кэша). Доступен после [attach]. */
    var upstreamDataSourceFactory: DataSource.Factory? = null
        private set

    /** Источник плеера с дисковым кэшем. Доступен после [attach]. */
    var cacheDataSourceFactory: DataSource.Factory? = null
        private set

    @get:UnstableApi
    var player: ExoPlayer? = null
        private set

    /** LoadControl плеера (для диагностики allocator-а). Доступен после [attach]. */
    @get:UnstableApi
    var loadControl: DefaultLoadControl? = null
        private set

    @UnstableApi
    fun attach(context: Context): ExoPlayer {
        player?.let { return it }
        cacheDataSourceProvider.refresh()

        val dataSourceType = dataSourceProvider.get()
        val upstreamFactory = DefaultDataSource.Factory(context, dataSourceType.factory)
        val dataSourceFactory = cacheDataSourceProvider.createCacheFactory(upstreamFactory)
        upstreamDataSourceFactory = upstreamFactory
        cacheDataSourceFactory = dataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(context).apply {
            setDataSourceFactory(dataSourceFactory)
        }
        val loadControl = PlayerBufferConfig.createLoadControl(
            preferencesHolder.playerForwardBufferSeconds.value,
            preferencesHolder.playerBackBufferSeconds.value,
            preferencesHolder.playerBufferMemoryLimitMb.value,
        )
        val newPlayer = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setPriorityTaskManager(priorityTaskManager)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        applyRuntimeSettings(newPlayer)

        this.loadControl = loadControl
        player = newPlayer
        startMediaSession(context, newPlayer)
        return newPlayer
    }

    fun applyRuntimeSettings(
        player: ExoPlayer = requireNotNull(this.player),
        allowNextEpisodePreload: Boolean = preferencesHolder.playerPreloadNextEpisode.value,
    ) {
        // Следующая серия держит в RAM только короткое начало: основной запас идёт через дисковый кэш.
        val preloadDurationUs = if (allowNextEpisodePreload && preferencesHolder.playerPreloadNextEpisode.value) {
            NEXT_ITEM_PRELOAD_DURATION_US
        } else {
            0L
        }
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(preloadDurationUs))
    }

    fun detach() {
        stopMediaSession()
        player?.release()
        player = null
        loadControl = null
        upstreamDataSourceFactory = null
        cacheDataSourceFactory = null
    }

    private fun startMediaSession(context: Context, player: ExoPlayer) {
        stopMediaSession()
        mediaSession = MediaSession.Builder(context, player)
            .setId(UUID.randomUUID().toString())
            .build()
    }

    private fun stopMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    private companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val NEXT_ITEM_PRELOAD_DURATION_US = 8_000_000L
    }
}
