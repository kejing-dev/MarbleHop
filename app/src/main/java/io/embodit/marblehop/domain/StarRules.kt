package io.embodit.marblehop.domain

import io.embodit.marblehop.domain.StarBoard.CAMP
import io.embodit.marblehop.domain.StarBoard.NEIGHBOUR
import io.embodit.marblehop.domain.StarBoard.SIZE

/**
 * One move: the marble at [from] ends at [to] through [path] (every landing in order, [to] last). A step has
 * `path == [to]` and [jump] false; a hop chain has one landing per hop.
 */
data class Move(val from: Int, val to: Int, val path: List<Int>, val jump: Boolean) {
    val hops: Int get() = if (jump) path.size else 0
    val code: String get() = "${StarBoard.name(from)}>${StarBoard.name(to)}"
}

/** The board: occupant camp per hole, -1 when empty. Immutable; [apply] returns a new one. */
class Position(private val cells: IntArray) {
    operator fun get(i: Int): Int = cells[i]
    fun empty(i: Int): Boolean = cells[i] < 0
    fun marblesOf(camp: Int): List<Int> = (0 until SIZE).filter { cells[it] == camp }
    fun copyCells(): IntArray = cells.copyOf()

    fun apply(m: Move): Position {
        require(cells[m.from] >= 0 && cells[m.to] < 0) { "bad move ${m.code}" }
        val c = cells.copyOf()
        c[m.to] = c[m.from]
        c[m.from] = -1
        return Position(c)
    }

    override fun equals(other: Any?): Boolean = other is Position && other.cells.contentEquals(cells)
    override fun hashCode(): Int = cells.contentHashCode()

    companion object {
        /** Start position: every seated camp full of its own marbles. */
        fun start(seats: IntArray): Position {
            val c = IntArray(SIZE) { -1 }
            for (camp in seats) for (h in StarBoard.CAMP_HOLES[camp]) c[h] = camp
            return Position(c)
        }
    }
}

/**
 * domain/rules (design mustHave.rulesEnforced, gameSpec.specialRules). Pure Kotlin, JVM unit-tested.
 *
 * - a step goes to an empty neighbour; a hop jumps one adjacent marble into the empty hole right behind it, and hops
 *   chain as far as they go; once you hop you can't step, and a turn never lands twice on the same hole;
 * - other people's camps (not your start camp, not your goal) can be passed through but not stopped in;
 * - a marble already in its goal camp only moves inside the goal camp;
 * - the chain shown and animated is the fewest hops; ties go to the smallest hole index at each landing.
 */
object StarRules {
    /** Holes the marble at [from] could stop on, with the chain to each (all lit at once: the turn is atomic). */
    fun targets(pos: Position, from: Int): Map<Int, Move> {
        val camp = pos[from]
        if (camp < 0) return emptyMap()
        val goal = StarBoard.target(camp)
        val home = CAMP[from] == goal
        fun canStop(h: Int): Boolean = if (home) CAMP[h] == goal else (CAMP[h] < 0 || CAMP[h] == camp || CAMP[h] == goal)

        val out = LinkedHashMap<Int, Move>()
        for (d in 0 until 6) {
            val n = NEIGHBOUR[from][d]
            if (n >= 0 && pos.empty(n) && canStop(n)) out[n] = Move(from, n, listOf(n), jump = false)
        }
        val distS = hopDistances(pos, from, from)
        for (h in 0 until SIZE) {
            if (h == from || distS[h] <= 0 || out.containsKey(h) || !canStop(h)) continue
            out[h] = Move(from, h, chain(pos, from, h, distS), jump = true)
        }
        return out
    }

    /** BFS over hop landings with the moving marble lifted from [lifted]: hops from [start] to every hole, -1 = none. */
    fun hopDistances(pos: Position, lifted: Int, start: Int): IntArray {
        val dist = IntArray(SIZE) { -1 }
        dist[start] = 0
        val queue = ArrayDeque<Int>()
        queue += start
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (d in 0 until 6) {
                val over = NEIGHBOUR[x][d]
                if (over < 0 || over == lifted || pos.empty(over)) continue
                val land = NEIGHBOUR[over][d]
                if (land < 0 || dist[land] >= 0) continue
                if (!(pos.empty(land) || land == lifted)) continue
                dist[land] = dist[x] + 1
                queue += land
            }
        }
        return dist
    }

    /** Fewest-hop chain from [from] to [to]; at each landing the smallest index that still lies on a shortest chain. */
    fun chain(pos: Position, from: Int, to: Int, distS: IntArray = hopDistances(pos, from, from)): List<Int> {
        val distT = hopDistances(pos, from, to)
        val path = ArrayList<Int>()
        var x = from
        val total = distS[to]
        for (k in 1..total) {
            var best = -1
            for (d in 0 until 6) {
                val over = NEIGHBOUR[x][d]
                if (over < 0 || over == from || pos.empty(over)) continue
                val land = NEIGHBOUR[over][d]
                if (land < 0 || distS[land] != k || distT[land] != total - k) continue
                if (best < 0 || land < best) best = land
            }
            check(best >= 0) { "no chain ${StarBoard.name(from)} -> ${StarBoard.name(to)}" }
            path += best
            x = best
        }
        return path
    }

    fun legalMoves(pos: Position, camp: Int): List<Move> = pos.marblesOf(camp).flatMap { targets(pos, it).values }

    fun canMove(pos: Position, from: Int): Boolean = targets(pos, from).isNotEmpty()

    /** a = own marbles in the goal camp, k = goal holes held by others (gameSpec.specialRules). */
    fun homeCounts(pos: Position, camp: Int): Pair<Int, Int> {
        val goal = StarBoard.target(camp)
        var a = 0; var k = 0
        for (h in StarBoard.CAMP_HOLES[goal]) when (pos[h]) {
            camp -> a++
            -1 -> Unit
            else -> k++
        }
        return a to k
    }

    /** Win ⇔ a = 10, or a >= 7 and a + k = 10 (the squatter rule; six unit-tested cases). */
    fun isWin(a: Int, k: Int): Boolean = a == 10 || (a >= 7 && a + k == 10)

    fun hasWon(pos: Position, camp: Int): Boolean = homeCounts(pos, camp).let { (a, k) -> isWin(a, k) }

    /** How far a camp still has to go: total hex distance of its marbles to the goal apex. */
    fun remaining(pos: Position, camp: Int): Int {
        val apex = StarBoard.APEX[StarBoard.target(camp)]
        return pos.marblesOf(camp).sumOf { StarBoard.dist(it, apex) }
    }

    /** Places for the result page: the winner first, then by marbles home, then by distance left. */
    fun ranking(pos: Position, seats: IntArray, winner: Int?): List<Int> =
        seats.sortedWith(compareBy<Int>({ if (it == winner) 0 else 1 }, { -homeCounts(pos, it).first }, { remaining(pos, it) }))
}
