package io.embodit.marblehop.domain

import kotlin.math.atan

/**
 * v0.1.2 (QA #2 q2-1): where the system puts the control window on a cold start, and the check that every corner of it
 * stays inside the default view (±39°, picture = perspective projection, the way QA measures).
 *
 * Shared Space gives the app no say in the distance or the side gap: the window opens with Placement.right(board volume)
 * (offset can't be negative, pico-cli-feedback「Placement.right(volume)…」), and the system puts the volume itself in
 * front of the head. Two cold starts were seen (QA #2, emulator-5562):
 *  - with a saved game (force-stop, relaunch): the VOLUME is centred (board centre +1.2°), the window hangs off its right
 *    face. v0.1.1's 560 dp window: left edge +27.9°, right edge ≈ +44–48° (cold-rerun-1/2/3).
 *  - cleared data (first start): the whole group sits about 10° further left (board centre −10°).
 So the only thing the app controls is the window's own size. Model (picture = 2560 px for ±39°, f = 1580 px; calibrated
 * on QA cold-rerun-3.png and on v0.1.2 cold starts, emulator-5560 2026-11-18, logcat size(640, 1800) px @ density 320):
 *  - eye at the origin looking −z, head level; ≈ 1.5 mm/dp at ≈ 1.95 m: vertically 7.7e-4 tan per dp (540 dp spanned
 *    0.411, 900 dp spanned 0.690); horizontally the window is turned toward the eye and reads wider, 8.8e-4 tan per dp;
 *  - a Planar window is never narrower than 320 dp (asked for 280, the system opened 320: logcat size(640, …));
 *  - the window's left edge = the volume's right face + the system gap: tan 0.530 (27.9°) for the 1.08 m volume and
 *    tan 0.504 (26.7°) for the 1.02 m volume, whenever the volume is centred (QA saved-game starts; v0.1.2 cleared-data
 *    and saved-game starts on emulator-5560 alike). 320 dp then ends at tan 0.785 = 38.1° (measured, 3 + 1 starts);
 *  - the TabBar pill (≈ 216 x 88 dp, centred over the top edge) and the system controls (≈ 160 x 60 dp, centred under
 *    the bottom edge) count as part of the window.
 */
object ControlPlacement {
    /** v0.1.2 control window, fixed size (min = max): the narrowest Planar window (320 dp), 640 dp tall (6-seat game page fits; short pages stay under the 20 % inner-gap rule), beside the 1.02 m volume. */
    const val WINDOW_W_DP = 320f
    const val WINDOW_H_DP = 640f
    /** v0.1.1 window (QA #2 q2-1 failed). */
    const val OLD_W_DP = 560f
    const val OLD_H_DP = 540f

    const val TAN_PER_DP_V = 7.7e-4f
    const val TAN_PER_DP_H = 8.8e-4f
    const val HALF_VIEW_DEG = 39f
    /** Keep 0.5° inside the view edge (the 3 + 1 measured starts landed within ±0.1° of each other). */
    const val MARGIN_DEG = 0.5f

    const val TABBAR_W_DP = 216f
    const val TABBAR_H_DP = 88f
    const val BAR_W_DP = 160f
    const val BAR_H_DP = 60f

    /** Window left edge (tan) when the 1.02 m volume is centred (measured 26.7°; 27.9° with the old 1.08 m volume). */
    val LEFT_TAN_RESTORED: Float = 0.504f
    val LEFT_TAN_OLD_VOLUME: Float = tanDeg(27.9f)
    /** First start (cleared data): the group sat ≈ 10° further left; QA saw the v0.1.1 left edge at ≈ +18.2°. */
    val LEFT_TAN_FRESH: Float = tanDeg(18.2f)
    /** Window vertical centre (tan): the 900 dp window spanned +0.287 … −0.403 (the 540 dp one +0.139 … −0.272). */
    const val CENTRE_TAN_V = (0.287f - 0.403f) / 2f

    enum class ColdStart(val leftTan: Float) { RESTORED(LEFT_TAN_RESTORED), FRESH(LEFT_TAN_FRESH), OLD_VOLUME(LEFT_TAN_OLD_VOLUME) }

    data class Corner(val part: String, val hDeg: Float, val vDeg: Float)

    /**
     * Every corner of the window, its TabBar pill and its system bar as (horizontal, vertical) picture angles.
     * The vertical centre stays where the system put the 540 dp window (it grows both ways).
     */
    fun corners(start: ColdStart, wDp: Float = WINDOW_W_DP, hDp: Float = WINDOW_H_DP): List<Corner> {
        val kh = TAN_PER_DP_H; val kv = TAN_PER_DP_V
        val left = start.leftTan
        val right = left + wDp * kh
        val cx = (left + right) / 2f
        val top = CENTRE_TAN_V + hDp * kv / 2f
        val bottom = CENTRE_TAN_V - hDp * kv / 2f
        val rects = listOf(
            Triple("window", left to right, bottom to top),
            Triple("tabbar", (cx - TABBAR_W_DP * kh / 2f) to (cx + TABBAR_W_DP * kh / 2f), top to (top + TABBAR_H_DP * kv)),
            Triple("systemBar", (cx - BAR_W_DP * kh / 2f) to (cx + BAR_W_DP * kh / 2f), (bottom - BAR_H_DP * kv) to bottom),
        )
        // 4 corners each: window frame, TabBar pill, system bar (12 in all)
        return rects.flatMap { (name, h, v) ->
            listOf(h.first, h.second).flatMap { x -> listOf(v.first, v.second).map { y -> Corner(name, atanDeg(x), atanDeg(y)) } }
        }
    }

    /** Corners outside ±([HALF_VIEW_DEG] − [MARGIN_DEG]); empty = the whole window is in the default view. */
    fun outside(start: ColdStart, wDp: Float = WINDOW_W_DP, hDp: Float = WINDOW_H_DP): List<Corner> {
        val lim = HALF_VIEW_DEG - MARGIN_DEG
        return corners(start, wDp, hDp).filter { kotlin.math.abs(it.hDeg) > lim || kotlin.math.abs(it.vDeg) > lim }
    }

    private fun tanDeg(d: Float): Float = kotlin.math.tan(Math.toRadians(d.toDouble())).toFloat()
    private fun atanDeg(t: Float): Float = Math.toDegrees(atan(t.toDouble())).toFloat()
}
