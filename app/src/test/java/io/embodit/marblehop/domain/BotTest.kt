package io.embodit.marblehop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * acceptance.mustPass M3: smart beats easy in >= 70 % of 50 games; the naive yardstick bot beats easy in >= 40 % (B27:
 * easy must be beatable by a newcomer); each move <= 300 ms; fixed seed = same game.
 */
class BotTest {
    private enum class Kind { EASY, SMART, NAIVE }

    /** Plays a 2-player game, [a] on camp 0 and [b] on camp 3; returns the winner camp (or -1 after 600 plies). */
    private fun play(a: Kind, b: Kind, seed: Int, firstA: Boolean): Int {
        var pos = Position.start(intArrayOf(0, 3))
        val rnd = Random(seed)
        var camp = if (firstA) 0 else 3
        repeat(600) {
            val kind = if (camp == 0) a else b
            val m = when (kind) {
                Kind.EASY -> Bot.choose(pos, camp, Level.EASY, rnd)
                Kind.SMART -> Bot.choose(pos, camp, Level.SMART, rnd)
                Kind.NAIVE -> Bot.naive(pos, camp, rnd)
            }
            if (m != null) pos = pos.apply(m)
            if (StarRules.hasWon(pos, camp)) return camp
            camp = if (camp == 0) 3 else 0
        }
        return -1
    }

    private fun rate(a: Kind, b: Kind, games: Int = 50): Pair<Double, Int> {
        var wins = 0; var undecided = 0
        for (g in 0 until games) {
            val w = play(a, b, 1000 + g, firstA = g % 2 == 0)
            if (w == 0) wins++
            if (w < 0) undecided++
        }
        println("RATE $a vs $b: $wins/$games wins, $undecided undecided")
        return wins.toDouble() / games to undecided
    }

    @Test fun smartBeatsEasyAtLeast70Percent() {
        val (r, _) = rate(Kind.SMART, Kind.EASY)
        assertTrue("smart vs easy $r", r >= 0.70)
    }

    @Test fun naiveBotBeatsEasyAtLeast40Percent() {
        val (r, _) = rate(Kind.NAIVE, Kind.EASY)
        assertTrue("naive vs easy $r", r >= 0.40)
    }

    @Test fun smartFinishesItsGames() {
        val w = play(Kind.SMART, Kind.SMART, 7, true)
        assertTrue("smart self-play ended: $w", w >= 0)
    }

    @Test fun movesAreFastAndReproducible() {
        val pos = Position.start(StarBoard.seatsFor(6))
        val t0 = System.nanoTime()
        val m1 = Bot.choose(pos, 0, Level.SMART, Random(42))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("move took $ms ms", ms <= 300)
        assertEquals(Bot.choose(pos, 0, Level.EASY, Random(42)), Bot.choose(pos, 0, Level.EASY, Random(42)))
        assertEquals(m1, Bot.choose(pos, 0, Level.SMART, Random(1)))
    }

    @Test fun sixComputerSoakEndsWithAWinner() {
        var pos = Position.start(StarBoard.seatsFor(6))
        val cfg = GameConfig(6, Opponent.SMART, 0)
        var winner = -1
        var turn = 0
        val rnd = Random(3)
        while (turn < 3000 && winner < 0) {
            val camp = cfg.campAt(turn)
            val m = Bot.choose(pos, camp, if (camp % 2 == 0) Level.SMART else Level.EASY, rnd)
            if (m != null) { pos = pos.apply(m); if (StarRules.hasWon(pos, camp)) winner = camp }
            turn++
        }
        println("SOAK 6 bots: winner=$winner after $turn moves")
        assertTrue(winner >= 0)
    }
}
