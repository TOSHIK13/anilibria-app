package ru.radiationx.data.datasource.remote.common

interface CheckerReserveSources {

    val sources: List<String>

    /**
     * true — сначала legacy `query=app_update`, [sources] только как fallback.
     * false — только [sources] (legacy endpoint отдаёт обновление мобильного приложения).
     */
    val useLegacyApi: Boolean
        get() = true
}
