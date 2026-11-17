package io.embodit.marblehop.domain

/** Who you play against (gameSpec.seats): the easy computer, the smart computer, or everyone on this headset. */
enum class Opponent { EASY, SMART, HOTSEAT }

enum class Language { SYSTEM, ZH, EN }

/**
 * One game's setup. [players] 2 / 3 / 4 / 6; against the computer you are camp 0 (mint) and every other seat is a
 * computer of the chosen level; hot-seat makes every seat human. [first] = index into [seats] of who starts.
 */
data class GameConfig(val players: Int = 2, val opponent: Opponent = Opponent.EASY, val first: Int = 0) {
    val seats: IntArray get() = StarBoard.seatsFor(players)
    fun isHuman(camp: Int): Boolean = opponent == Opponent.HOTSEAT || camp == 0
    val level: Level get() = if (opponent == Opponent.SMART) Level.SMART else Level.EASY
    val hotSeat: Boolean get() = opponent == Opponent.HOTSEAT
    /** Camp to move after [turns] moves (seats take turns clockwise, starting at [first]). */
    fun campAt(turns: Int): Int = seats[(first + turns) % seats.size]
}

/** A move on disk: from and to only; the chain is recomputed by the rules on replay. A pass is (-1, -1). */
data class SavedMove(val from: Int, val to: Int)

/** What a game looks like on disk (files/game.txt). */
data class SavedGame(val config: GameConfig, val moves: List<SavedMove>, val elapsedMs: Long = 0)

/** App settings (SharedPreferences). volume 1..3 = 小 / 中 / 大; [muted] is the 静音 choice (design D15: first segment). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val volume: Int = 3,
    val muted: Boolean = false,
    val tilt: Int = 75,
    val firstRunDone: Boolean = false,
    val coachDone: Boolean = false,
    val lastPlayers: Int = 2,
    val lastOpponent: Opponent = Opponent.EASY,
) {
    companion object {
        /** spatialLayout.tilt: the five angles the user named + the default 75°. */
        val TILTS = listOf(0, 30, 45, 60, 75, 90)
        /** Volume percent per level (index 1..3); same three steps as the board-game template exemplar (§15.2). */
        val VOLUME_PERCENT = intArrayOf(0, 35, 65, 100)
    }
}

/** Line codec: `v1|players|opponent|first|elapsedMs|from>to from>to ...` (hole indices). */
object SaveCodec {
    fun encode(g: SavedGame): String = listOf(
        "v1",
        g.config.players.toString(),
        g.config.opponent.name,
        g.config.first.toString(),
        g.elapsedMs.toString(),
        g.moves.joinToString(" ") { "${it.from}>${it.to}" },
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 6 || f[0] != "v1") return null
        val players = f[1].toInt()
        if (players !in StarBoard.PLAYER_COUNTS) return null
        val cfg = GameConfig(players, Opponent.valueOf(f[2]), f[3].toInt().coerceIn(0, players - 1))
        val moves = if (f[5].isBlank()) emptyList() else f[5].split(" ").map { m -> m.split(">").let { SavedMove(it[0].toInt(), it[1].toInt()) } }
        SavedGame(cfg, moves, f[4].toLong())
    }.getOrNull()
}

/** Replays saved moves through the rules; null if any of them is not legal (a corrupt or foreign save). */
fun replay(config: GameConfig, saved: List<SavedMove>): Pair<Position, List<Move>>? {
    var pos = Position.start(config.seats)
    val out = ArrayList<Move>()
    for ((i, s) in saved.withIndex()) {
        if (s.from < 0) { out += PASS; continue }
        if (pos[s.from] != config.campAt(i)) return null
        val m = StarRules.targets(pos, s.from)[s.to] ?: return null
        pos = pos.apply(m)
        out += m
    }
    return pos to out
}

/** A seat with no legal move passes (gameSpec.specialRules: skipped and announced on the sign). */
val PASS = Move(-1, -1, emptyList(), jump = false)
