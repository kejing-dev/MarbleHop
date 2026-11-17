package io.embodit.marblehop.domain

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Panel arrow keys (input.nav). */
enum class Dir(val x: Float, val y: Float) { UP(0f, 1f), DOWN(0f, -1f), LEFT(-1f, 0f), RIGHT(1f, 0f) }

/**
 * Pure input geometry (design input.rule / nav / drag), unit-tested:
 *  - a hit on any hole resolves to the nearest candidate within 1.5 pitches (marbles you can move while picking,
 *    lit holes while choosing where to drop), so the whole board is one big target (gate B: "尽可能大一点");
 *  - arrow keys walk to the nearest candidate inside a ±50° sector of the direction as you see the board (the board's
 *    yaw is applied, tilt never flips up / down);
 *  - dragging snaps the ghost to the nearest lit hole with hysteresis: in at 0.5 pitch, out at 0.75 pitch.
 */
object Picking {
    const val PICK_RADIUS = 1.5f
    const val SNAP_IN = 0.5f
    const val SNAP_OUT = 0.75f
    const val SECTOR_DEG = 50f

    /** Hole position in pitch units on the board plane, rotated by the board's yaw (degrees). */
    fun screen(h: Int, yawDeg: Float = 0f): Pair<Float, Float> {
        val u = StarBoard.Q[h] + StarBoard.R[h] / 2f
        val v = StarBoard.R[h] * 0.8660254f
        val a = (yawDeg * PI / 180.0)
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return (u * c - v * s) to (u * s + v * c)
    }

    fun distPitch(a: Int, b: Int): Float {
        val (ax, ay) = screen(a); val (bx, by) = screen(b)
        return hypot(ax - bx, ay - by)
    }

    /** The candidate nearest to the hit hole within [PICK_RADIUS] pitches; the hit hole itself wins ties. */
    fun nearest(hit: Int, candidates: Collection<Int>, radius: Float = PICK_RADIUS): Int? {
        if (hit in candidates) return hit
        return candidates.map { it to distPitch(hit, it) }.filter { it.second <= radius + 1e-4f }
            .minWithOrNull(compareBy<Pair<Int, Float>> { it.second }.thenBy { it.first })?.first
    }

    /** Arrow key: nearest candidate inside the ±50° sector of [dir] as seen with the board turned by [yawDeg]. */
    fun nav(from: Int, dir: Dir, candidates: Collection<Int>, yawDeg: Float): Int? {
        val (fx, fy) = screen(from, yawDeg)
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (c in candidates) {
            if (c == from) continue
            val (cx, cy) = screen(c, yawDeg)
            val dx = cx - fx; val dy = cy - fy
            val d = hypot(dx, dy)
            if (d < 1e-4f) continue
            val cosA = ((dx * dir.x + dy * dir.y) / d).coerceIn(-1f, 1f)
            val ang = acos(cosA) * 180f / PI.toFloat()
            if (ang > SECTOR_DEG + 1e-3f) continue
            if (d < bestD - 1e-4f || (kotlin.math.abs(d - bestD) <= 1e-4f && c < (best ?: Int.MAX_VALUE))) { best = c; bestD = d }
        }
        return best
    }

    /**
     * Drag snapping. [px], [py] = where the held marble is, in pitch units on the (unrotated) board plane; [current] =
     * the hole the ghost sits in now. Returns the hole the ghost should sit in (or null: no ghost, release = put back).
     */
    fun snap(px: Float, py: Float, current: Int?, lit: Collection<Int>): Int? {
        fun d(h: Int): Float { val (x, y) = screen(h); return hypot(x - px, y - py) }
        if (current != null && current in lit && d(current) <= SNAP_OUT) {
            // stay unless another lit hole is clearly inside the snap-in radius and closer
            val other = lit.filter { it != current && d(it) <= SNAP_IN }.minByOrNull { d(it) }
            return if (other != null && d(other) < d(current)) other else current
        }
        return lit.filter { d(it) <= SNAP_IN }.minWithOrNull(compareBy<Int> { d(it) }.thenBy { it })
    }
}
