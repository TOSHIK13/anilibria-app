package ru.radiationx.data.external

/** Политика первой синхронизации. */
enum class FirstSyncPolicy {
    /** Прогресс — максимум, статус — явный выбор / «Просмотрено» / иначе нужен выбор; недостающее добавляется в обе стороны. */
    MERGE,

    /** AniList перезаписывается нашими данными; записи только из AniList остаются как есть. */
    ANILIBERTY,

    /** AniLiberty подстраивается под AniList (прогресс локально только растёт); записи только у нас остаются. */
    ANILIST,
}

/** Что произойдёт с тайтлом. */
enum class FirstSyncFlow {
    /** Ничего менять не нужно (совпадает или остаётся как есть). */
    NONE,

    /** Изменится запись в AniList (через очередь отправки). */
    TO_REMOTE,

    /** Изменится AniLiberty. */
    TO_LOCAL,

    /** Изменятся обе стороны (например, статус у одной, прогресс у другой). */
    BOTH,

    /** Статусы расходятся, а выбор не сделан: пропускается. */
    CHOOSE,

    /** Нет в каталоге AniLiberty: пропускается. */
    SKIP,
}

/**
 * Действие по одному тайтлу. [toRemote]/[toLocal] — целевое состояние стороны, если она меняется.
 * [localSetStatus]/[localRaiseTo] — что применить к AniLiberty (см. [ExternalPullSync.applyLocalChange]).
 * [finalLocal]/[finalRemote] — состояние сторон после применения (новая база слияния).
 */
data class FirstSyncAction(
    val item: FirstSyncItem,
    val group: FirstSyncCompare.Group,
    val flow: FirstSyncFlow,
    val toRemote: SyncSnapshot?,
    val toLocal: SyncSnapshot?,
    val localSetStatus: DesiredStatus?,
    val localRaiseTo: Int?,
    val finalLocal: SyncSnapshot,
    val finalRemote: SyncSnapshot,
    /** Локальный прогресс больше, чем в AniList, но политика «AniList главнее»: не уменьшаем. */
    val progressKept: Boolean = false,
    /** Для [FirstSyncFlow.CHOOSE]: варианты (статус AniLiberty, статус AniList). */
    val options: Pair<DesiredStatus, DesiredStatus>? = null,
) {
    val hasWork: Boolean get() = toRemote != null || toLocal != null

    /** Что отправить в AniList. */
    fun pushState(): DesiredState? = toRemote?.let { DesiredState(it.status, it.progress, item.episodes) }
}

data class FirstSyncPlan(val actions: List<FirstSyncAction>) {
    val toRemoteCount: Int get() = actions.count { it.toRemote != null }
    val toLocalCount: Int get() = actions.count { it.toLocal != null }
    val conflicts: Int get() = actions.count { it.flow == FirstSyncFlow.CHOOSE }
    val skipped: Int get() = actions.count { it.flow == FirstSyncFlow.SKIP }
    val changes: Int get() = actions.count { it.hasWork }
}

/** Строит план первой синхронизации по отчёту сравнения (чистая функция). */
object FirstSyncPlanner {

    /** [choices] — явный выбор статуса пользователем для конфликтных тайтлов (ключ — MAL id). */
    fun plan(
        report: FirstSyncReport,
        policy: FirstSyncPolicy,
        choices: Map<Int, DesiredStatus> = emptyMap(),
    ): FirstSyncPlan {
        val actions = ArrayList<FirstSyncAction>()
        report.matching.forEach { actions += none(it, FirstSyncCompare.Group.MATCHING) }
        report.differing.forEach { actions += differing(it, policy, choices[it.malId]) }
        report.onlyRemote.forEach { actions += onlyRemote(it, policy) }
        report.onlyLocal.forEach { actions += onlyLocal(it, policy) }
        report.notInCatalog.forEach {
            actions += FirstSyncAction(
                it, FirstSyncCompare.Group.NONE, FirstSyncFlow.SKIP, null, null, null, null,
                SyncSnapshot.ABSENT, it.remote ?: SyncSnapshot.ABSENT,
            )
        }
        return FirstSyncPlan(actions)
    }

    private fun local(item: FirstSyncItem) = item.local?.takeIf { it.status != null } ?: SyncSnapshot.ABSENT
    private fun remote(item: FirstSyncItem) = item.remote?.takeIf { it.status != null } ?: SyncSnapshot.ABSENT

    private fun none(item: FirstSyncItem, group: FirstSyncCompare.Group) = FirstSyncAction(
        item, group, FirstSyncFlow.NONE, null, null, null, null, local(item), remote(item),
    )

    /** Сторона отстаёт от [target]: другой статус или (кроме «Просмотрено») меньший прогресс. */
    private fun behind(side: SyncSnapshot, target: SyncSnapshot): Boolean =
        side.status != target.status ||
            (target.status != DesiredStatus.COMPLETED && target.progress > side.progress)

