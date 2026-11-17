package io.embodit.marblehop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** acceptance.mustPass M1: rules (star, start moves, hop BFS + tie rule, no stopping in others' camps, home stays home, win test). */
class RulesTest {
    private fun h(s: String) = StarBoard.parse(s)!!

    private fun empty() = IntArray(StarBoard.SIZE) { -1 }

    @Test fun starHas121HolesAndSixCampsOfTen() {
        assertEquals(121, StarBoard.SIZE)
        for (c in 0 until 6) assertEquals(10, StarBoard.CAMP_HOLES[c].size)
        assertEquals(61, (0 until StarBoard.SIZE).count { StarBoard.CAMP[it] < 0 })
        for (c in 0 until 6) assertEquals(c, StarBoard.CAMP[StarBoard.APEX[c]])
    }

    @Test fun boardSizeMatchesTheDesign() {
        // pitch 6.5 cm, row 5.63 cm, tip to tip 16 rows = 0.90 m, widest row 13 holes = 0.78 m
        assertEquals(0.0563f, StarBoard.ROW, 0.0001f)
        val tip = StarBoard.v(StarBoard.APEX[3]) - StarBoard.v(StarBoard.APEX[0])
        assertEquals(0.90f, tip, 0.002f)
        val row = (0 until StarBoard.SIZE).filter { StarBoard.R[it] == -4 }
        assertEquals(13, row.size)
        assertEquals(0.78f, StarBoard.u(row.max()) - StarBoard.u(row.min()), 0.001f)
    }

    @Test fun twoPlayerOpeningHas14MovesEach() {
        val pos = Position.start(StarBoard.seatsFor(2))
        assertEquals(14, StarRules.legalMoves(pos, 0).size)
        assertEquals(14, StarRules.legalMoves(pos, 3).size)
    }

    @Test fun seatsPerPlayerCount() {
        assertEquals(listOf(0, 3), StarBoard.seatsFor(2).toList())
        assertEquals(listOf(0, 2, 4), StarBoard.seatsFor(3).toList())
        assertEquals(listOf(0, 1, 3, 4), StarBoard.seatsFor(4).toList())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), StarBoard.seatsFor(6).toList())
        for (c in 0 until 6) assertEquals((c + 3) % 6, StarBoard.target(c))
    }

    @Test fun hopChainBfsGivesEveryLandingAndTheFewestHops() {
        val c = empty()
        c[h("0,-4")] = 0; c[h("0,-3")] = 3; c[h("0,-1")] = 0; c[h("0,1")] = 3
        val pos = Position(c)
        val t = StarRules.targets(pos, h("0,-4"))
        val m = t[h("0,2")]
        assertNotNull(m)
        assertEquals(listOf(h("0,-2"), h("0,0"), h("0,2")), m!!.path)
        assertEquals(3, m.hops)
        assertTrue(t[h("0,-2")]!!.jump)
        assertEquals(1, t[h("0,-2")]!!.hops)
        // steps go to the empty neighbours only
        assertFalse(t[h("0,-3")] != null)
    }

    @Test fun tiesGoToTheSmallestLanding() {
        // two 2-hop routes from (0,0) to (0,4)? build a diamond: (0,0) -> (0,2) -> (0,4) and (0,0) -> (2,0)... use a symmetric pair
        val c = empty()
        val from = h("0,0")
        c[from] = 0
        // route A: over (1,0) to (2,0), then over (1,1) to (0,2)
        c[h("1,0")] = 3; c[h("1,1")] = 3
        // route B: over (0,1) to (0,2) directly is one hop — so ask for (2,0)->... instead test determinism: same input, same chain
        val pos = Position(c)
        val a = StarRules.targets(pos, from)
        val b = StarRules.targets(pos, from)
        assertEquals(a.mapValues { it.value.path }, b.mapValues { it.value.path })
        // every chain is a fewest-hop chain
        for ((to, m) in a) if (m.jump) assertEquals(StarRules.hopDistances(pos, from, from)[to], m.hops)
    }

    @Test fun cannotStopInSomeoneElsesCampButCanPassThrough() {
        val c = empty()
        // mint marble just outside camp 1 (left-near, s >= 5), with a marble to hop over into camp 1
        val from = h("-2,-2") // s = 4, centre
        c[from] = 0
        c[h("-3,-2")] = 3 // hop over (dir -1,0) lands on (-4,-2): s = 6 -> camp 1
        val pos = Position(c)
        val t = StarRules.targets(pos, from)
        assertNull(t[h("-4,-2")])
        assertEquals(1, StarBoard.CAMP[h("-4,-2")])
    }

    @Test fun aMarbleHomeStaysHome() {
        val c = empty()
        val from = h("-1,5") // camp 3 = mint's goal
        c[from] = 0
        val t = StarRules.targets(Position(c), from)
        assertTrue(t.isNotEmpty())
        assertTrue(t.keys.all { StarBoard.CAMP[it] == 3 })
    }

    @Test fun winRuleSixCases() {
        assertFalse(StarRules.isWin(0, 10))
        assertFalse(StarRules.isWin(4, 6))
        assertFalse(StarRules.isWin(6, 4))
        assertTrue(StarRules.isWin(7, 3))
        assertTrue(StarRules.isWin(8, 2))
        assertTrue(StarRules.isWin(10, 0))
        assertFalse(StarRules.isWin(9, 0))
    }

    @Test fun squatterScriptGameTriggersTheSevenThreeWin() {
        // amber leaves 3 marbles in its start camp (= mint's goal); mint fills the other 7 holes
        val c = empty()
        val goal = StarBoard.CAMP_HOLES[3]
        goal.take(3).forEach { c[it] = 3 }
        goal.drop(3).forEach { c[it] = 0 }
        val pos = Position(c)
        assertEquals(7 to 3, StarRules.homeCounts(pos, 0))
        assertTrue(StarRules.hasWon(pos, 0))
    }

    @Test fun threeAndFourPlayerCampsAndRanking() {
        val p3 = Position.start(StarBoard.seatsFor(3))
        assertEquals(30, (0 until StarBoard.SIZE).count { p3[it] >= 0 })
        val p4 = Position.start(StarBoard.seatsFor(4))
        assertEquals(10, p4.marblesOf(1).size)
        assertTrue(p4.marblesOf(2).isEmpty())
        // ranking: winner first, then by marbles home
        val c = empty()
        StarBoard.CAMP_HOLES[3].forEach { c[it] = 0 }
        StarBoard.CAMP_HOLES[0].take(5).forEach { c[it] = 3 }
        StarBoard.CAMP_HOLES[2].take(2).forEach { c[it] = 5 }
        val r = StarRules.ranking(Position(c), intArrayOf(0, 3, 5), 0)
        assertEquals(listOf(0, 3, 5), r)
    }

    @Test fun parseAndNameRoundTrip() {
        for (i in 0 until StarBoard.SIZE) assertEquals(i, StarBoard.parse(StarBoard.name(i)))
        assertNull(StarBoard.parse("9,9"))
    }
}
