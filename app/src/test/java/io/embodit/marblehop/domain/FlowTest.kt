package io.embodit.marblehop.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemStore : GameStore {
    var saved: SavedGame? = null
    override fun load() = saved
    override fun save(game: SavedGame) { saved = game }
    override fun clear() { saved = null }
}

/** Turn flow: two-stage select / confirm, drag, alerts, hot-seat hand-over, undo, save / restore (M1, M2, M7). */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private fun h(s: String) = StarBoard.parse(s)!!

    private class Rig(val scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, coachDone = true)) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(scope, StandardTestDispatcher(scope.testScheduler), { scope.testScheduler.currentTime }, store,
            settings = { settings }, onSettings = { settings = it }, onSound = { s, _ -> sounds += s })
    }

    @Test fun selectThenPreviewThenConfirm() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.EASY, 0)); runCurrent()
        assertEquals(Phase.IDLE, r.flow.state.value.phase)
        r.flow.tap(h("2,-5"))
        assertEquals(Phase.SELECTED, r.flow.state.value.phase)
        assertEquals(h("2,-5"), r.flow.state.value.selected)
        // a hit on any hole still selects nothing new: preview the nearest lit hole
        r.flow.tap(h("2,-4"))
        assertEquals(Phase.PREVIEW, r.flow.state.value.phase)
        // the position has not changed yet (first select, then confirm)
        assertEquals(0, r.flow.state.value.position!![h("2,-5")])
        r.flow.tap(h("2,-4"))
        assertEquals(Phase.ANIMATING, r.flow.state.value.phase)
        assertEquals(0, r.flow.state.value.position!![h("2,-4")])
        advanceTimeBy(GameFlow.STEP_MS + 1); runCurrent()
        assertEquals(Phase.THINKING, r.flow.state.value.phase)
        advanceUntilIdle()
        r.flow.holdThinking = false
    }

    @Test fun tapNearAMarbleSelectsItAndDropButtonPreviewsThenCommits() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        // (2,-4) is empty, one pitch from the movable (2,-5) and (1,-4)'s neighbours: the nearest movable is picked
        r.flow.tap(h("2,-4"))
        assertNotNull(r.flow.state.value.selected)
        r.flow.drop()
        assertEquals(Phase.PREVIEW, r.flow.state.value.phase)
        r.flow.drop()
        assertEquals(Phase.ANIMATING, r.flow.state.value.phase)
    }

    @Test fun refusedHolesAlertAndComeBack() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        r.flow.tap(h("2,-5"))
        r.flow.tap(h("0,0"))
        assertEquals(Phase.ALERT, r.flow.state.value.phase)
        assertEquals(AlertKind.ILLEGAL, r.flow.state.value.alert!!.kind)
        assertTrue(Sound.NO in r.sounds)
        // a stuck marble (the apex, boxed in at the start)
        r.flow.tap(StarBoard.APEX[0])
        assertEquals(AlertKind.STUCK, r.flow.state.value.alert!!.kind)
        advanceTimeBy(GameFlow.ALERT_MS + 10); runCurrent()
        assertEquals(Phase.SELECTED, r.flow.state.value.phase)
        assertNull(r.flow.state.value.alert)
    }

    @Test fun dragDropsOnALitHoleAndPutsBackElsewhere() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        assertTrue(r.flow.dragStart(h("2,-5")))
        assertEquals(Phase.DRAGGING, r.flow.state.value.phase)
        // move one row up (to (2,-4)): du = pitch/2, dv = row
        r.flow.dragMove(StarBoard.PITCH * 0.5f, StarBoard.ROW)
        assertEquals(h("2,-4"), r.flow.state.value.drag!!.ghost)
        // held for a long time: no timeout
        advanceTimeBy(30_000); runCurrent()
        assertEquals(Phase.DRAGGING, r.flow.state.value.phase)
        r.flow.dragEnd()
        assertEquals(Phase.ANIMATING, r.flow.state.value.phase)
        advanceTimeBy(1000); runCurrent()
        // next player: put back
        advanceUntilIdle()
        r.flow.handoffStart()
        val st = r.flow.state.value
        val any = st.movable().first()
        assertTrue(r.flow.dragStart(any))
        r.flow.dragMove(0f, 0f)
        r.flow.dragEnd()
        assertEquals(Phase.IDLE, r.flow.state.value.phase)
        assertEquals(1, r.flow.state.value.moves.size)
    }

    @Test fun hotSeatRotatesThenHandsOver() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        assertEquals(0, r.flow.state.value.facing)
        r.flow.tap(h("2,-5")); r.flow.tap(h("2,-4")); r.flow.tap(h("2,-4"))
        advanceTimeBy(GameFlow.STEP_MS + 1); runCurrent()
        assertEquals(Phase.ROTATING, r.flow.state.value.phase)
        assertEquals(3, r.flow.state.value.facing)
        advanceTimeBy(GameFlow.ROTATE_MAX_MS + 1); runCurrent()
        assertEquals(Phase.HANDOFF, r.flow.state.value.phase)
        // the board ignores taps during the hand-over
        r.flow.tap(h("-2,5"))
        assertEquals(Phase.HANDOFF, r.flow.state.value.phase)
        r.flow.handoffStart()
        assertEquals(Phase.IDLE, r.flow.state.value.phase)
        assertEquals(3, r.flow.state.value.camp)
    }

    @Test fun rotateTimingIsShortWay() {
        assertEquals(0L, GameFlow.rotateMs(0, 0))
        assertEquals(400L, GameFlow.rotateMs(0, 1))
        assertEquals(400L, GameFlow.rotateMs(0, 5))
        assertEquals(600L, GameFlow.rotateMs(0, 3))
    }

    @Test fun undoAgainstTheComputerGoesBackBeforeYourMove() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.EASY, 0)); runCurrent()
        r.flow.tap(h("2,-5")); r.flow.tap(h("2,-4")); r.flow.tap(h("2,-4"))
        advanceUntilIdle()
        assertEquals(2, r.flow.state.value.moves.size)
        assertTrue(r.flow.state.value.canUndo)
        r.flow.requestUndo()
        assertEquals(0, r.flow.state.value.moves.size)
        assertEquals(Position.start(intArrayOf(0, 3)), r.flow.state.value.position)
    }

    @Test fun hotSeatUndoAsksFirst() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        r.flow.tap(h("2,-5")); r.flow.tap(h("2,-4")); r.flow.tap(h("2,-4"))
        advanceUntilIdle()
        r.flow.requestUndo()
        assertEquals(ConfirmKind.UNDO_2P, r.flow.state.value.confirm)
        r.flow.confirm()
        assertEquals(0, r.flow.state.value.moves.size)
    }

    @Test fun newGameWithMovesAsksFirst() = runTest {
        val r = Rig(this)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        r.flow.requestNewGame()
        assertEquals(Phase.SETUP, r.flow.state.value.phase)
        r.flow.newGame(GameConfig(2, Opponent.HOTSEAT, 0)); runCurrent()
        r.flow.tap(h("2,-5")); r.flow.tap(h("2,-4")); r.flow.tap(h("2,-4"))
        advanceUntilIdle()
        r.flow.requestNewGame()
        assertEquals(ConfirmKind.NEW_GAME, r.flow.state.value.confirm)
    }

    @Test fun killedMidGameRestoresTheSamePosition() = runTest {
        val store = MemStore()
        val r = Rig(this, store)
        r.flow.newGame(GameConfig(3, Opponent.HOTSEAT, 0)); runCurrent()
        r.flow.tap(h("2,-5")); r.flow.tap(h("2,-4")); r.flow.tap(h("2,-4"))
        advanceUntilIdle()
        val before = r.flow.state.value.position
        val r2 = Rig(this, store)
        r2.flow.start(); runCurrent()
        assertEquals(before, r2.flow.state.value.position)
        assertTrue(r2.flow.state.value.restored)
        assertEquals(1, r2.flow.state.value.moves.size)
        // a bot's turn comes back to thinking (S16 -> S06)
        val r3 = Rig(this, MemStore().also { it.saved = SavedGame(GameConfig(2, Opponent.EASY, 1), emptyList()) })
        r3.flow.start(); runCurrent()
        assertEquals(Phase.THINKING, r3.flow.state.value.phase)
        r3.flow.holdThinking = false
        advanceUntilIdle()
    }

    @Test fun winningMoveEndsTheGame() = runTest {
        val r = Rig(this)
        val c = IntArray(StarBoard.SIZE) { -1 }
        for (x in StarBoard.CAMP_HOLES[3]) if (x != h("-4,5")) c[x] = 0
        c[h("-4,4")] = 0
        StarBoard.CAMP_HOLES[0].take(8).forEach { c[it] = 3 }
        r.flow.loadPosition(GameConfig(2, Opponent.EASY, 0), Position(c), 0); runCurrent()
        r.flow.tap(h("-4,4")); r.flow.tap(h("-4,5")); r.flow.tap(h("-4,5"))
        advanceTimeBy(GameFlow.STEP_MS + 1); runCurrent()
        assertEquals(Phase.RESULT, r.flow.state.value.phase)
        assertEquals(0, r.flow.state.value.result!!.winner)
        assertTrue(Sound.WIN in r.sounds)
        assertNull(r.store.saved)
    }

    @Test fun firstRunShowsWelcomeAndFirstGameIsTwoPlayerEasy() = runTest {
        val r = Rig(this, settings = Settings())
        r.flow.start()
        assertEquals(Phase.WELCOME, r.flow.state.value.phase)
        r.flow.firstGame(); runCurrent()
        val cfg = r.flow.state.value.config!!
        assertEquals(2, cfg.players); assertEquals(Opponent.EASY, cfg.opponent); assertEquals(0, cfg.first)
        assertTrue(r.settings.firstRunDone)
        assertTrue(r.flow.state.value.firstGame)
    }

    @Test fun saveCodecRoundTrip() {
        val g = SavedGame(GameConfig(6, Opponent.SMART, 4), listOf(SavedMove(3, 9), SavedMove(-1, -1)), 12345)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("v0|junk"))
        assertFalse(SaveCodec.decode("v1|5|EASY|0|0|") != null)
    }
}
