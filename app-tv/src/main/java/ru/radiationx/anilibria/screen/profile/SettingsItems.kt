package ru.radiationx.anilibria.screen.profile

/** Разделы экрана настроек (левая колонка). */
enum class SettingsSection(val title: String) {
    ACCOUNTS("Аккаунты и сервисы"),
    PLAYER("Плеер"),
    APP("Приложение"),
}

/** Действия строк настроек: по ним ViewModel понимает, что нажато. */
enum class SettingsAction {
    SIGN_IN,
    SIGN_OUT,
    OPEN_ANILIST,
    REFRESH_HISTORY,

    SKIPS,
    AUTO_SKIP,
    AUTOPLAY,
    PRELOAD_NEXT,
    SPEED,
    QUALITY,
    BUFFER_FORWARD,
    BUFFER_BACK,
    BUFFER_MEMORY,
    DISK_CACHE,
    DISK_CACHE_SIZE,

    VERSION,
    SERVER,
    ABOUT,
}

/** Элементы правой части; [key] стабилен между обновлениями состояния (для сохранения фокуса). */
sealed class SettingsItem {
    abstract val key: String

    /** Карточка аккаунта AniLiberty (или внешнего сервиса). */
    data class Account(
        override val key: String,
        val nick: String?,
        val avatarUrl: String?,
        val subtitle: String,
        val actionTitle: String,
        val action: SettingsAction?,
    ) : SettingsItem()

    /** Строка сервиса статистики (AniList): значок, название, состояние, действие справа. */
    data class Service(
        override val key: String,
        val action: SettingsAction,
        val title: String,
        val iconText: String,
        val iconColor: Int,
        val subtitle: String,
        /** Подстрока красным (вход истёк). */
        val subtitleError: Boolean,
        /** Подстрока жёлтым (изменения ждут отправки). */
        val subtitleWarning: Boolean = false,
        val value: String,
    ) : SettingsItem()

    data class Header(
        override val key: String,
        val title: String,
    ) : SettingsItem()

    data class Row(
        val action: SettingsAction,
        val title: String,
        val subtitle: String? = null,
        /** Значение справа (со стрелкой «›»), null — без значения. */
        val value: String? = null,
        /** Состояние переключателя, null — строка без переключателя. */
        val switch: Boolean? = null,
        override val key: String = action.name,
    ) : SettingsItem()
}
