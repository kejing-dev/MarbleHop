package io.embodit.marblehop.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The 121-hole star in axial coordinates (design gameSpec.coords): (q, r), s = -q - r. The star is the union of the
 * two big triangles q, r, s <= 4 and q, r, s >= -4. Holes are indexed 0..120 sorted by r, then q (near row first),
 * so "lexicographically smallest" in the rules means smallest index.
 *
 * Camps clockwise from the near one: 0 near (r <= -5) · 1 left-near (s >= 5) · 2 left-far (q <= -5) · 3 far (r >= 5)
 * · 4 right-far (s <= -5) · 5 right-near (q >= 5). Everyone's goal is the camp straight across (+3). Holes in the
 * central hexagon belong to no camp (-1).
 */
object StarBoard {
    const val SIZE = 121
    const val CAMPS = 6

    /** Hole pitch (centre to centre of neighbours) and row pitch, metres (gameSpec.board: 6.5 cm, 5.63 cm). */
    const val PITCH = 0.065f
    val ROW: Float = PITCH * sqrt(3f) / 2f

    val Q: IntArray
    val R: IntArray
    private val index = HashMap<Long, Int>()

    init {
        val cells = ArrayList<Pair<Int, Int>>()
        for (r in -8..8) for (q in -8..8) {
            val s = -q - r
            val a = q <= 4 && r <= 4 && s <= 4
            val b = q >= -4 && r >= -4 && s >= -4
            if (a || b) cells += q to r
        }
        check(cells.size == SIZE) { "star has ${cells.size} holes" }
        Q = IntArray(SIZE) { cells[it].first }
        R = IntArray(SIZE) { cells[it].second }
        for (i in 0 until SIZE) index[key(Q[i], R[i])] = i
    }

    private fun key(q: Int, r: Int): Long = (q.toLong() shl 32) xor (r.toLong() and 0xffffffffL)

    fun at(q: Int, r: Int): Int? = index[key(q, r)]

    fun s(i: Int): Int = -Q[i] - R[i]

    /** The six neighbour directions in axial coordinates. */
    val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, -1), intArrayOf(-1, 1))

    /** neighbour[i][d] = hole one step in direction d, or -1. */
    val NEIGHBOUR: Array<IntArray> = Array(SIZE) { i -> IntArray(6) { d -> at(Q[i] + DIRS[d][0], R[i] + DIRS[d][1]) ?: -1 } }

    /** Camp of a hole (0..5) or -1 for the centre. */
    val CAMP: IntArray = IntArray(SIZE) { i ->
        val q = Q[i]; val r = R[i]; val s = -q - r
        when {
            r <= -5 -> 0
            s >= 5 -> 1
            q <= -5 -> 2
            r >= 5 -> 3
            s <= -5 -> 4
            q >= 5 -> 5
            else -> -1
        }
    }

    val CAMP_HOLES: Array<IntArray> = Array(CAMPS) { c -> (0 until SIZE).filter { CAMP[it] == c }.toIntArray() }

    /** The tip (apex) hole of each camp: the hole of the camp farthest from the centre. */
    val APEX: IntArray = IntArray(CAMPS) { c -> CAMP_HOLES[c].maxBy { dist(it, at(0, 0)!!) } }

    fun target(camp: Int): Int = (camp + 3) % CAMPS

    /** Hex distance between two holes. */
    fun dist(a: Int, b: Int): Int {
        val dq = Q[a] - Q[b]; val dr = R[a] - R[b]
        return max(abs(dq), max(abs(dr), abs(dq + dr)))
    }

    /** Board-plane position of a hole, metres: u to the right, v from near to far (gameSpec.coords). */
    fun u(i: Int, pitch: Float = PITCH): Float = pitch * (Q[i] + R[i] / 2f)
    fun v(i: Int, pitch: Float = PITCH): Float = pitch * sqrt(3f) / 2f * R[i]

    /** Seats (camps) for 2 / 3 / 4 / 6 players (gameSpec.coords). */
    fun seatsFor(players: Int): IntArray = when (players) {
        2 -> intArrayOf(0, 3)
        3 -> intArrayOf(0, 2, 4)
        4 -> intArrayOf(0, 1, 3, 4)
        6 -> intArrayOf(0, 1, 2, 3, 4, 5)
        else -> throw IllegalArgumentException("players $players")
    }

    val PLAYER_COUNTS = listOf(2, 3, 4, 6)

    /** Name for logs and debug commands: "q,r". */
    fun name(i: Int): String = "${Q[i]},${R[i]}"

    fun parse(s: String): Int? {
        val f = s.trim().split(",")
        if (f.size != 2) return null
        val q = f[0].trim().toIntOrNull() ?: return null
        val r = f[1].trim().toIntOrNull() ?: return null
        return at(q, r)
    }
}
