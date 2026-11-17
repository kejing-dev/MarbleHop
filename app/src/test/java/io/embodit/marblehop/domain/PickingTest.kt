package io.embodit.marblehop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** acceptance.mustPass M2: hit -> nearest candidate (1.5 pitch), arrow keys (±50°, any tilt / facing), snap hysteresis. */
class PickingTest {
    private fun h(s: String) = StarBoard.parse(s)!!

    @Test fun hitOnACandidateIsThatCandidate() {
        assertEquals(h("0,0"), Picking.nearest(h("0,0"), setOf(h("0,0"), h("1,0"))))
    }

    @Test fun hitNextToACandidateFindsIt() {
        assertEquals(h("1,0"), Picking.nearest(h("0,0"), setOf(h("1,0"), h("4,0"))))
        // two pitches away is out of reach (radius 1.5)
        assertNull(Picking.nearest(h("0,0"), setOf(h("2,0"))))
    }

    @Test fun neighbourDistancesAreOnePitch() {
        for (d in StarBoard.NEIGHBOUR[h("0,0")]) assertEquals(1f, Picking.distPitch(h("0,0"), d), 1e-4f)
    }

    @Test fun arrowsMapToNeighbours() {
        val all = (0 until StarBoard.SIZE).toSet()
        assertEquals(h("1,0"), Picking.nav(h("0,0"), Dir.RIGHT, all, 0f))
        assertEquals(h("-1,0"), Picking.nav(h("0,0"), Dir.LEFT, all, 0f))
        // up = the farther row; both upper neighbours are 30° off, the tie goes to the smaller index
        assertEquals(1, StarBoard.R[Picking.nav(h("0,0"), Dir.UP, all, 0f)!!])
        assertEquals(-1, StarBoard.R[Picking.nav(h("0,0"), Dir.DOWN, all, 0f)!!])
    }

    @Test fun arrowsFollowTheBoardTurnAndNeverFlipWithTilt() {
        val all = (0 until StarBoard.SIZE).toSet()
        // turned 180° (facing 3): "right" on screen is -q on the board
        assertEquals(h("-1,0"), Picking.nav(h("0,0"), Dir.RIGHT, all, 180f))
        // the tilt never enters the mapping: up stays up for all six tilts (far row is up on screen from 0° to 90°)
        for (t in Settings.TILTS) assertEquals(1, StarBoard.R[Picking.nav(h("0,0"), Dir.UP, all, 0f)!!])
    }

    @Test fun noCandidateInTheSectorMeansNoMove() {
        assertNull(Picking.nav(h("0,0"), Dir.UP, setOf(h("0,0"), h("0,-1"), h("1,-1")), 0f))
    }

    @Test fun snapHasHysteresis() {
        val lit = setOf(h("0,0"), h("1,0"))
        val (x0, y0) = Picking.screen(h("0,0"))
        // 0.45 pitch from (0,0): snaps in
        assertEquals(h("0,0"), Picking.snap(x0 + 0.45f, y0, null, lit))
        // 0.6 pitch: not in when coming from nowhere ...
        assertNull(Picking.snap(x0 + 0.6f, y0, null, setOf(h("0,0"))))
        // ... but stays once in (out at 0.75)
        assertEquals(h("0,0"), Picking.snap(x0 + 0.7f, y0, h("0,0"), setOf(h("0,0"))))
        assertNull(Picking.snap(x0 + 0.8f, y0 + 0.3f, h("0,0"), setOf(h("0,0"))))
        // a closer lit hole well inside its snap-in radius takes over
        assertEquals(h("1,0"), Picking.snap(x0 + 0.95f, y0, h("0,0"), lit))
    }
}
