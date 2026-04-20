package ru.radiationx.data.entity.domain.collection

enum class CollectionType(val value: String) {
    PLANNED("PLANNED"),
    WATCHED("WATCHED"),
    WATCHING("WATCHING"),
    POSTPONED("POSTPONED"),
    ABANDONED("ABANDONED")
}
