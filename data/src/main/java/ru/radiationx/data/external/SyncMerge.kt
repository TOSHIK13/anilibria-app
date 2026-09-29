package ru.radiationx.data.external

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Метка «изменение пришло из внешнего сервиса». Кладётся в контекст корутины на время применения
 * входящих изменений к AniLiberty; [ru.radiationx.data.tracker.AnimeTrackerRegistry] по ней не
 * рассылает события трекерам, чтобы полученное из AniList не ушло обратно в очередь отправки.
 */
object RemoteOrigin : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<RemoteOrigin>

    suspend fun isActive(): Boolean = coroutineContext[Key] != null
}

/** Состояние тайтла с одной из сторон: [status] null — записи нет. */
data class SyncSnapshot(val status: DesiredStatus?, val progress: Int) {
    companion object {
        val ABSENT = SyncSnapshot(null, 0)

        /** Статус AniList → статус AniLiberty; REPEATING = «Смотрю», неизвестный — null. */
        fun statusOf(remote: String?): DesiredStatus? = when (remote) {
            "PLANNING" -> DesiredStatus.PLANNING
            "CURRENT", "REPEATING" -> DesiredStatus.CURRENT
            "COMPLETED" -> DesiredStatus.COMPLETED
            "PAUSED" -> DesiredStatus.PAUSED
            "DROPPED" -> DesiredStatus.DROPPED
            else -> null
        }

        fun ofRemote(status: String?, progress: Int): SyncSnapshot {
            val s = statusOf(status) ?: return ABSENT
            return SyncSnapshot(s, progress)
        }

        fun parse(raw: String?): SyncSnapshot? {
            if (raw == null) return null
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val status = if (parts[0].isEmpty()) null else {
                runCatching { DesiredStatus.valueOf(parts[0]) }.getOrNull() ?: return null
            }
            return SyncSnapshot(status, parts[1].toIntOrNull() ?: return null)
        }
    }

    fun serialize(): String = "${status?.name.orEmpty()}:$progress"
}

/**
 * Вход трёхстороннего слияния одного тайтла (ключ — MAL id).
 * [baseLocal]/[baseRemote] — что было у нас и в сервисе при последнем согласовании
 * (нет записи — [SyncSnapshot.ABSENT]).
 */
data class MergeInput(
    val baseLocal: SyncSnapshot,
    val baseRemote: SyncSnapshot,
    val local: SyncSnapshot,
    /** Время последнего локального изменения, epoch ms; null — неизвестно. */
    val localChangedAt: Long?,
    val remote: SyncSnapshot,
    /** `updatedAt` записи в сервисе, epoch ms. */
    val remoteUpdatedAt: Long,
)

enum class MergeKind {
    /** Входящих изменений нет. */
    NO_CHANGE,

    /** Изменение сервиса применяется к AniLiberty. */
    APPLIED,

    /** Менялись обе стороны: слито по правилам. */
    MERGED,

    /** Запись есть только у нас: её надо отправить в сервис. */
    PUSH_ONLY,
}

/**
 * Итог слияния. [setStatus]: null — статус локально не менять, [DesiredStatus.REMOVE] — убрать из
 * коллекций. [raiseProgressTo]: отметить серии 1..N просмотренными (прогресс только растёт).
 * [push] — что отправить в сервис (null — нечего). [newBaseLocal]/[newBaseRemote] — новая база.
 */
data class MergeResult(
    val kind: MergeKind,
    val setStatus: DesiredStatus?,
    val raiseProgressTo: Int?,
    val push: DesiredState?,
    val newBaseLocal: SyncSnapshot,
    val newBaseRemote: SyncSnapshot,
) {
    val changesLocal: Boolean get() = setStatus != null || raiseProgressTo != null
}

/** Трёхстороннее слияние AniLiberty <-> сервис (чистая функция). */
object ThreeWayMerge {

    fun merge(input: MergeInput): MergeResult {
        val bL = input.baseLocal
        val bR = input.baseRemote
        val l = input.local
        val r = input.remote
        val remoteChanged = r != bR
        val localChanged = l != bL

        // remote == base: входящего нет; «только у нас» — запись появилась у нас, а в сервисе и в базе её нет
        if (!remoteChanged) {
            val push = if (localChanged && bR.status == null && r.status == null && l.status != null) {
                DesiredState(l.status, l.progress)
            } else null
            return MergeResult(
                if (push != null) MergeKind.PUSH_ONLY else MergeKind.NO_CHANGE,
                null, null, push, l, r,
            )
        }

        if (!localChanged) return applyRemote(bR, l, r)

        return bothChanged(l, input.localChangedAt, r, input.remoteUpdatedAt)
    }

