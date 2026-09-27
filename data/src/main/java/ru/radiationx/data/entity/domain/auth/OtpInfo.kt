package ru.radiationx.data.entity.domain.auth

import java.util.*

data class OtpInfo(
    val code: String,
    val description: String,
    val expiresAt: Date,
    val remainingTime: Long
)

private val OTP_CODE_REGEX = Regex("^\\d{6}$")

/**
 * Сайт принимает только 6-значный код, а сервер иногда выдаёт 5 цифр
 * (теряет ведущий ноль). Такой код ввести невозможно — его нужно перевыпустить.
 */
fun isValidOtpCode(code: String): Boolean = OTP_CODE_REGEX.matches(code)

fun OtpInfo.hasValidCode(): Boolean = isValidOtpCode(code)