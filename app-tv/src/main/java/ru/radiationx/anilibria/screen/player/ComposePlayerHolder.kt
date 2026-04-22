package ru.radiationx.anilibria.screen.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.player.PlayerBufferConfig
import ru.radiationx.data.player.PlayerDataSourceProvider
import java.util.UUID

class ComposePlayerHolder(
    private val dataSourceProvider: PlayerDataSourceProvider,
    private val preferencesHolder: PreferencesHolder,
) {

    private var mediaSession: MediaSession? = null

    @get:UnstableApi
    var player: ExoPlayer? = null
        private set

    @UnstableApi
    fun attach(context: Context): ExoPlayer {
        player?.let { return it }

        val dataSourceType = dataSourceProvider.get()
        val dataSourceFactory = DefaultDataSource.Factory(context, dataSourceType.factory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context).apply {
            setDataSourceFactory(dataSourceFactory)
        }
        val loadControl = PlayerBufferConfig.createLoadControl(
            preferencesHolder.playerForwardBufferSeconds.value,
            preferencesHolder.playerBackBufferSeconds.value,
        )
        val newPlayer = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        player = newPlayer
        startMediaSession(context, newPlayer)
        return newPlayer
    }

    fun detach() {
        stopMediaSession()
        player?.release()
        player = null
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
    }
}
