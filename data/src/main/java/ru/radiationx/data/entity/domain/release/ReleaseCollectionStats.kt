package ru.radiationx.data.entity.domain.release

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Счётчики пользователей AniLiberty по релизу (V1 `added_in_*`); null — поле не пришло. */
@Parcelize
data class ReleaseCollectionStats(
    val favorites: Int? = null,
    val watching: Int? = null,
    val planned: Int? = null,
    val watched: Int? = null,
    val postponed: Int? = null,
    val abandoned: Int? = null,
) : Parcelable {

    val isEmpty: Boolean
        get() = listOfNotNull(favorites, watching, planned, watched, postponed, abandoned).isEmpty()
}
