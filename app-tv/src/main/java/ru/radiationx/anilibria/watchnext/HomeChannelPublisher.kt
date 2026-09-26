package ru.radiationx.anilibria.watchnext

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.ContinueWatchingLoader
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.contentprovider.suggestions.SuggestionsContentProvider
import ru.radiationx.anilibria.screen.launcher.MainActivity
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.watch.ContinueWatchingItem
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Канал по умолчанию на главном экране Android TV — «Продолжить просмотр AniLibria».
 * Строится из серверной истории просмотра теми же правилами, что и ряд «Продолжить просмотр»
 * на главной ([ContinueWatchingLoader]). Без авторизации канал остаётся пустым.
 * Никогда не бросает исключения (кроме отмены корутины), на API < 26 ничего не делает.
 */
class HomeChannelPublisher @Inject constructor(
    private val context: Context,
    private val loader: ContinueWatchingLoader,
    private val authRepository: AuthRepository,
) {

    private companion object {
        const val CHANNEL_KEY = "continue_watching"
        const val SYNC_DEBOUNCE_MS = 2_000L
        const val LOGO_SIZE_PX = 256
        const val TAG = "HomeChannel"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val helper by lazy { PreviewChannelHelper(context) }

    private var observeJob: Job? = null
    private var lastSignature: String? = null

    private val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** Держит канал в актуальном состоянии, пока жив процесс. Повторные вызовы игнорируются. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!isSupported || observeJob?.isActive == true) return
        observeJob = scope.launch {
            combine(
                authRepository.observeAuthState(),
                loader.observeItems(),
                loader.observeCardUpdates().onStart { emit(Unit) },
            ) { _, _, _ -> }
                .debounce(SYNC_DEBOUNCE_MS)
                .collect { sync() }
        }
    }

    /** Однократная синхронизация (например, по системному INITIALIZE_PROGRAMS). */
    suspend fun sync() {
        if (!isSupported) return
        coRunCatching {
            withContext(Dispatchers.IO) {
                mutex.withLock { syncLocked() }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "sync failed")
        }
    }

    private suspend fun syncLocked() {
        val channelId = ensureChannel()
        val entries = if (authRepository.getAuthState() == AuthState.AUTH) {
            var items: List<ContinueWatchingItem> = emptyList()
            val cards = loader.loadCards(onItems = { items = it })
            val byRelease = items.associateBy { it.releaseId }
            cards.mapNotNull { card ->
                val releaseId = (card.type as? LibriaCard.Type.Release)?.releaseId ?: return@mapNotNull null
                Entry(releaseId, card, byRelease[releaseId])
            }
        } else {
            emptyList()
        }

        val signature = entries.joinToString("|") {
            "${it.releaseId.id}:${it.card.title}:${it.card.image}:${it.card.description}:${it.item?.ordinal}:${it.item?.time}"
        }
        if (signature == lastSignature) return

        val existing = queryPrograms(channelId).associateBy { it.internalProviderId }
        val keep = mutableSetOf<String>()
        entries.forEachIndexed { index, entry ->
            val key = entry.releaseId.id.toString()
            keep += key
            val program = buildProgram(channelId, entry, weight = entries.size - index)
            val old = existing[key]
            if (old == null) {
                helper.publishPreviewProgram(program)
            } else {
                helper.updatePreviewProgram(old.id, program)
            }
        }
        val stale = existing.values.filter { it.internalProviderId !in keep }
        stale.forEach { helper.deletePreviewProgram(it.id) }

        lastSignature = signature
        Timber.tag(TAG).d("channel=$channelId programs=${entries.size} removed=${stale.size}")
    }

    private fun ensureChannel(): Long {
        val found = helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL_KEY }
        if (found != null) {
            return found.id
        }
        val appIntent = Intent(context, MainActivity::class.java)
        val channel = PreviewChannel.Builder()
            .setDisplayName(context.getString(R.string.home_channel_continue))
            .setInternalProviderId(CHANNEL_KEY)
            .setAppLinkIntentUri(Uri.parse(appIntent.toUri(Intent.URI_INTENT_SCHEME)))
            .setLogo(
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
                    .toBitmap(LOGO_SIZE_PX, LOGO_SIZE_PX)
            )
            .build()
        // Первый канал приложения — канал по умолчанию: система показывает его без запроса пользователю.
        val id = helper.publishDefaultChannel(channel)
        lastSignature = null
        Timber.tag(TAG).d("default channel created id=$id")
        return id
    }

    @SuppressLint("RestrictedApi")
    private fun buildProgram(channelId: Long, entry: Entry, weight: Int): PreviewProgram {
        val item = entry.item
        val ordinal = item?.ordinal
        val intent = if (ordinal != null) {
            val episodeId = EpisodeId(BigDecimal(ordinal.toDouble()).toString(), entry.releaseId)
            Intent(context, MainActivity::class.java).apply {
                action = WatchNextPublisher.ACTION_WATCH_NEXT
                data = WatchNextPublisher.buildUri(episodeId)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = SuggestionsContentProvider.INTENT_ACTION
                data = Uri.parse("anilibria-tv://release/${entry.releaseId.id}")
            }
        }
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
            .setInternalProviderId(entry.releaseId.id.toString())
            .setTitle(entry.card.title)
            .setDescription(entry.card.description)
            .setIntentUri(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
            .setWeight(weight)
        entry.card.image.takeIf { it.isNotBlank() }?.let {
            builder.setPosterArtUri(Uri.parse(it))
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
        val positionMs = item?.time?.takeIf { it > 0f }?.let { (it * 1000).toLong() }
        if (positionMs != null) {
            builder.setLastPlaybackPositionMillis(positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return builder.build()
    }

    private fun queryPrograms(channelId: Long): List<PreviewProgram> {
        val cursor = context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            PreviewProgram.PROJECTION,
            null,
            null,
            null,
        ) ?: return emptyList()
        return cursor.use {
            val result = mutableListOf<PreviewProgram>()
            while (it.moveToNext()) {
                result += PreviewProgram.fromCursor(it)
            }
            result
        }
    }

    private data class Entry(
        val releaseId: ReleaseId,
        val card: LibriaCard,
        val item: ContinueWatchingItem?,
    )
}
