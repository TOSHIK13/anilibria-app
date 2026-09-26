package ru.radiationx.anilibria.common

data class LoadingCard(
    val title: String = "",
    val description: String = "",
    val isError: Boolean = false,
    /** Статичная заглушка «пусто»: иконка и текст вместо спиннера. */
    val isEmpty: Boolean = false,
) : CardItem {
    override fun getId(): Int {
        return title.hashCode()
    }
}