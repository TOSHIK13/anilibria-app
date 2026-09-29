package ru.radiationx.anilibria.screen.services

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.github.terrakok.cicerone.androidx.FragmentScreen

/** Настройки → Аккаунты и сервисы → «Подключить AniList» / «Обновить вход»: вставка токена. */
class AniListLinkScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment = AniListLinkFragment()
}

/** Настройки → Аккаунты и сервисы → строка AniList (когда аккаунт уже привязан). */
class AniListServiceScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment = AniListServiceFragment()
}

/** «1 день», «2 дня», «5 дней» — по правилам русского языка независимо от локали устройства. */
fun daysText(days: Int): String {
    val mod100 = days % 100
    val mod10 = days % 10
    val word = when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
    return "$days $word"
}

/** Экран сервиса → «Журнал синхронизации». */
class AniListJournalScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment = AniListJournalFragment()
}
