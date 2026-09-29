package ru.radiationx.data.external

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.external.DesiredStatus.COMPLETED
import ru.radiationx.data.external.DesiredStatus.CURRENT
import ru.radiationx.data.external.DesiredStatus.DROPPED
import ru.radiationx.data.external.DesiredStatus.PAUSED
import ru.radiationx.data.external.DesiredStatus.PLANNING
import ru.radiationx.data.external.DesiredStatus.REMOVE

class ThreeWayMergeTest {

    private fun s(status: DesiredStatus?, progress: Int = 0) = SyncSnapshot(status, progress)
    private val absent = SyncSnapshot.ABSENT

    private fun merge(
        base: SyncSnapshot,
        local: SyncSnapshot,
        remote: SyncSnapshot,
        baseRemote: SyncSnapshot = base,
        localAt: Long? = null,
        remoteAt: Long = 1_000,
    ) = ThreeWayMerge.merge(MergeInput(base, baseRemote, local, localAt, remote, remoteAt))

    // --- remote == base ---

    @Test
    fun `remote unchanged and local unchanged gives no change`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 3), s(CURRENT, 3))
        assertEquals(MergeKind.NO_CHANGE, r.kind)
        assertNull(r.setStatus)
        assertNull(r.raiseProgressTo)
        assertNull(r.push)
    }

    @Test
    fun `remote unchanged and local changed does not apply anything`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 5), s(CURRENT, 3))
        assertEquals(MergeKind.NO_CHANGE, r.kind)
        assertFalse(r.changesLocal)
        assertNull(r.push)
        assertEquals(s(CURRENT, 5), r.newBaseLocal)
    }

    // --- remote changed, local == base ---

    @Test
    fun `remote status change is applied to local`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 3), s(PAUSED, 3))
        assertEquals(MergeKind.APPLIED, r.kind)
        assertEquals(PAUSED, r.setStatus)
        assertNull(r.raiseProgressTo)
        assertEquals(s(PAUSED, 3), r.newBaseLocal)
        assertEquals(s(PAUSED, 3), r.newBaseRemote)
    }

    @Test
    fun `remote progress increase raises local progress only`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 3), s(CURRENT, 6))
        assertEquals(MergeKind.APPLIED, r.kind)
        assertNull(r.setStatus)
        assertEquals(6, r.raiseProgressTo)
    }

    @Test
    fun `remote progress decrease is ignored`() {
        val r = merge(s(CURRENT, 8), s(CURRENT, 8), s(CURRENT, 2))
        assertEquals(MergeKind.NO_CHANGE, r.kind)
        assertNull(r.raiseProgressTo)
        assertEquals(s(CURRENT, 8), r.newBaseLocal)
        assertEquals(s(CURRENT, 2), r.newBaseRemote)
    }

    @Test
    fun `explicit remote change from completed is applied when local equals base`() {
        val r = merge(s(COMPLETED, 12), s(COMPLETED, 12), s(CURRENT, 12))
        assertEquals(CURRENT, r.setStatus)
        assertNull(r.raiseProgressTo)
    }

    @Test
    fun `progress only change never downgrades completed status`() {
        val r = merge(s(COMPLETED, 12), s(COMPLETED, 12), s(COMPLETED, 12), baseRemote = s(COMPLETED, 11))
        assertNull(r.setStatus)
    }

    @Test
    fun `remote reported repeating maps to current and does not downgrade same status`() {
        val remote = SyncSnapshot.ofRemote("REPEATING", 2)
        assertEquals(s(CURRENT, 2), remote)
        val r = merge(s(CURRENT, 2), s(CURRENT, 2), remote, baseRemote = s(CURRENT, 1))
        assertNull(r.setStatus)
        assertEquals(MergeKind.NO_CHANGE, r.kind)
    }

    // --- новые и исчезнувшие записи ---

    @Test
    fun `new remote entry is added to local collection`() {
        val r = merge(absent, absent, s(CURRENT, 4))
        assertEquals(MergeKind.APPLIED, r.kind)
        assertEquals(CURRENT, r.setStatus)
        assertEquals(4, r.raiseProgressTo)
    }

    @Test
    fun `new remote planned entry without progress only sets status`() {
        val r = merge(absent, absent, s(PLANNING, 0))
        assertEquals(PLANNING, r.setStatus)
        assertNull(r.raiseProgressTo)
    }

    @Test
    fun `entry removed remotely is removed locally only if it was in base and local equals base`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 3), absent, baseRemote = s(CURRENT, 3))
        assertEquals(MergeKind.APPLIED, r.kind)
        assertEquals(REMOVE, r.setStatus)
    }

    @Test
    fun `entry removed remotely is kept when local changed`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 5), absent, baseRemote = s(CURRENT, 3))
        assertNull(r.setStatus)
        assertEquals(DesiredState(CURRENT, 5), r.push)
    }

    @Test
    fun `entry never in base is not removed`() {
        // записи нет ни в сервисе, ни в базе, ни у нас — нечего удалять
        val r = merge(absent, absent, absent)
        assertEquals(MergeKind.NO_CHANGE, r.kind)
        assertNull(r.setStatus)
    }

    @Test
    fun `local only entry is queued for sending`() {
        val r = merge(absent, s(WATCHED_STATUS, 7), absent)
        assertEquals(MergeKind.PUSH_ONLY, r.kind)
        assertEquals(DesiredState(WATCHED_STATUS, 7), r.push)
        assertFalse(r.changesLocal)
    }

    @Test
    fun `local only entry already handled in base is not pushed again`() {
        val r = merge(s(WATCHED_STATUS, 7), s(WATCHED_STATUS, 7), absent, baseRemote = absent)
        assertEquals(MergeKind.NO_CHANGE, r.kind)
        assertNull(r.push)
    }

    // --- оба изменились ---

    @Test
    fun `both changed progress is max and pushes larger local`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 8), s(CURRENT, 5))
        assertEquals(MergeKind.MERGED, r.kind)
        assertNull(r.raiseProgressTo)
        assertEquals(DesiredState(CURRENT, 8), r.push)
        assertEquals(s(CURRENT, 8), r.newBaseLocal)
    }

    @Test
    fun `both changed remote progress larger raises local`() {
        val r = merge(s(CURRENT, 3), s(CURRENT, 4), s(CURRENT, 9))
        assertEquals(9, r.raiseProgressTo)
        assertNull(r.push)
    }

    @Test
    fun `both changed status newer remote wins`() {
        val r = merge(s(CURRENT, 3), s(PAUSED, 3), s(DROPPED, 3), localAt = 500, remoteAt = 900)
        assertEquals(DROPPED, r.setStatus)
        assertNull(r.push)
    }

    @Test
    fun `both changed status newer local wins and is pushed`() {
        val r = merge(s(CURRENT, 3), s(PAUSED, 3), s(DROPPED, 3), localAt = 900, remoteAt = 500)
        assertNull(r.setStatus)
        assertEquals(DesiredState(PAUSED, 3), r.push)
    }

    @Test
    fun `both changed status with unknown local time keeps local and pushes`() {
        val r = merge(s(CURRENT, 3), s(PAUSED, 3), s(DROPPED, 3), localAt = null, remoteAt = 900)
        assertNull(r.setStatus)
        assertEquals(DesiredState(PAUSED, 3), r.push)
    }

    @Test
    fun `both changed completed local is never downgraded by newer remote`() {
        val r = merge(s(CURRENT, 3), s(COMPLETED, 12), s(CURRENT, 5), localAt = 100, remoteAt = 900)
        assertNull(r.setStatus)
        assertEquals(DesiredState(COMPLETED, 12), r.push)
    }

    @Test
    fun `both changed local removal loses to newer remote`() {
        val r = merge(s(CURRENT, 3), absent, s(CURRENT, 6), localAt = 100, remoteAt = 900)
        assertEquals(CURRENT, r.setStatus)
        assertEquals(6, r.raiseProgressTo)
    }

    @Test
    fun `both changed local removal newer than remote is pushed as remove`() {
        val r = merge(s(CURRENT, 3), absent, s(CURRENT, 6), localAt = 900, remoteAt = 100)
        assertNull(r.setStatus)
        assertEquals(DesiredState(REMOVE), r.push)
    }

    // --- фильмы и сопоставление ---

    @Test
    fun `movie completed remotely is applied as completed with progress one`() {
        val r = merge(absent, absent, SyncSnapshot.ofRemote("COMPLETED", 1))
        assertEquals(COMPLETED, r.setStatus)
        assertEquals(1, r.raiseProgressTo)
    }

    @Test
    fun `movie completed remotely without progress still sets status`() {
        val r = merge(absent, absent, SyncSnapshot.ofRemote("COMPLETED", 0))
        assertEquals(COMPLETED, r.setStatus)
        assertNull(r.raiseProgressTo)
    }

    @Test
    fun `several releases per mal id are ordered by release id`() {
        val index = CatalogIndex(0, mapOf(30 to 5, 10 to 5, 20 to 7))
        assertEquals(listOf(10, 30), index.releasesOf(5))
        assertEquals(listOf(20), index.releasesOf(7))
        assertTrue(index.releasesOf(99).isEmpty())
    }

    // --- база и сравнение ---

    @Test
    fun `snapshot serialization round trips`() {
        assertEquals(s(CURRENT, 3), SyncSnapshot.parse(s(CURRENT, 3).serialize()))
        assertEquals(absent, SyncSnapshot.parse(absent.serialize()))
        assertNull(SyncSnapshot.parse("BROKEN"))
        assertNull(SyncSnapshot.parse(null))
    }

    @Test
    fun `base without stored snapshots falls back to remote state and current local`() {
        val state = TitleSyncState("anilist", 1, 2, "CURRENT", 4, syncedAt = 10, pending = false, error = null)
        val (bl, br) = state.baseSnapshots(s(CURRENT, 6))
        assertEquals(s(CURRENT, 6), bl)
        assertEquals(s(CURRENT, 4), br)
        val fresh = TitleSyncState("anilist", 1, 2, null, 0, syncedAt = 0, pending = true, error = null)
        assertEquals(absent to absent, fresh.baseSnapshots(s(CURRENT, 6)))
        val stored = fresh.copy(base = "PAUSED:2|COMPLETED:9", syncedAt = 5)
        assertEquals(s(PAUSED, 2) to s(COMPLETED, 9), stored.baseSnapshots(absent))
    }

    @Test
    fun `first sync compare groups`() {
        assertEquals(FirstSyncCompare.Group.MATCHING, FirstSyncCompare.group(s(CURRENT, 3), s(CURRENT, 3)))
        assertEquals(FirstSyncCompare.Group.MATCHING, FirstSyncCompare.group(s(COMPLETED, 10), s(COMPLETED, 12)))
        assertEquals(FirstSyncCompare.Group.DIFFERING, FirstSyncCompare.group(s(CURRENT, 3), s(CURRENT, 4)))
        assertEquals(FirstSyncCompare.Group.DIFFERING, FirstSyncCompare.group(s(CURRENT, 3), s(PAUSED, 3)))
        assertEquals(FirstSyncCompare.Group.ONLY_REMOTE, FirstSyncCompare.group(null, s(PLANNING)))
        assertEquals(FirstSyncCompare.Group.ONLY_LOCAL, FirstSyncCompare.group(s(PLANNING), absent))
        assertEquals(FirstSyncCompare.Group.NONE, FirstSyncCompare.group(null, null))
    }

    // --- метка источника (против петель) ---

    @Test
    fun `remote origin marker is visible only inside marked context`() = runBlocking {
        assertFalse(RemoteOrigin.isActive())
        assertTrue(withContext(RemoteOrigin) { RemoteOrigin.isActive() })
        // вложенные withContext (например Dispatchers.IO в репозиториях) метку сохраняют
        assertTrue(withContext(RemoteOrigin) { withContext(kotlinx.coroutines.Dispatchers.IO) { RemoteOrigin.isActive() } })
    }

    private companion object {
        val WATCHED_STATUS = COMPLETED
    }
}
