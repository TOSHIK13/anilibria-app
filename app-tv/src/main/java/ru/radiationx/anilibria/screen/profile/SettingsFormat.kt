package ru.radiationx.anilibria.screen.profile

import ru.radiationx.data.entity.common.PlayerQuality

object SettingsFormat {

    fun speed(value: Float): String {
        val text = if (value * 100 % 10 == 0f) {
            String.format("%.1f", value)
        } else {
            String.format("%.2f", value)
        }
        return "${text.replace('.', ',')}×"
    }

    fun quality(value: PlayerQuality): String = when (value) {
        PlayerQuality.SD -> "480p"
        PlayerQuality.HD -> "720p"
        PlayerQuality.FULLHD -> "1080p"
    }

    fun seconds(value: Int): String {
        if (value < 60) return "$value сек"
        val minutes = value / 60
        val seconds = value % 60
        return if (seconds == 0) "$minutes мин" else "$minutes мин $seconds сек"
    }

    fun megabytes(value: Int): String {
        if (value < 1024) return "$value МБ"
        val gb = value / 1024f
        val text = if (value % 1024 == 0) gb.toInt().toString() else String.format("%.1f", gb).replace('.', ',')
        return "$text ГБ"
    }
}
