package ru.radiationx.data.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.external.DesiredStatus.COMPLETED
import ru.radiationx.data.external.DesiredStatus.CURRENT
import ru.radiationx.data.external.DesiredStatus.DROPPED
import ru.radiationx.data.external.DesiredStatus.PAUSED
import ru.radiationx.data.external.DesiredStatus.PLANNING

class FirstSyncPlannerTest {

    private fun s(status: DesiredStatus, progress: Int = 0) = SyncSnapshot(status, progress)

    private fun item(mal: Int, l: SyncSnapshot?, r: SyncSnapshot?) =
        FirstSyncItem(mal, mal * 10, "T$mal", l, r, episodes = 12)

    private fun report(
        differing: List<FirstSyncItem> = emptyList(),
        onlyRemote: List<FirstSyncItem> = emptyList(),
        onlyLocal: List<FirstSyncItem> = emptyList(),
        notInCatalog: List<FirstSyncItem> = emptyList(),
        matching: List<FirstSyncItem> = emptyList(),
    ) = FirstSyncReport(matching, differing, onlyRemote, onlyLocal, notInCatalog, 0, 10, 0)

    private fun only(plan: FirstSyncPlan) = plan.actions.single()

    // --- Объединить ---

    @Test
    fun `merge takes max progress and pushes to lagging side`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(CURRENT, 5), s(CURRENT, 3)))), FirstSyncPolicy.MERGE))
        assertEquals(FirstSyncFlow.TO_REMOTE, a.flow)
        assertEquals(s(CURRENT, 5), a.toRemote)
        assertNull(a.toLocal)
    }

    @Test
    fun `merge raises local progress when remote is ahead`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(CURRENT, 2), s(CURRENT, 6)))), FirstSyncPolicy.MERGE))
        assertEquals(FirstSyncFlow.TO_LOCAL, a.flow)
        assertNull(a.localSetStatus)
        assertEquals(6, a.localRaiseTo)
        assertNull(a.toRemote)
    }

    @Test
    fun `merge completed wins automatically`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(COMPLETED, 12), s(CURRENT, 8)))), FirstSyncPolicy.MERGE))
        assertEquals(FirstSyncFlow.TO_REMOTE, a.flow)
        assertEquals(COMPLETED, a.toRemote?.status)
        val b = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(CURRENT, 8), s(COMPLETED, 12)))), FirstSyncPolicy.MERGE))
        assertEquals(FirstSyncFlow.TO_LOCAL, b.flow)
        assertEquals(COMPLETED, b.localSetStatus)
        assertNull(b.localRaiseTo) // «Просмотрено» отмечает все серии
    }

    @Test
    fun `merge different statuses without completed is a conflict left untouched`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(PAUSED, 12), s(DROPPED, 12)))), FirstSyncPolicy.MERGE))
        assertEquals(FirstSyncFlow.CHOOSE, a.flow)
        assertNull(a.toRemote)
        assertNull(a.toLocal)
        assertEquals(PAUSED to DROPPED, a.options)
        assertEquals(s(PAUSED, 12), a.finalLocal)
        assertEquals(s(DROPPED, 12), a.finalRemote)
    }

    @Test
    fun `merge conflict resolved by user choice`() {
        val rep = report(differing = listOf(item(1, s(PAUSED, 12), s(DROPPED, 12))))
        val keepLocal = only(FirstSyncPlanner.plan(rep, FirstSyncPolicy.MERGE, mapOf(1 to PAUSED)))
        assertEquals(FirstSyncFlow.TO_REMOTE, keepLocal.flow)
        assertEquals(s(PAUSED, 12), keepLocal.toRemote)
        val keepRemote = only(FirstSyncPlanner.plan(rep, FirstSyncPolicy.MERGE, mapOf(1 to DROPPED)))
        assertEquals(FirstSyncFlow.TO_LOCAL, keepRemote.flow)
        assertEquals(DROPPED, keepRemote.localSetStatus)
    }

    @Test
    fun `merge choice overrides completed rule`() {
        val rep = report(differing = listOf(item(1, s(COMPLETED, 12), s(DROPPED, 3))))
        val a = only(FirstSyncPlanner.plan(rep, FirstSyncPolicy.MERGE, mapOf(1 to DROPPED)))
        assertEquals(FirstSyncFlow.BOTH, a.flow)
        assertEquals(DROPPED, a.localSetStatus)
    }

    @Test
    fun `merge same status different progress with status win can change both sides`() {
        // статус выбран у AniList, а прогресс больше у нас: обе стороны подтягиваются
        val rep = report(differing = listOf(item(1, s(PAUSED, 9), s(DROPPED, 4))))
        val a = only(FirstSyncPlanner.plan(rep, FirstSyncPolicy.MERGE, mapOf(1 to DROPPED)))
        assertEquals(FirstSyncFlow.BOTH, a.flow)
        assertEquals(s(DROPPED, 9), a.toRemote)
        assertEquals(s(DROPPED, 9), a.toLocal)
        assertEquals(DROPPED, a.localSetStatus)
        assertNull(a.localRaiseTo) // локальный прогресс уже 9
    }

    @Test
    fun `merge adds missing on both sides`() {
        val plan = FirstSyncPlanner.plan(
            report(
                onlyRemote = listOf(item(1, null, s(CURRENT, 4))),
                onlyLocal = listOf(item(2, s(PLANNING), null)),
            ),
            FirstSyncPolicy.MERGE,
        )
        val remoteOnly = plan.actions.first { it.item.malId == 1 }
        assertEquals(FirstSyncFlow.TO_LOCAL, remoteOnly.flow)
        assertEquals(CURRENT, remoteOnly.localSetStatus)
        assertEquals(4, remoteOnly.localRaiseTo)
        val localOnly = plan.actions.first { it.item.malId == 2 }
        assertEquals(FirstSyncFlow.TO_REMOTE, localOnly.flow)
        assertEquals(DesiredState(PLANNING, 0, 12), localOnly.pushState())
        assertEquals(1, plan.toLocalCount)
        assertEquals(1, plan.toRemoteCount)
    }

    // --- AniLiberty главнее ---

    @Test
    fun `aniliberty wins overwrites differing and pushes local only, leaves remote only`() {
        val plan = FirstSyncPlanner.plan(
            report(
                differing = listOf(item(1, s(PAUSED, 5), s(DROPPED, 7))),
                onlyRemote = listOf(item(2, null, s(CURRENT, 3))),
                onlyLocal = listOf(item(3, s(COMPLETED, 12), null)),
            ),
            FirstSyncPolicy.ANILIBERTY,
        )
        val d = plan.actions.first { it.item.malId == 1 }
        assertEquals(FirstSyncFlow.TO_REMOTE, d.flow)
        assertEquals(s(PAUSED, 5), d.toRemote)
        assertNull(d.toLocal)
        val r = plan.actions.first { it.item.malId == 2 }
        assertEquals(FirstSyncFlow.NONE, r.flow)
        assertNull(r.toLocal)
        assertEquals(s(CURRENT, 3), r.finalRemote)
        assertEquals(SyncSnapshot.ABSENT, r.finalLocal)
        val l = plan.actions.first { it.item.malId == 3 }
        assertEquals(FirstSyncFlow.TO_REMOTE, l.flow)
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun `aniliberty wins has no conflicts even for different statuses`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(PAUSED, 12), s(DROPPED, 12)))), FirstSyncPolicy.ANILIBERTY))
        assertEquals(FirstSyncFlow.TO_REMOTE, a.flow)
    }

    // --- AniList главнее ---

    @Test
    fun `anilist wins adapts local, adds remote only, leaves local only`() {
        val plan = FirstSyncPlanner.plan(
            report(
                differing = listOf(item(1, s(CURRENT, 2), s(COMPLETED, 12))),
                onlyRemote = listOf(item(2, null, s(PLANNING))),
                onlyLocal = listOf(item(3, s(CURRENT, 3), null)),
            ),
            FirstSyncPolicy.ANILIST,
        )
        val d = plan.actions.first { it.item.malId == 1 }
        assertEquals(FirstSyncFlow.TO_LOCAL, d.flow)
        assertEquals(COMPLETED, d.localSetStatus)
        assertNull(d.toRemote)
        val r = plan.actions.first { it.item.malId == 2 }
        assertEquals(FirstSyncFlow.TO_LOCAL, r.flow)
        assertEquals(PLANNING, r.localSetStatus)
        assertNull(r.localRaiseTo)
        val l = plan.actions.first { it.item.malId == 3 }
        assertEquals(FirstSyncFlow.NONE, l.flow)
        assertNull(l.toRemote)
        assertEquals(s(CURRENT, 3), l.finalLocal)
    }

    @Test
    fun `anilist wins never lowers local progress`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(CURRENT, 9), s(PAUSED, 4)))), FirstSyncPolicy.ANILIST))
        assertEquals(FirstSyncFlow.TO_LOCAL, a.flow)
        assertEquals(PAUSED, a.localSetStatus)
        assertNull(a.localRaiseTo)
        assertEquals(s(PAUSED, 9), a.finalLocal)
        assertTrue(a.progressKept)
    }

    @Test
    fun `anilist wins same status lower remote progress changes nothing but is noted`() {
        val a = only(FirstSyncPlanner.plan(report(differing = listOf(item(1, s(CURRENT, 9), s(CURRENT, 4)))), FirstSyncPolicy.ANILIST))
        assertEquals(FirstSyncFlow.NONE, a.flow)
        assertTrue(a.progressKept)
    }

    // --- общее ---

    @Test
    fun `not in catalog is always skipped and matching needs no work`() {
        for (policy in FirstSyncPolicy.values()) {
            val plan = FirstSyncPlanner.plan(
                report(
                    notInCatalog = listOf(item(1, null, s(CURRENT, 3))),
                    matching = listOf(item(2, s(CURRENT, 3), s(CURRENT, 3))),
                ),
                policy,
            )
            assertEquals(FirstSyncFlow.SKIP, plan.actions.first { it.item.malId == 1 }.flow)
            assertEquals(FirstSyncFlow.NONE, plan.actions.first { it.item.malId == 2 }.flow)
            assertEquals(0, plan.changes)
            assertEquals(1, plan.skipped)
        }
    }

    @Test
    fun `plan is idempotent after apply - matching items produce no work`() {
        val rep = report(matching = listOf(item(1, s(CURRENT, 5), s(CURRENT, 5)), item(2, s(COMPLETED, 12), s(COMPLETED, 12))))
        for (policy in FirstSyncPolicy.values()) {
            assertEquals(0, FirstSyncPlanner.plan(rep, policy).changes)
        }
    }
}