    private fun differing(item: FirstSyncItem, policy: FirstSyncPolicy, choice: DesiredStatus?): FirstSyncAction {
        val l = local(item)
        val r = remote(item)
        return when (policy) {
            FirstSyncPolicy.ANILIBERTY -> {
                val needs = behind(r, l)
                // AniList не понижает прогресс (правила записи), поэтому итог там — не меньше его текущего
                val finalRemote = if (l.status == DesiredStatus.COMPLETED) l else SyncSnapshot(l.status, maxOf(l.progress, r.progress))
                FirstSyncAction(
                    item, FirstSyncCompare.Group.DIFFERING,
                    if (needs) FirstSyncFlow.TO_REMOTE else FirstSyncFlow.NONE,
                    toRemote = l.takeIf { needs }, toLocal = null,
                    localSetStatus = null, localRaiseTo = null,
                    finalLocal = l, finalRemote = if (needs) finalRemote else r,
                )
            }

            FirstSyncPolicy.ANILIST -> {
                val needs = behind(l, r)
                val target = SyncSnapshot(r.status, maxOf(l.progress, r.progress))
                FirstSyncAction(
                    item, FirstSyncCompare.Group.DIFFERING,
                    if (needs) FirstSyncFlow.TO_LOCAL else FirstSyncFlow.NONE,
                    toRemote = null, toLocal = target.takeIf { needs },
                    localSetStatus = r.status.takeIf { needs && it != l.status },
                    localRaiseTo = if (needs) raise(target, l) else null,
                    finalLocal = if (needs) target else l, finalRemote = r,
                    progressKept = r.status != DesiredStatus.COMPLETED && l.progress > r.progress,
                )
            }

            FirstSyncPolicy.MERGE -> mergeDiffering(item, l, r, choice)
        }
    }

    private fun mergeDiffering(item: FirstSyncItem, l: SyncSnapshot, r: SyncSnapshot, choice: DesiredStatus?): FirstSyncAction {
        val status = when {
            l.status == r.status -> l.status
            choice != null && (choice == l.status || choice == r.status) -> choice
            l.status == DesiredStatus.COMPLETED || r.status == DesiredStatus.COMPLETED -> DesiredStatus.COMPLETED
            else -> null
        }
        if (status == null) {
            // конфликт: пока не выбрано, ничего не меняем, база — как есть
            return FirstSyncAction(
                item, FirstSyncCompare.Group.DIFFERING, FirstSyncFlow.CHOOSE, null, null, null, null, l, r,
                options = l.status!! to r.status!!,
            )
        }
        val target = SyncSnapshot(status, maxOf(l.progress, r.progress))
        val toRemote = behind(r, target)
        val toLocal = behind(l, target)
        val flow = when {
            toRemote && toLocal -> FirstSyncFlow.BOTH
            toRemote -> FirstSyncFlow.TO_REMOTE
            toLocal -> FirstSyncFlow.TO_LOCAL
            else -> FirstSyncFlow.NONE
        }
        return FirstSyncAction(
            item, FirstSyncCompare.Group.DIFFERING, flow,
            toRemote = target.takeIf { toRemote }, toLocal = target.takeIf { toLocal },
            localSetStatus = status.takeIf { toLocal && it != l.status },
            localRaiseTo = if (toLocal) raise(target, l) else null,
            finalLocal = target, finalRemote = target,
        )
    }

    private fun onlyRemote(item: FirstSyncItem, policy: FirstSyncPolicy): FirstSyncAction {
        val r = remote(item)
        if (policy == FirstSyncPolicy.ANILIBERTY) return none(item, FirstSyncCompare.Group.ONLY_REMOTE)
        return FirstSyncAction(
            item, FirstSyncCompare.Group.ONLY_REMOTE, FirstSyncFlow.TO_LOCAL,
            toRemote = null, toLocal = r,
            localSetStatus = r.status, localRaiseTo = raise(r, SyncSnapshot.ABSENT),
            finalLocal = r, finalRemote = r,
        )
    }

    private fun onlyLocal(item: FirstSyncItem, policy: FirstSyncPolicy): FirstSyncAction {
        val l = local(item)
        if (policy == FirstSyncPolicy.ANILIST) return none(item, FirstSyncCompare.Group.ONLY_LOCAL)
        return FirstSyncAction(
            item, FirstSyncCompare.Group.ONLY_LOCAL, FirstSyncFlow.TO_REMOTE,
            toRemote = l, toLocal = null, localSetStatus = null, localRaiseTo = null,
            finalLocal = l, finalRemote = l,
        )
    }

    /** До какого номера серии отметить просмотренными; для «Просмотрено» — null (отмечается всё). */
    private fun raise(target: SyncSnapshot, current: SyncSnapshot): Int? {
        if (target.status == DesiredStatus.COMPLETED) return null
        val have = if (current.status == null) 0 else current.progress
        return target.progress.takeIf { it > have }
    }
}
