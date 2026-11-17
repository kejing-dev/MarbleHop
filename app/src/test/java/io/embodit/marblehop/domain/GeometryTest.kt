package io.embodit.marblehop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan

/** acceptance.mustPass M4 (D35 / D16): every tilt keeps board, marbles, arcs and sign inside the volume; the sign never hides a marble. */
class GeometryTest {
    private val eye = V3(0f, 0.14f, 1.75f)

    @Test fun everyTiltFitsTheVolume() {
        for (t in Settings.TILTS) {
            val g = BoardGeometry(t)
            for (p in g.checkPoints()) assertTrue("tilt $t point $p outside", g.insideVolume(p))
        }
    }

    @Test fun designTableSignAndBoardCentres() {
        val g = BoardGeometry(75)
        // spatialLayout.tiltTable 75°: sign centre (y, z) ≈ (0.471, 0.214); our sign stands on its anchor (+ half height)
        assertEquals(0.214f, g.signCentre().z, 0.02f)
        assertEquals(0.509f, g.signCentre().y, 0.04f)
    }

    @Test fun signNeverHidesAMarble() {
        for (t in Settings.TILTS) {
            val g = BoardGeometry(t)
            val s = g.signCentre()
            for (h in 0 until StarBoard.SIZE) {
                val m = g.boardToVolume(V3(StarBoard.u(h), BoardGeometry.MARBLE_Y, -StarBoard.v(h)))
                if (s.z <= m.z) continue // the sign is behind this marble
                val k = (s.z - eye.z) / (m.z - eye.z)
                val x = eye.x + (m.x - eye.x) * k
                val y = eye.y + (m.y - eye.y) * k
                val inside = kotlin.math.abs(x) <= BoardGeometry.SIGN_W / 2 && kotlin.math.abs(y - s.y) <= BoardGeometry.SIGN_H / 2
                assertTrue("tilt $t hole ${StarBoard.name(h)} hidden by the sign", !inside)
            }
        }
    }

    @Test fun defaultTiltEveryCellAtLeastTwoDegrees() {
        // D17 at the default 75°: one pitch seen from the reference eye >= 2° (design tiltTable 2.16–2.69°)
        val g = BoardGeometry(75)
        var min = 99.0
        for (h in 0 until StarBoard.SIZE) {
            val d = (g.boardToVolume(g.hole(h)) - eye).length()
            val deg = Math.toDegrees(2 * atan(StarBoard.PITCH * 0.97 / 2 / d))
            if (deg < min) min = deg
        }
        assertTrue("smallest cell $min°", min >= 2.0)
    }

    @Test fun dragOnThePlaneUndoesTheFacingTurn() {
        val g = BoardGeometry(75)
        val (u, v, _) = g.planeAxes(0)
        val d = u * 0.1f
        val (du0, dv0) = g.dragOnPlane(d.x, d.y, d.z, 0)
        assertEquals(0.1f, du0, 1e-4f); assertEquals(0f, dv0, 1e-4f)
        // turned 180°: moving right on screen is -u on the board's own frame
        assertEquals(-0.1f, g.dragOnPlane(d.x, d.y, d.z, 3).first, 1e-4f)
        val far = v * 0.065f
        assertEquals(0.065f, g.dragOnPlane(far.x, far.y, far.z, 0).second, 1e-4f)
        assertEquals(1f, u.length(), 1e-4f)
    }

    @Test fun boardTurnMatchesPickingScreen() {
        // the scene turns boardRoot by facing x 60° about Y; Picking.screen turns (u, v) by the same angle:
        // after the turn, camp f's tip sits where camp 0's tip sits unturned (the near tip)
        val g = BoardGeometry(0)
        val near = g.boardToVolume(g.hole(StarBoard.APEX[0]), 0)
        val (nx, ny) = Picking.screen(StarBoard.APEX[0], 0f)
        for (f in 0 until 6) {
            val p = g.boardToVolume(g.hole(StarBoard.APEX[f]), f)
            assertEquals(near.z, p.z, 1e-3f); assertEquals(near.x, p.x, 1e-3f)
            val (sx, sy) = Picking.screen(StarBoard.APEX[f], f * 60f)
            assertEquals(nx, sx, 1e-3f); assertEquals(ny, sy, 1e-3f)
        }
    }
}