    /** Сервис изменился, у нас нет: применяем; прогресс не уменьшаем, «Просмотрено» — только явной сменой статуса. */
    private fun applyRemote(bR: SyncSnapshot, l: SyncSnapshot, r: SyncSnapshot): MergeResult {
        if (r.status == null) {
            // запись исчезла в сервисе (была в базе, у нас без изменений) — убираем из коллекций
            val remove = if (l.status != null) DesiredStatus.REMOVE else null
            return MergeResult(
                if (remove != null) MergeKind.APPLIED else MergeKind.NO_CHANGE,
                remove, null, null, SyncSnapshot.ABSENT, r,
            )
        }
        val target = when {
            l.status == null -> r.status
            r.status != l.status && r.status != bR.status -> r.status
            else -> null
        }
        val localProgress = if (l.status == null) 0 else l.progress
        val raise = r.progress.takeIf { it > localProgress }
        val finalLocal = SyncSnapshot(target ?: l.status, maxOf(localProgress, r.progress))
        val applied = target != null || raise != null
        return MergeResult(if (applied) MergeKind.APPLIED else MergeKind.NO_CHANGE, target, raise, null, finalLocal, r)
    }

    private fun bothChanged(l: SyncSnapshot, localAt: Long?, r: SyncSnapshot, remoteAt: Long): MergeResult {
        val remoteNewer = localAt != null && remoteAt > localAt
        if (r.status == null) {
            // сервис удалил запись, а мы меняли: ничего не удаляем, возвращаем в сервис
            val push = l.status?.let { DesiredState(it, l.progress) }
            return MergeResult(if (push != null) MergeKind.MERGED else MergeKind.NO_CHANGE, null, null, push, l, r)
        }
        if (l.status == null) {
            // мы убрали из коллекций, сервис менял запись: побеждает более свежее
            return if (remoteNewer) {
                MergeResult(MergeKind.MERGED, r.status, r.progress.takeIf { it > 0 }, null, r, r)
            } else {
                MergeResult(MergeKind.MERGED, null, null, DesiredState(DesiredStatus.REMOVE), l, r)
            }
        }
        val status = when {
            l.status == r.status -> l.status
            l.status == DesiredStatus.COMPLETED -> l.status // автособытие не понижает «Просмотрено»
            remoteNewer -> r.status
            else -> l.status // время локального изменения неизвестно или оно свежее — оставляем своё
        }
        val progress = maxOf(l.progress, r.progress)
        val setStatus = status.takeIf { it != l.status }
        val raise = r.progress.takeIf { it > l.progress }
        val final = SyncSnapshot(status, progress)
        val push = if (status != r.status || progress > r.progress) DesiredState(status, progress) else null
        val changed = setStatus != null || raise != null || push != null
        return MergeResult(if (changed) MergeKind.MERGED else MergeKind.NO_CHANGE, setStatus, raise, push, final, r)
    }
}

/** Итог сравнения для первой синхронизации: одна строка — один тайтл. */
data class FirstSyncItem(
    val malId: Int,
    val releaseId: Int?,
    val title: String,
    /** Состояние в AniLiberty (null — тайтла нет в коллекциях). */
    val local: SyncSnapshot?,
    /** Состояние в AniList (null — записи нет). */
    val remote: SyncSnapshot?,
)

/**
 * Сравнение локального и удалённого списков (для мастера первой синхронизации). [noMalId] —
 * записей AniList без MAL id (пропущены), [remoteTotal] — всего записей AniList.
 */
data class FirstSyncReport(
    /** Совпадает: статус один и тот же (и прогресс, кроме «Просмотрено»). */
    val matching: List<FirstSyncItem>,
    /** Расходится: тайтл в обоих списках, статус или прогресс различаются. */
    val differing: List<FirstSyncItem>,
    /** Есть в AniList и в каталоге AniLiberty, но не в коллекциях. */
    val onlyRemote: List<FirstSyncItem>,
    /** Есть в коллекциях AniLiberty (с MAL id), но не в AniList. */
    val onlyLocal: List<FirstSyncItem>,
    /** Есть в AniList, но нет в каталоге AniLiberty. */
    val notInCatalog: List<FirstSyncItem>,
    val noMalId: Int,
    val remoteTotal: Int,
    val createdAt: Long,
) {
    /** Записей AniList с найденным релизом AniLiberty. */
    val linked: Int get() = remoteTotal - noMalId - notInCatalog.size
    val changesCount: Int get() = differing.size + onlyRemote.size + onlyLocal.size
}

object FirstSyncCompare {

    enum class Group { MATCHING, DIFFERING, ONLY_REMOTE, ONLY_LOCAL, NONE }

    fun group(local: SyncSnapshot?, remote: SyncSnapshot?): Group {
        val l = local?.takeIf { it.status != null }
        val r = remote?.takeIf { it.status != null }
        return when {
            l == null && r == null -> Group.NONE
            l == null -> Group.ONLY_REMOTE
            r == null -> Group.ONLY_LOCAL
            l.status == r.status && (l.status == DesiredStatus.COMPLETED || l.progress == r.progress) -> Group.MATCHING
            else -> Group.DIFFERING
        }
    }
}
