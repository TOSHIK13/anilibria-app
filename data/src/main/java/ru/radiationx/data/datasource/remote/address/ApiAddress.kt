package ru.radiationx.data.datasource.remote.address

data class ApiAddress(
    val tag: String,
    val name: String?,
    val desc: String?,
    val widgetsSite: String,
    val site: String,
    val baseImages: String,
    val base: String,
    val api: String,
    val animeBase: String? = null,
    val accountsBase: String? = null,
    val ips: List<String>,
    val proxies: List<ApiProxy>
)
