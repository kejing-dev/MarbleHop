package io.embodit.marblehop.domain

import kotlin.random.Random

/** Computer levels (gameSpec.ai): 入门 Easy and 聪明 Smart. */
enum class Level { EASY, SMART }

/**
 * Greedy one-ply computer (gameSpec.ai.kind; multi-seat games only look at their own score, a simplified max-n).
 *
 * Score of a position for a camp (gameSpec.ai.eval, tuned in BotTest): how far each marble still is from the goal
 * tip, measured ALONG the line from its start tip to its goal tip, plus 0.3 x how far it strays sideways from that
 * line, plus 0.2 x the straggler's distance (in pitches, lower is better). Plain hex distance or straight-line distance
 * made a deterministic greedy lose to the randomised easy bot (11 / 50, 29 / 50); the axis measure wins 50 / 50.
 *
 * Easy: 60 % a random pick among the five best, 40 % a random forward move. Smart: the best. Same seed, same game.
 */
object Bot {
    /** Remaining distance of one marble at [h] for [camp], pitches (axis + 0.3 x sideways). */
    fun distance(h: Int, camp: Int): Double {
        val from = StarBoard.APEX[camp]
        val to = StarBoard.APEX[StarBoard.target(camp)]
        val ax = (StarBoard.u(to) - StarBoard.u(from)).toDouble(); val ay = (StarBoard.v(to) - StarBoard.v(from)).toDouble()
        val len = Math.hypot(ax, ay)
        val dx = (StarBoard.u(to) - StarBoard.u(h)).toDouble(); val dy = (StarBoard.v(to) - StarBoard.v(h)).toDouble()
        val along = (dx * ax + dy * ay) / len
        val side = Math.abs(dx * ay - dy * ax) / len
        return (along + 0.3 * side) / StarBoard.PITCH
    }

    fun score(pos: Position, camp: Int): Double {
        var sum = 0.0; var worst = 0.0
        for (h in 0 until StarBoard.SIZE) {
            if (pos[h] != camp) continue
            val d = distance(h, camp)
            sum += d
            if (d > worst) worst = d
        }
        return -sum - 0.2 * worst
    }

    /** How much closer to the goal the moved marble gets (pitches). */
    fun progress(m: Move, camp: Int): Double = distance(m.from, camp) - distance(m.to, camp)

    fun choose(pos: Position, camp: Int, level: Level, random: Random, cancelled: () -> Boolean = { false }): Move? {
        val moves = StarRules.legalMoves(pos, camp)
        if (moves.isEmpty()) return null
        val scored = ArrayList<Pair<Move, Double>>(moves.size)
        for (m in moves) {
            if (cancelled()) return null
            val next = pos.apply(m)
            // a move that wins outright beats everything
            scored += m to (if (StarRules.hasWon(next, camp)) 1e9 else score(next, camp))
        }
        // stable order first (index order), then by score: the random picks below stay reproducible with a seed
        scored.sortWith(compareByDescending<Pair<Move, Double>> { it.second }.thenBy { it.first.from }.thenBy { it.first.to })
        return when (level) {
            Level.SMART -> scored.first().first
            Level.EASY -> {
                if (scored.first().second >= 1e9) return scored.first().first
                if (random.nextDouble() < 0.6) scored[random.nextInt(minOf(5, scored.size))].first
                else {
                    val forward = moves.filter { progress(it, camp) > 0.01 }
                    (forward.ifEmpty { moves }).let { it[random.nextInt(it.size)] }
                }
            }
        }
    }

    /**
     * The acceptance yardstick bot (gameSpec.ai.baselineBot): takes the single step or single hop with the largest net
     * advance; never plans a chain, never looks at stragglers. Easy must lose to it often enough (>= 40 %, B27).
     */
    fun naive(pos: Position, camp: Int, random: Random): Move? {
        val all = StarRules.legalMoves(pos, camp)
        val moves = all.filter { it.path.size == 1 }
        if (moves.isEmpty()) return all.firstOrNull()
        val best = moves.maxOf { progress(it, camp) }
        val top = moves.filter { progress(it, camp) >= best - 1e-6 }
        return top[random.nextInt(top.size)]
    }
}
