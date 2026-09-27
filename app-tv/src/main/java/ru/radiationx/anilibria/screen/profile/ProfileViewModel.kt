package ru.radiationx.anilibria.screen.profile

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.BuildConfig
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.AuthGuidedScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.PlayerBufferSettingsGuidedScreen
import ru.radiationx.anilibria.screen.UpdateScreen
import ru.radiationx.anilibria.screen.player.settings.PlayerBufferTarget
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.other.ProfileItem
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CheckerRepository
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.data.tracker.AnimeTracker
import ru.radiationx.data.tracker.AnimeTrackerRegistry
import ru.radiationx.data.tracker.TrackerEntry
import ru.radiationx.data.tracker.TrackerState
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferencesHolder: PreferencesHolder,
    private val guidedRouter: GuidedRouter,
    private val router: Router,
    private val watchProgressRepository: WatchProgressRepository,
    private val checkerRepository: CheckerRepository,
    private val apiConfig: ApiConfig,
    private val trackerRegistry: AnimeTrackerRegistry,
) : LifecycleViewModel() {

    /** Строки каждого раздела; фрагмент рисует выбранный. */
    val sections = MutableStateFlow<Map<SettingsSection, List<SettingsItem>>>(emptyMap())

    /** Короткое сообщение (тост). */
    val messages = MutableStateFlow<String?>(null)

    private val prefs = MutableStateFlow(PlayerPrefs())
    private val profile = MutableStateFlow<ProfileItem?>(null)
    private val linkedTrackers = MutableStateFlow<List<TrackerEntry>>(emptyList())
    private val trackersToLink = MutableStateFlow<List<AnimeTracker>>(emptyList())
    private val historyStatus = MutableStateFlow(TaskStatus.IDLE)
    private val updateStatus = MutableStateFlow(TaskStatus.IDLE)
    private val serverHost = MutableStateFlow(currentHost())

    init {
        authRepository.observeUser().onEach { profile.value = it }.launchIn(viewModelScope)
        trackerRegistry.observeLinked().onEach { linkedTrackers.value = it }.launchIn(viewModelScope)
        trackerRegistry.availableToLink.onEach { trackersToLink.value = it }.launchIn(viewModelScope)

        combine<Any, PlayerPrefs>(
            preferencesHolder.playerSkips,
            preferencesHolder.playerSkipsTimer,
            preferencesHolder.playerAutoplay,
            preferencesHolder.playerPreloadNextEpisode,
            preferencesHolder.playSpeed,
            preferencesHolder.playerQuality,
            preferencesHolder.playerForwardBufferSeconds,
            preferencesHolder.playerBackBufferSeconds,
            preferencesHolder.playerBufferMemoryLimitMb,
            preferencesHolder.playerDiskCacheEnabled,
            preferencesHolder.playerDiskCacheSizeMb,
        ) { values ->
            PlayerPrefs(
                skips = values[0] as Boolean,
                autoSkip = values[1] as Boolean,
                autoplay = values[2] as Boolean,
                preloadNext = values[3] as Boolean,
                speed = values[4] as Float,
                quality = values[5] as PlayerQuality,
                forwardBufferSeconds = values[6] as Int,
                backBufferSeconds = values[7] as Int,
                memoryLimitMb = values[8] as Int,
                diskCacheEnabled = values[9] as Boolean,
                diskCacheSizeMb = values[10] as Int,
            )
        }.onEach { prefs.value = it }.launchIn(viewModelScope)

        combine(
            prefs,
            profile,
            combine(linkedTrackers, trackersToLink) { linked, toLink -> linked to toLink },
            combine(historyStatus, updateStatus, serverHost) { history, update, host ->
                Triple(history, update, host)
            },
        ) { prefs, profile, trackers, statuses ->
            mapOf(
                SettingsSection.ACCOUNTS to accountItems(profile, trackers.first, trackers.second, statuses.first),
                SettingsSection.PLAYER to playerItems(prefs),
                SettingsSection.APP to appItems(statuses.second, statuses.third),
            )
        }.onEach { sections.value = it }.launchIn(viewModelScope)
    }

    override fun onResume() {
        super.onResume()
        // адрес мог смениться на экране проверки адресов
        serverHost.value = currentHost()
    }

    fun onMessageShown() {
        messages.value = null
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.SIGN_IN -> guidedRouter.open(AuthGuidedScreen())
            SettingsAction.SIGN_OUT -> guidedRouter.open(SettingsSignOutGuidedScreen())
            SettingsAction.CONNECT_SERVICE -> messages.value = "Подключение сервисов пока недоступно"
            SettingsAction.REFRESH_HISTORY -> refreshHistory()

            SettingsAction.SKIPS -> toggle(preferencesHolder.playerSkips.value) {
                preferencesHolder.playerSkips.value = it
            }

            SettingsAction.AUTO_SKIP -> toggle(preferencesHolder.playerSkipsTimer.value) {
                preferencesHolder.playerSkipsTimer.value = it
            }

            SettingsAction.AUTOPLAY -> toggle(preferencesHolder.playerAutoplay.value) {
                preferencesHolder.playerAutoplay.value = it
            }

            SettingsAction.PRELOAD_NEXT -> toggle(preferencesHolder.playerPreloadNextEpisode.value) {
                preferencesHolder.playerPreloadNextEpisode.value = it
            }

            SettingsAction.DISK_CACHE -> toggle(preferencesHolder.playerDiskCacheEnabled.value) {
                preferencesHolder.playerDiskCacheEnabled.value = it
            }

            SettingsAction.SPEED -> guidedRouter.open(SettingsChoiceGuidedScreen(SettingsChoice.SPEED))
            SettingsAction.QUALITY -> guidedRouter.open(SettingsChoiceGuidedScreen(SettingsChoice.QUALITY))
            SettingsAction.BUFFER_MEMORY -> guidedRouter.open(SettingsChoiceGuidedScreen(SettingsChoice.MEMORY_LIMIT))
            SettingsAction.DISK_CACHE_SIZE -> guidedRouter.open(SettingsChoiceGuidedScreen(SettingsChoice.DISK_CACHE_SIZE))
            SettingsAction.BUFFER_FORWARD -> guidedRouter.open(PlayerBufferSettingsGuidedScreen(PlayerBufferTarget.FORWARD))
            SettingsAction.BUFFER_BACK -> guidedRouter.open(PlayerBufferSettingsGuidedScreen(PlayerBufferTarget.BACK))

            SettingsAction.VERSION -> checkUpdates()
            SettingsAction.SERVER -> router.navigateTo(SettingsConfigScreen())
            SettingsAction.ABOUT -> guidedRouter.open(SettingsAboutGuidedScreen())
        }
    }

    private inline fun toggle(current: Boolean, set: (Boolean) -> Unit) = set(!current)

    private fun refreshHistory() {
        if (historyStatus.value == TaskStatus.RUNNING) return
        viewModelScope.launch {
            historyStatus.value = TaskStatus.RUNNING
            val ok = coRunCatching { watchProgressRepository.forceRefresh() }
                .onFailure { Timber.e(it) }
                .getOrDefault(false)
            historyStatus.value = if (ok) TaskStatus.DONE else TaskStatus.FAILED
            messages.value = if (ok) "История просмотров обновлена" else "Не удалось обновить историю"
        }
    }

    private fun checkUpdates() {
        if (updateStatus.value == TaskStatus.RUNNING) return
        viewModelScope.launch {
            updateStatus.value = TaskStatus.RUNNING
            coRunCatching { checkerRepository.checkUpdate(force = true) }
                .onSuccess { data ->
                    if (data.hasUpdate) {
                        updateStatus.value = TaskStatus.IDLE
                        router.navigateTo(UpdateScreen())
                    } else {
                        updateStatus.value = TaskStatus.DONE
                    }
                }
                .onFailure {
                    Timber.e(it)
                    updateStatus.value = TaskStatus.FAILED
                }
        }
    }

    private fun accountItems(
        profile: ProfileItem?,
        linked: List<TrackerEntry>,
        toLink: List<AnimeTracker>,
        history: TaskStatus,
    ): List<SettingsItem> = buildList {
        add(
            if (profile != null) {
                SettingsItem.Account(
                    key = "account_main",
                    nick = profile.nick,
                    avatarUrl = profile.avatarUrl,
                    subtitle = "Вход по коду · AniLiberty",
                    actionTitle = "Выйти",
                    action = SettingsAction.SIGN_OUT,
                )
            } else {
                SettingsItem.Account(
                    key = "account_main",
                    nick = null,
                    avatarUrl = null,
                    subtitle = "Войдите, чтобы синхронизировать историю и коллекции",
                    actionTitle = "Войти",
                    action = SettingsAction.SIGN_IN,
                )
            }
        )
        if (profile != null) {
            add(
                SettingsItem.Row(
                    action = SettingsAction.REFRESH_HISTORY,
                    title = "Обновить историю просмотров",
                    subtitle = "Заново загрузить прогресс и таймкоды с сервера",
                    value = when (history) {
                        TaskStatus.RUNNING -> "Загрузка…"
                        TaskStatus.DONE -> "Обновлено"
                        TaskStatus.FAILED -> "Ошибка, повторить"
                        TaskStatus.IDLE -> "Обновить"
                    },
                )
            )
        }
        // Блок сервисов статистики — только если есть что показать (сейчас реализаций нет).
        if (linked.isNotEmpty() || toLink.isNotEmpty()) {
            add(SettingsItem.Header(key = "trackers_header", title = "Сервисы статистики"))
        }
        linked.forEach { entry ->
            val state = entry.state
            val account = when (state) {
                is TrackerState.Linked -> state.account
                is TrackerState.Error -> state.account
                TrackerState.NotLinked -> null
            }
            add(
                SettingsItem.Account(
                    key = "tracker_${entry.tracker.id}",
                    nick = account?.nick ?: entry.tracker.title,
                    avatarUrl = account?.avatarUrl,
                    subtitle = if (state is TrackerState.Error) {
                        "${entry.tracker.title} · ${state.message}"
                    } else {
                        entry.tracker.title
                    },
                    actionTitle = "",
                    action = null,
                )
            )
        }
        if (toLink.isNotEmpty()) {
            add(
                SettingsItem.Row(
                    action = SettingsAction.CONNECT_SERVICE,
                    title = "Подключить сервис",
                    subtitle = toLink.joinToString { it.title },
                    value = "",
                )
            )
        }
    }

    private fun playerItems(prefs: PlayerPrefs): List<SettingsItem> = listOf(
        SettingsItem.Row(
            action = SettingsAction.SKIPS,
            title = "Кнопки пропуска",
            subtitle = "Показывать «Пропустить опенинг / эндинг»",
            switch = prefs.skips,
        ),
        SettingsItem.Row(
            action = SettingsAction.AUTO_SKIP,
            title = "Автопропуск",
            subtitle = "Пропускать опенинг и эндинг без нажатия",
            switch = prefs.autoSkip,
        ),
        SettingsItem.Row(
            action = SettingsAction.AUTOPLAY,
            title = "Автовоспроизведение",
            subtitle = "Следующая серия запускается сама",
            switch = prefs.autoplay,
        ),
        SettingsItem.Row(
            action = SettingsAction.PRELOAD_NEXT,
            title = "Предзагрузка следующей серии",
            subtitle = "Начинать грузить следующую серию заранее",
            switch = prefs.preloadNext,
        ),
        SettingsItem.Row(
            action = SettingsAction.SPEED,
            title = "Скорость по умолчанию",
            value = SettingsFormat.speed(prefs.speed),
        ),
        SettingsItem.Row(
            action = SettingsAction.QUALITY,
            title = "Качество по умолчанию",
            subtitle = "Если серия есть в таком качестве",
            value = SettingsFormat.quality(prefs.quality),
        ),
        SettingsItem.Row(
            action = SettingsAction.BUFFER_FORWARD,
            title = "Буфер вперёд",
            subtitle = "Сколько видео загружать наперёд",
            value = SettingsFormat.seconds(prefs.forwardBufferSeconds),
        ),
        SettingsItem.Row(
            action = SettingsAction.BUFFER_BACK,
            title = "Буфер назад",
            subtitle = "Сколько просмотренного держать для перемотки назад",
            value = SettingsFormat.seconds(prefs.backBufferSeconds),
        ),
        SettingsItem.Row(
            action = SettingsAction.BUFFER_MEMORY,
            title = "Лимит буфера в памяти",
            subtitle = "Больше — меньше подгрузок, но выше расход памяти",
            value = SettingsFormat.megabytes(prefs.memoryLimitMb),
        ),
        SettingsItem.Row(
            action = SettingsAction.DISK_CACHE,
            title = "Кэш видео на диске",
            subtitle = "Повторный просмотр и перемотка без загрузки",
            switch = prefs.diskCacheEnabled,
        ),
        SettingsItem.Row(
            action = SettingsAction.DISK_CACHE_SIZE,
            title = "Размер кэша на диске",
            subtitle = if (prefs.diskCacheEnabled) null else "Кэш выключен",
            value = SettingsFormat.megabytes(prefs.diskCacheSizeMb),
        ),
    )

    private fun appItems(update: TaskStatus, host: String): List<SettingsItem> = listOf(
        SettingsItem.Row(
            action = SettingsAction.VERSION,
            title = "Версия",
            subtitle = "${BuildConfig.VERSION_NAME} · сборка от ${BuildConfig.BUILD_DATE}",
            value = when (update) {
                TaskStatus.RUNNING -> "Проверка…"
                TaskStatus.DONE -> "Установлена последняя версия"
                TaskStatus.FAILED -> "Не удалось проверить"
                TaskStatus.IDLE -> "Проверить обновления"
            },
        ),
        SettingsItem.Row(
            action = SettingsAction.SERVER,
            title = "Адрес сервера",
            subtitle = "Какой адрес API сейчас используется",
            value = host,
        ),
        SettingsItem.Row(
            action = SettingsAction.ABOUT,
            title = "О приложении",
            subtitle = "AniLiberty TV Mod, исходный код, лицензия",
            value = "",
        ),
    )

    private fun currentHost(): String {
        val url = apiConfig.animeBaseUrl
        return Uri.parse(url).host ?: url
    }

    private enum class TaskStatus { IDLE, RUNNING, DONE, FAILED }

    private data class PlayerPrefs(
        val skips: Boolean = true,
        val autoSkip: Boolean = true,
        val autoplay: Boolean = true,
        val preloadNext: Boolean = true,
        val speed: Float = 1f,
        val quality: PlayerQuality = PlayerQuality.FULLHD,
        val forwardBufferSeconds: Int = 0,
        val backBufferSeconds: Int = 0,
        val memoryLimitMb: Int = 0,
        val diskCacheEnabled: Boolean = true,
        val diskCacheSizeMb: Int = 0,
    )
}
