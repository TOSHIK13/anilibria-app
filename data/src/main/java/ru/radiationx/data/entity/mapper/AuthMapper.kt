package ru.radiationx.data.entity.mapper

import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.domain.auth.OtpInfo
import ru.radiationx.data.entity.domain.auth.SocialAuth
import ru.radiationx.data.entity.domain.other.ProfileItem
import ru.radiationx.data.entity.response.auth.OtpInfoResponse
import ru.radiationx.data.entity.response.auth.SocialAuthResponse
import ru.radiationx.data.entity.response.auth.V1SocialAuthLoginResponse
import ru.radiationx.data.entity.response.auth.V1OtpInfoResponse
import ru.radiationx.data.entity.response.other.ProfileResponse
import ru.radiationx.data.entity.response.other.V1ProfileImageResponse
import ru.radiationx.data.entity.response.other.V1ProfileResponse
import java.util.Locale
import java.util.Date

fun OtpInfoResponse.toDomain(): OtpInfo = OtpInfo(
    code = code,
    description = description,
    expiresAt = expiredAt.secToDate(),
    remainingTime = remainingTime.secToMillis()
)

fun V1OtpInfoResponse.toDomain(): OtpInfo {
    val remainingMillis = remainingTime.toLong() * 1000L
    return OtpInfo(
        code = otp.code,
        description = "Введите код на сайте AniLiberty",
        expiresAt = Date(System.currentTimeMillis() + remainingMillis),
        remainingTime = remainingMillis
    )
}

fun SocialAuthResponse.toDomain(): SocialAuth = SocialAuth(
    key = key,
    title = title,
    socialUrl = socialUrl,
    resultPattern = resultPattern,
    errorUrlPattern = errorUrlPattern
)

fun V1SocialAuthLoginResponse.toDomain(provider: String): SocialAuth = SocialAuth(
    key = provider,
    title = provider.toSocialTitle(),
    socialUrl = url,
    resultPattern = buildResultPattern(state),
    errorUrlPattern = "",
    authState = state,
)

fun ProfileResponse.toDomain(apiConfig: ApiConfig): ProfileItem = ProfileItem(
    id,
    nick.orEmpty(),
    avatarUrl?.appendBaseUrl(apiConfig.baseImagesUrl)
)

fun V1ProfileResponse.toDomain(apiConfig: ApiConfig): ProfileItem = ProfileItem(
    id,
    nickname ?: login.orEmpty(),
    avatar?.toAvatarUrl(apiConfig)
)

private fun V1ProfileImageResponse.toAvatarUrl(apiConfig: ApiConfig): String? {
    val path = optimized?.preview
        ?: preview
        ?: optimized?.thumbnail
        ?: thumbnail
    return path?.let {
        if (it.startsWith("http")) it else it.appendBaseUrl(apiConfig.baseImagesUrl)
    }
}

private fun String.toSocialTitle(): String = when (lowercase(Locale.ROOT)) {
    "vk" -> "VK"
    "google" -> "Google"
    "discord" -> "Discord"
    "patreon" -> "Patreon"
    else -> replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }
}

private fun buildResultPattern(state: String): String {
    val escapedState = Regex.escape(state)
    return "((?:.*[?&])state=$escapedState(?:[&#].*)?)"
}
