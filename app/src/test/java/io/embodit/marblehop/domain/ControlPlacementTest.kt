package io.embodit.marblehop.domain

import io.embodit.marblehop.domain.ControlPlacement.ColdStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA #2 q2-1: on both cold starts (saved game = volume centred; cleared data = group further left) every corner of the
 * control window, its TabBar pill and its system bar projects inside ±39° (1° margin), at ≈ 1.5 mm/dp.
 */
class ControlPlacementTest {
    @Test fun restoredColdStartFitsTheView() {
        val out = ControlPlacement.outside(ColdStart.RESTORED)
        assertTrue("restored: $out", out.isEmpty())
    }

    @Test fun freshColdStartFitsTheView() {
        val out = ControlPlacement.outside(ColdStart.FRESH)
        assertTrue("fresh: $out", out.isEmpty())
    }

    @Test fun twelveCornersEachStart() {
        for (s in listOf(ColdStart.RESTORED, ColdStart.FRESH)) {
            val c = ControlPlacement.corners(s)
            assertEquals(12, c.size)
            val right = c.maxOf { it.hDeg }
            println("$s right edge %.1f°, top %.1f°, bottom %.1f°".format(right, c.maxOf { it.vDeg }, c.minOf { it.vDeg }))
        }
    }

    @Test fun modelReproducesTheV011Failure() {
        // the model must show what QA saw: the 560 x 540 dp window leaves the view on the restored start (≈ +44–48°)
        val c = ControlPlacement.corners(ColdStart.OLD_VOLUME, ControlPlacement.OLD_W_DP, ControlPlacement.OLD_H_DP)
        val right = c.filter { it.part == "window" }.maxOf { it.hDeg }
        assertTrue("v0.1.1 right edge $right", right > 42f)
        // the 320 dp window beside the old 1.08 m volume measured ≈ 38.7°: out of the 0.5° margin, so the volume went to 1.02 m
        assertTrue(ControlPlacement.outside(ColdStart.OLD_VOLUME, 320f, 900f).isNotEmpty())
        // and beside the 1.02 m volume the model gives the measured right edge (38.1°)
        val now = ControlPlacement.corners(ColdStart.RESTORED).filter { it.part == "window" }.maxOf { it.hDeg }
        assertEquals(38.1f, now, 0.3f)
    }

    @Test fun windowNoLowerThanFortyDegreesUnderTheEye() {
        for (s in listOf(ColdStart.RESTORED, ColdStart.FRESH)) assertTrue(ControlPlacement.corners(s).minOf { it.vDeg } >= -40f)
    }
}
