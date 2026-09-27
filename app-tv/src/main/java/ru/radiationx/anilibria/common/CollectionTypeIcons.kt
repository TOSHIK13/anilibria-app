package ru.radiationx.anilibria.common

import androidx.annotation.DrawableRes
import ru.radiationx.anilibria.R
import ru.radiationx.data.entity.domain.collection.CollectionType

/**
 * Маленькая иконка коллекции пользователя — на карточках релиза (в т.ч. частях франшизы)
 * и в списке выбора коллекции на экране релиза.
 */
@DrawableRes
fun CollectionType.iconRes(): Int = when (this) {
    CollectionType.PLANNED -> R.drawable.ic_collection_planned
    CollectionType.WATCHING -> R.drawable.ic_collection_watching
    CollectionType.WATCHED -> R.drawable.ic_collection_watched
    CollectionType.POSTPONED -> R.drawable.ic_collection_postponed
    CollectionType.ABANDONED -> R.drawable.ic_collection_abandoned
}
