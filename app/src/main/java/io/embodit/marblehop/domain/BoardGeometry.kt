package io.embodit.marblehop.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(x * x + y * y + z * z)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

/**
 * Metres of the board volume (design container.volume + spatialLayout), all from one place and unit-tested (D35 / D16).
 * Volume centre = origin, +X right, +Y up, +Z toward the user.
 *
 * ```
 * root (volume centre)
 * └ tiltRoot  at PIVOT = (0, -0.56, +0.45): the near bottom edge of the board, turned about X by the tilt
 *   └ boardRoot at (0, 0, -0.50) in tiltRoot: the board's bottom centre, turned about its normal by facing x 60°
 *     └ holes at (u, TOP, -v), marbles, markers
 * sign: upright, standing 2 cm beyond the far edge (6 mm above the face); never tilts, never turns
 * ```
 */
class BoardGeometry(val tiltDeg: Int = 75) {
    companion object {
        val VOLUME = V3(1.02f, 1.16f, 1.16f)
        val PIVOT = V3(0f, -0.56f, 0.45f)
        const val BOARD_R = 0.50f
        const val FACE_R = 0.478f
        const val THICK = 0.03f
        /** Top of the face (the rim is 4 mm proud of it). */
        const val TOP = THICK
        /** Marble Ø 4.6 cm (design 4.0, enlarged for gate B「尽可能大一点」), half sunk into its 1 cm groove. */
        const val MARBLE_R = 0.023f
        const val GROOVE_DEPTH = 0.010f
        val MARBLE_Y: Float = TOP + MARBLE_R - GROOVE_DEPTH
        const val PICK_LIFT = 0.012f
        const val DRAG_LIFT = 0.030f
        /** Added height at the top of a hop: the marble centre peaks 6 cm above the face (animation.hop). */
        const val HOP_APEX = 0.047f
        const val STEP_APEX = 0.015f
        /** Status sign 0.58 x 0.075 m (spatialLayout.sign). */
        const val SIGN_W = 0.58f
        const val SIGN_H = 0.075f
        /** Normal budget from the board bottom: thickness + marble top at the hop apex (container.volume.formula 0.11 m with Ø 4.0; 0.115 with Ø 4.6). */
        const val NORMAL_BUDGET = 0.115f
    }

    val theta: Float = (tiltDeg * PI / 180.0).toFloat()

    /** tiltRoot frame -> volume frame. */
    fun tiltToVolume(p: V3): V3 {
        val c = cos(theta); val s = sin(theta)
        return PIVOT + V3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
    }

    /** boardRoot frame (after the facing turn) -> volume frame. */
    fun boardToVolume(p: V3, facing: Int = 0): V3 {
        val a = facing * PI.toFloat() / 3f
        val c = cos(a); val s = sin(a)
        // rotation about +Y by a (same as Quat(Y, a)): x' = x c + z s, z' = -x s + z c
        val r = V3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
        return tiltToVolume(r + V3(0f, 0f, -BOARD_R))
    }

    /** A hole's centre on the face, boardRoot frame, at [lift] above the face. */
    fun hole(h: Int, lift: Float = 0f): V3 = V3(StarBoard.u(h), TOP + lift, -StarBoard.v(h))

    /** A point given in pitch units on the board plane. */
    fun plane(px: Float, py: Float, lift: Float): V3 = V3(px * StarBoard.PITCH, TOP + lift, -py * StarBoard.PITCH)

    /** Unit vectors of the board plane (u right, v far) and its normal, in volume axes, for drag projection. */
    fun planeAxes(facing: Int): Triple<V3, V3, V3> {
        val o = boardToVolume(V3(0f, 0f, 0f), facing)
        val u = boardToVolume(V3(1f, 0f, 0f), facing) - o
        val v = boardToVolume(V3(0f, 0f, -1f), facing) - o
        val n = boardToVolume(V3(0f, 1f, 0f), facing) - o
        return Triple(u, v, n)
    }

    /** Volume drag travel -> board-plane travel (metres along u, v) as the board faces now. */
    fun dragOnPlane(dx: Float, dy: Float, dz: Float, facing: Int): Pair<Float, Float> {
        // the plane axes in volume space with facing 0; the facing turn is applied by Picking.screen on the u/v result
        val (u, v, _) = planeAxes(0)
        val d = V3(dx, dy, dz)
        val du = d.dot(u); val dv = d.dot(v)
        // undo the facing turn so the result is in the board's own (unturned) frame
        val a = -facing * PI.toFloat() / 3f
        val c = cos(a); val s = sin(a)
        return (du * c - dv * s) to (du * s + dv * c)
    }

    /** Centre of the upright status sign in the volume. */
    fun signCentre(): V3 {
        val anchor = tiltToVolume(V3(0f, TOP + 0.006f, -2 * BOARD_R - 0.02f))
        return anchor + V3(0f, SIGN_H / 2f, 0f)
    }

    /** Seat pip on the rim, outside each camp's tip (boardRoot frame). */
    fun pip(camp: Int): V3 {
        val apex = StarBoard.APEX[camp]
        val u = StarBoard.u(apex); val v = StarBoard.v(apex)
        val len = sqrt(u * u + v * v)
        val r = (FACE_R + BOARD_R) / 2f
        return V3(u / len * r, TOP + 0.004f, -v / len * r)
    }

    /** Corners used by the D35 / D16 volume check: board rim × normal heights, sign corners. */
    fun checkPoints(): List<V3> {
        val pts = ArrayList<V3>()
        for (k in 0 until 72) {
            val a = k * 2 * PI.toFloat() / 72
            for (h in listOf(0f, TOP + 0.004f, NORMAL_BUDGET)) {
                pts += boardToVolume(V3(BOARD_R * cos(a), h, BOARD_R * sin(a)))
            }
        }
        val c = signCentre()
        for (dx in listOf(-SIGN_W / 2, SIGN_W / 2)) for (dy in listOf(-SIGN_H / 2, SIGN_H / 2)) pts += c + V3(dx, dy, 0f)
        return pts
    }

    fun insideVolume(p: V3, margin: Float = 0f): Boolean =
        kotlin.math.abs(p.x) <= VOLUME.x / 2 - margin && kotlin.math.abs(p.y) <= VOLUME.y / 2 - margin && kotlin.math.abs(p.z) <= VOLUME.z / 2 - margin

    /** Hop arc between two holes at time t in [0, 1] (horizontal easeInOutSine, parabolic height), boardRoot frame. */
    fun arc(a: Int, b: Int, t: Float, apex: Float): V3 {
        val e = (0.5f - 0.5f * cos(PI.toFloat() * t.coerceIn(0f, 1f)))
        val p = hole(a).lerp(hole(b), e)
        val h = 4f * apex * t * (1f - t)
        return V3(p.x, MARBLE_Y + h, p.z)
    }
}
