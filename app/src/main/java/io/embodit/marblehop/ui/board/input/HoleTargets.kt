package io.embodit.marblehop.ui.board.input

import com.pico.spatial.core.ecs.Entity
import io.embodit.marblehop.domain.StarBoard

/** Entity names, from one place only (board-input README: never build names inside `Entity().apply {}`). */
object BoardNames {
    fun hole(i: Int) = "hole:$i"
    fun groove(i: Int) = "groove:$i"
    fun marble(i: Int) = "marble:$i"
}

/**
 * What a pinch / trigger / gaze can land on, resolved by the factory board-input template (profile D50): the 121 hole
 * holders `hole:<i>` (collider + Interactable + system HoverEffect, tiling the whole face) with their visible groove
 * disc `groove:<i>`, and the marbles `marble:<i>` (renamed to the hole they sit in on every update). Anything else
 * (slab, face, rim, pips, sign, ghost, markers) is not a target. GameFlow then picks the nearest candidate to that hole.
 */
object HoleTargets : TargetResolver<Int> {
    private val PREFIXES = listOf("hole:", "groove:", "marble:")

    /** Pure name rule, unit-tested: entity name -> hole index, or null (out of range = null, so a naming slip shows). */
    fun holeOf(name: String?): Int? {
        if (name == null) return null
        for (p in PREFIXES) if (name.startsWith(p)) return name.removePrefix(p).toIntOrNull()?.takeIf { it in 0 until StarBoard.SIZE }
        return null
    }

    override fun resolve(entity: Entity): Int? = holeOf(runCatching { entity.getName() }.getOrNull())
}
