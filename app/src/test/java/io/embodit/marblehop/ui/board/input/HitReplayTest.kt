package io.embodit.marblehop.ui.board.input

import io.embodit.marblehop.domain.GameConfig
import io.embodit.marblehop.domain.GameFlow
import io.embodit.marblehop.domain.Opponent
import io.embodit.marblehop.domain.Phase
import io.embodit.marblehop.domain.Settings
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.domain.MemStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * build.md「命中目标 → 格子必须有回放单测」: replay what the system reports as the hit entity (the groove disc, its hole
 * holder, a marble, the slab / face / rim / sign / ghost …) through the same name rule the board-input template uses,
 * and on into GameFlow.tap — every hit on a hole must resolve to that hole, everything else to nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private val parents = listOf("boardRoot", "tiltRoot", "root")
    private fun h(s: String) = StarBoard.parse(s)!!

    @Test fun everyHitKindResolvesToItsHole() {
        for (i in 0 until StarBoard.SIZE) {
            assertEquals(i, resolvePath(listOf(BoardNames.groove(i), BoardNames.hole(i)) + parents, HoleTargets::holeOf))
            assertEquals(i, resolvePath(listOf(BoardNames.hole(i)) + parents, HoleTargets::holeOf))
            assertEquals(i, resolvePath(listOf(BoardNames.marble(i)) + parents, HoleTargets::holeOf))
        }
    }

    @Test fun nonTargetsResolveToNothing() {
        for (n in listOf("slab", "face", "rim", "pip:3", "ghost", "litHop", "litStep", "chain", "spark", "selectRing", "redRing", "fallbackBoard", "marble:-", "hole:121", "hole:-1")) {
            assertNull(n, resolvePath(listOf(n) + parents, HoleTargets::holeOf))
        }
    }

    @Test fun loggedHeadsetStylePathsReplay() {
        // the exact `path=` format BoardInput writes to files/input-log.txt (no headset log for this app yet: synthetic lines)
        val lines = listOf(
            "groove:56 < hole:56 < boardRoot < tiltRoot < root" to 56,
            "marble:12 < boardRoot < tiltRoot < root" to 12,
            "face < boardRoot < tiltRoot < root" to null,
        )
        for ((path, want) in lines) assertEquals(path, want, resolvePath(parseLoggedPath(path), HoleTargets::holeOf))
    }

    @Test fun replayedHitsDriveTheGameThroughTheSamePath() = runTest {
        val flow = GameFlow(this, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, MemStore(),
            settings = { Settings(firstRunDone = true, coachDone = true) })
        flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        // hand ray hits the marble mesh, eye-hand the groove under the lit hole, controller the hole holder
        flow.tap(resolvePath(listOf(BoardNames.marble(h("2,-5"))) + parents, HoleTargets::holeOf))
        assertEquals(Phase.SELECTED, flow.state.value.phase)
        flow.tap(resolvePath(listOf(BoardNames.groove(h("2,-4")), BoardNames.hole(h("2,-4"))) + parents, HoleTargets::holeOf))
        assertEquals(Phase.PREVIEW, flow.state.value.phase)
        flow.tap(resolvePath(listOf(BoardNames.hole(h("2,-4"))) + parents, HoleTargets::holeOf))
        assertEquals(Phase.ANIMATING, flow.state.value.phase)
    }

    @Test fun dragTotalAddsPerEventDeltas() {
        // SDK 6.1.9 sends per-event dragAmount: 60 events x 2 mm must total 12 cm
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-4f)
        assertEquals(60, t.events)
    }
}
