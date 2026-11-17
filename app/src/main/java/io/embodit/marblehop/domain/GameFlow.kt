package io.embodit.marblehop.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S11 / S12 / S14 are window pages). */
enum class Phase(val id: String) {
    LOADING("S00"), WELCOME("S01"), SETUP("S02"), IDLE("S03"), SELECTED("S04"), PREVIEW("S19"), DRAGGING("S20"),
    ANIMATING("S05"), THINKING("S06"), ALERT("S08"), RESULT("S09"), ROTATING("S17"), HANDOFF("S18"),
}

enum class Sound { PICK, PLACE, HOP, BACK, NO, HOME, WIN, LOSE, EDGE }

enum class AlertKind { ILLEGAL, STUCK, CAMP }

data class Alert(val kind: AlertKind, val hole: Int, val at: Long, val back: Phase)

/** A committed move being animated (the position already holds the result: commit first, animate after). */
data class Anim(val move: Move, val camp: Int, val startedAt: Long) {
    val durationMs: Long get() = GameFlow.durationOf(move)
}

/** The marble held in a drag: where it started, where it is now (pitch units, board plane), and the ghost's hole. */
data class Drag(val from: Int, val px: Float, val py: Float, val ghost: Int?)

enum class ConfirmKind { NEW_GAME, UNDO_2P, EXIT }

data class GameResult(val winner: Int, val ranking: List<Int>, val moves: Int, val longestChain: Int, val elapsedMs: Long)

/** The one state every surface reads (board scene, control window, status sign, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val position: Position? = null,
    val moves: List<Move> = emptyList(),
    val selected: Int? = null,
    val preview: Int? = null,
    val targets: Map<Int, Move> = emptyMap(),
    val drag: Drag? = null,
    val anim: Anim? = null,
    val alert: Alert? = null,
    val result: GameResult? = null,
    val confirm: ConfirmKind? = null,
    /** The camp the board is turned to (its camp sits on the near edge). */
    val facing: Int = 0,
    val rotateStartedAt: Long = 0,
    val rotateFrom: Int = 0,
    val restored: Boolean = false,
    val restoredAt: Long = 0,
    val firstGame: Boolean = false,
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val passed: Int? = null,
    /** Debug freeze: the animation stops this many ms after its start. */
    val freezeMs: Long? = null,
    val setupReturn: Boolean = false,
) {
    val turn: Int get() = moves.size
    val camp: Int get() = config?.campAt(turn) ?: 0
    val humansTurn: Boolean get() = config?.isHuman(camp) == true
    val inGame: Boolean get() = config != null && result == null && phase !in setOf(Phase.LOADING, Phase.WELCOME, Phase.SETUP)
    val canUndo: Boolean get() = config != null && result == null && phase in setOf(Phase.IDLE, Phase.SELECTED, Phase.PREVIEW, Phase.HANDOFF, Phase.ALERT) && when {
        config.hotSeat -> moves.any { it.from >= 0 }
        else -> moves.indices.any { config.campAt(it) == 0 && moves[it].from >= 0 }
    }
    val canDrop: Boolean get() = humansTurn && (phase == Phase.PREVIEW || (phase == Phase.SELECTED && targets.isNotEmpty()))
    /** Marbles the player to move can move (the picking candidates). */
    fun movable(): Set<Int> {
        val p = position ?: return emptySet()
        return p.marblesOf(camp).filter { StarRules.canMove(p, it) }.toSet()
    }
    /** Longest hop chain so far for a camp. */
    fun longest(campId: Int): Int = moves.withIndex().filter { config?.campAt(it.index) == campId }.maxOfOrNull { it.value.hops } ?: 0
}

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Turn sequencing (rules - state - render: this is the middle layer). Main-thread confined: every public call
 * and every coroutine it launches runs on [scope]'s dispatcher; only the computer runs on [engineDispatcher].
 * Debug commands call the same functions the board and the window call (build.md: no shortcuts past the input path).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound, Int?) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    seed: Int = 20261116,
) {
    companion object {
        const val STEP_MS = 220L
        const val HOP_MS = 260L
        const val HOP_GAP_MS = 60L
        const val MIN_THINK_MS = 400L
        const val ALERT_MS = 1200L
        const val ROTATE_STEP_MS = 400L
        const val ROTATE_MAX_MS = 600L
        const val RESTORED_MS = 1500L

        fun durationOf(m: Move): Long = if (!m.jump) STEP_MS else m.hops * HOP_MS + (m.hops - 1) * HOP_GAP_MS

        /** Turning from camp [a] to camp [b] the short way: 60° steps, 400 ms each, 600 ms at most (S17). */
        fun rotateMs(a: Int, b: Int): Long {
            val d = ((b - a) % 6 + 6) % 6
            val steps = minOf(d, 6 - d)
            return minOf(steps * ROTATE_STEP_MS, ROTATE_MAX_MS)
        }
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private val random = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** Debug: keep the current alert on screen until released, for screenshots. */
    var holdAlert: Boolean = false

    private var animJob: Job? = null
    private var thinkJob: Job? = null
    private var alertJob: Job? = null
    private var rotateJob: Job? = null

    private fun set(next: PlayState) { _state.value = next }

    // ---- lifecycle ----

    fun start() {
        val st = settings()
        if (!st.firstRunDone) {
            // S01: a 2-player board is laid out behind the welcome card
            val cfg = GameConfig(2, Opponent.EASY, 0)
            set(PlayState(phase = Phase.WELCOME, config = cfg, position = Position.start(cfg.seats)))
            log("STATE S01"); return
        }
        val saved = store.load()
        if (saved != null && restore(saved)) return
        toSetup()
    }

    /** S01 [开始第一局]: two players against the easy computer, you start (states S01 exits). */
    fun firstGame() {
        onSettings(settings().copy(firstRunDone = true))
        newGame(GameConfig(2, Opponent.EASY, 0))
    }

    /** S01 [自己选人数]. */
    fun welcomeChoose() {
        onSettings(settings().copy(firstRunDone = true))
        toSetup()
    }

    private fun restore(saved: SavedGame): Boolean {
        val (pos, moves) = replay(saved.config, saved.moves) ?: return false
        if (saved.config.seats.any { StarRules.hasWon(pos, it) }) return false
        cancelJobs()
        val now = clock()
        val base = PlayState(phase = Phase.IDLE, config = saved.config, position = pos, moves = moves, elapsedMs = saved.elapsedMs,
            resumedAt = now, restored = true, restoredAt = now)
        set(base.copy(facing = facingFor(base)))
        log("GAME restored moves=${moves.size} S16")
        nextTurn(rotate = false)
        return true
    }

    fun continueSaved(): Boolean = store.load()?.let { restore(it) } ?: false

    /** S02 new-game page with the setup previewed on the board (marbles laid out for the chosen player count). */
    fun toSetup() {
        cancelJobs()
        val players = settings().lastPlayers
        val running = s.inGame
        val cfg = GameConfig(players, settings().lastOpponent, 0)
        set(PlayState(phase = Phase.SETUP, config = cfg, position = Position.start(cfg.seats), setupReturn = running && store.load() != null))
        log("STATE S02 players=$players")
    }

    /** Changing the player count on S02 re-lays the marbles (states S02). */
    fun previewSetup(players: Int, opponent: Opponent) {
        onSettings(settings().copy(lastPlayers = players, lastOpponent = opponent))
        if (s.phase != Phase.SETUP) return
        val cfg = GameConfig(players, opponent, 0)
        set(s.copy(config = cfg, position = Position.start(cfg.seats)))
    }

    fun newGame(players: Int, opponent: Opponent) {
        onSettings(settings().copy(lastPlayers = players, lastOpponent = opponent))
        // Against the computer the first move is random (gameSpec.seats); hot-seat starts with mint.
        val first = if (opponent == Opponent.HOTSEAT) 0 else random.nextInt(players)
        newGame(GameConfig(players, opponent, first))
    }

    fun newGame(config: GameConfig) {
        cancelJobs()
        // any game started means the welcome is done: a later relaunch restores the save instead of showing S01 again
        if (!settings().firstRunDone) onSettings(settings().copy(firstRunDone = true))
        val now = clock()
        val base = PlayState(phase = Phase.IDLE, config = config, position = Position.start(config.seats), resumedAt = now,
            firstGame = !settings().coachDone)
        set(base.copy(facing = facingFor(base)))
        log("GAME new players=${config.players} opp=${config.opponent} first=${config.first}")
        persist()
        nextTurn(rotate = false)
    }

    fun again() { s.config?.let { newGame(it.players, it.opponent) } }

    /** [回到这局] on S02: back to the saved game. */
    fun backToGame() { if (!continueSaved()) toSetup() }

    /** Leaving for the background: stop the computer, finish any animation, save (design container.lifecycle). */
    fun onBackground() {
        thinkJob?.cancel()
        if (s.anim != null) { animJob?.cancel(); finishAnim() }
        if (s.drag != null) dragCancel()
        persist()
    }

    fun onForeground() {
        if (s.phase == Phase.THINKING && thinkJob?.isActive != true) think()
    }

    // ---- input: board (all three inputs and the debug commands come through here) ----

    /** A pinch / trigger landed on [hole] (null = outside every hole: cancel). Two stages: select, then confirm. */
    fun tap(hole: Int?) {
        val st = s
        log("TAP ${hole?.let(StarBoard::name) ?: "-"} phase=${st.phase.id}")
        if (!st.humansTurn) return
        val pos = st.position ?: return
        val phase = if (st.phase == Phase.ALERT) st.alert?.back ?: Phase.IDLE else st.phase
        when (phase) {
            Phase.IDLE -> {
                if (hole == null) return
                if (pos[hole] == st.camp && !StarRules.canMove(pos, hole)) { alert(AlertKind.STUCK, hole, Phase.IDLE); return }
                val pick = Picking.nearest(hole, st.movable()) ?: return
                select(pick)
            }
            Phase.SELECTED, Phase.PREVIEW -> {
                if (hole == null) { if (phase == Phase.PREVIEW) set(st.copy(phase = Phase.SELECTED, preview = null, alert = null)) else deselect(); return }
                val sel = st.selected ?: return
                if (hole != sel && pos[hole] == st.camp) {
                    if (StarRules.canMove(pos, hole)) select(hole) else alert(AlertKind.STUCK, hole, phase)
                    return
                }
                if (hole == sel) return
                val lit = Picking.nearest(hole, st.targets.keys)
                if (lit == null) { alert(if (blockedByCamp(pos, sel, hole)) AlertKind.CAMP else AlertKind.ILLEGAL, hole, phase); return }
                if (phase == Phase.PREVIEW && lit == st.preview) commit(st.targets.getValue(lit)) else preview(lit)
            }
            else -> Unit
        }
    }

    private fun select(h: Int) {
        val pos = s.position ?: return
        val t = StarRules.targets(pos, h)
        set(s.copy(phase = Phase.SELECTED, selected = h, preview = null, targets = t, alert = null))
        onSound(Sound.PICK, h)
        log("SELECT ${StarBoard.name(h)} targets=${t.size} S04")
    }

    private fun deselect() {
        set(s.copy(phase = Phase.IDLE, selected = null, preview = null, targets = emptyMap(), alert = null))
        onSound(Sound.BACK, null)
        log("DESELECT S03")
    }

    private fun preview(h: Int) {
        set(s.copy(phase = Phase.PREVIEW, preview = h, alert = null))
        log("PREVIEW ${StarBoard.name(h)} hops=${s.targets[h]?.hops} S19")
    }

    /** The hole could be reached by the chain but is another player's camp (states S08 「别人家不能停」). */
    private fun blockedByCamp(pos: Position, from: Int, hole: Int): Boolean {
        val c = StarBoard.CAMP[hole]
        if (c < 0 || !pos.empty(hole)) return false
        val camp = pos[from]
        if (c == camp || c == StarBoard.target(camp)) return false
        if (StarBoard.NEIGHBOUR[from].contains(hole)) return true
        return StarRules.hopDistances(pos, from, from)[hole] > 0
    }

    /** Panel [落下] (and the controller trigger): S19 drops; S04 previews the most forward lit hole first. */
    fun drop() {
        val st = s
        if (!st.canDrop) return
        if (st.phase == Phase.PREVIEW) st.preview?.let { p -> st.targets[p]?.let { commit(it) } }
        else bestTarget(st)?.let { preview(it) }
    }

    private fun bestTarget(st: PlayState): Int? =
        st.targets.values.maxWithOrNull(compareBy<Move> { Bot.progress(it, st.camp) }.thenBy { -it.to })?.to

    /** Panel arrow keys / thumbstick: walk the selection (S03 / S04) or the preview (S19) as the board is seen. */
    fun nav(dir: Dir) {
        val st = s
        if (!st.humansTurn) return
        val yaw = st.facing * 60f
        when (st.phase) {
            Phase.IDLE -> {
                val c = st.movable()
                val start = c.minByOrNull { val (x, y) = Picking.screen(it, yaw); y * 10 + kotlin.math.abs(x) } ?: return
                select(start)
            }
            Phase.SELECTED -> {
                val next = Picking.nav(st.selected ?: return, dir, st.movable(), yaw)
                if (next == null) onSound(Sound.EDGE, null) else select(next)
            }
            Phase.PREVIEW -> {
                val next = Picking.nav(st.preview ?: return, dir, st.targets.keys, yaw)
                if (next == null) onSound(Sound.EDGE, null) else preview(next)
            }
            else -> Unit
        }
    }

    // ---- input: drag (pinch held / trigger held and moved) ----

    /** A press on [hole] started moving: lift the nearest marble you can move. Returns true when the drag is taken. */
    fun dragStart(hole: Int): Boolean {
        val st = s
        if (!st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED, Phase.PREVIEW, Phase.ALERT)) return false
        val pos = st.position ?: return false
        if (pos[hole] == st.camp && !StarRules.canMove(pos, hole)) { alert(AlertKind.STUCK, hole, Phase.IDLE); return false }
        val from = Picking.nearest(hole, st.movable()) ?: return false
        val t = StarRules.targets(pos, from)
        val (x, y) = Picking.screen(from)
        set(st.copy(phase = Phase.DRAGGING, selected = from, preview = null, targets = t, drag = Drag(from, x, y, null), alert = null))
        onSound(Sound.PICK, from)
        log("DRAG start ${StarBoard.name(from)} targets=${t.size} S20")
        return true
    }

    /** The held marble moved by ([du], [dv]) metres on the board plane since the press. */
    fun dragMove(du: Float, dv: Float) {
        val st = s
        val d = st.drag ?: return
        val (x0, y0) = Picking.screen(d.from)
        var px = x0 + du / StarBoard.PITCH
        var py = y0 + dv / StarBoard.PITCH
        // keep the held marble over the board (radius ~7.4 pitches)
        val r = kotlin.math.hypot(px, py)
        val maxR = 7.4f
        if (r > maxR) { px *= maxR / r; py *= maxR / r }
        val ghost = Picking.snap(px, py, d.ghost, st.targets.keys)
        if (ghost != d.ghost) log("DRAG ghost ${ghost?.let(StarBoard::name) ?: "-"}")
        set(st.copy(drag = d.copy(px = px, py = py, ghost = ghost), preview = ghost))
    }

    /** Let go: on a lit hole it hops there; anywhere else the marble goes back (not a move). */
    fun dragEnd() {
        val st = s
        val d = st.drag ?: return
        val m = d.ghost?.let { st.targets[it] }
        if (m != null) { log("DRAG drop ${m.code}"); commit(m) } else dragCancel()
    }

    fun dragCancel() {
        val st = s
        if (st.drag == null) return
        set(st.copy(phase = Phase.IDLE, drag = null, selected = null, preview = null, targets = emptyMap()))
        onSound(Sound.BACK, null)
        log("DRAG put back S03")
    }

    // ---- hot-seat hand-over ----

    /** S18 [开始]: the next player has the headset. */
    fun handoffStart() {
        if (s.phase != Phase.HANDOFF) return
        set(s.copy(phase = Phase.IDLE))
        log("HANDOFF start camp=${s.camp} S03")
    }

    // ---- moves ----

    private fun commit(m: Move) {
        val st = s
        val pos = st.position ?: return
        val camp = pos[m.from]
        val now = clock()
        set(st.copy(
            phase = Phase.ANIMATING, position = pos.apply(m), moves = st.moves + m, anim = Anim(m, camp, now),
            selected = null, preview = null, targets = emptyMap(), drag = null, alert = null, restored = false, passed = null,
        ))
        log("MOVE ${m.code} camp=$camp hops=${m.hops} n=${s.moves.size} S05")
        persist()
        animJob?.cancel()
        animJob = scope.launch {
            if (!m.jump) delay(STEP_MS)
            else for (k in m.path.indices) {
                delay(HOP_MS)
                if (k < m.path.lastIndex) { onSound(Sound.HOP, m.path[k]); delay(HOP_GAP_MS) }
            }
            awaitUnfrozen()
            onSound(Sound.PLACE, m.to)
            finishAnim()
        }
    }

    private suspend fun awaitUnfrozen() {
        while (coroutineContext.isActive && s.freezeMs != null) delay(50)
    }

    private fun finishAnim() {
        val st = s
        val a = st.anim ?: return
        set(st.copy(anim = null))
        val pos = s.position ?: return
        val cfg = s.config ?: return
        if (StarRules.hasWon(pos, a.camp)) { finish(a.camp); return }
        val goal = StarBoard.target(a.camp)
        if (StarBoard.CAMP[a.move.to] == goal && StarBoard.CAMP[a.move.from] != goal) onSound(Sound.HOME, a.move.to)
        if (cfg.seats.isNotEmpty()) nextTurn(rotate = true)
    }

    private fun finish(winner: Int) {
        cancelJobs()
        val st = s
        val cfg = st.config ?: return
        val pos = st.position ?: return
        val elapsed = st.elapsedMs + (clock() - st.resumedAt)
        val stats = if (!cfg.hotSeat) 0 else winner
        val r = GameResult(winner, StarRules.ranking(pos, cfg.seats, winner),
            moves = st.moves.indices.count { cfg.campAt(it) == stats && st.moves[it].from >= 0 },
            longestChain = st.longest(stats), elapsedMs = elapsed)
        set(st.copy(phase = Phase.RESULT, result = r, elapsedMs = elapsed, selected = null, preview = null, targets = emptyMap(), drag = null, confirm = null))
        // A computer's win is not a fanfare (template: opponent wins don't play the victory sound).
        onSound(if (!cfg.hotSeat && winner != 0) Sound.LOSE else Sound.WIN, null)
        log("RESULT winner=$winner ranking=${r.ranking} moves=${r.moves} longest=${r.longestChain} S09")
        if (st.firstGame) onSettings(settings().copy(coachDone = true))
        store.clear()
    }

    /** Whose turn is next; seats with nothing to move pass (announced on the sign). */
    private fun nextTurn(rotate: Boolean) {
        var st = s
        val cfg = st.config ?: return
        val pos = st.position ?: return
        if (st.phase == Phase.RESULT) return
        var passes = 0
        var passed: Int? = null
        while (StarRules.legalMoves(pos, st.camp).isEmpty() && passes < cfg.seats.size) {
            log("PASS camp=${st.camp}")
            passed = st.camp
            st = st.copy(moves = st.moves + PASS)
            passes++
        }
        if (passes >= cfg.seats.size) { finish(StarRules.ranking(pos, cfg.seats, null).first()); return }
        st = st.copy(passed = passed)
        set(st)
        if (!cfg.isHuman(st.camp)) { think(); return }
        val face = facingFor(st)
        if (cfg.hotSeat && rotate) {
            val ms = rotateMs(st.facing, face)
            set(st.copy(phase = Phase.ROTATING, rotateFrom = st.facing, facing = face, rotateStartedAt = clock()))
            log("ROTATE ${st.facing} -> $face ${ms} ms S17")
            rotateJob?.cancel()
            rotateJob = scope.launch {
                delay(ms)
                set(s.copy(phase = Phase.HANDOFF))
                log("HANDOFF camp=${s.camp} S18")
            }
            return
        }
        set(st.copy(phase = Phase.IDLE, facing = face))
        log("STATE S03 camp=${st.camp}")
    }

    /** The board turns to the human whose turn it is; against the computer it stays on mint (camp 0). */
    private fun facingFor(st: PlayState): Int = if (st.config?.hotSeat == true) st.camp else 0

    private fun think() {
        val st = s
        val cfg = st.config ?: return
        val pos = st.position ?: return
        val camp = st.camp
        set(st.copy(phase = Phase.THINKING, selected = null, preview = null, targets = emptyMap()))
        log("THINK camp=$camp level=${cfg.level} S06")
        thinkJob?.cancel()
        val turn = st.turn
        thinkJob = scope.launch {
            val t0 = clock()
            val rnd = Random(aiSeed + turn * 31 + camp)
            val move = withContext(engineDispatcher) {
                val job = coroutineContext[Job]
                Bot.choose(pos, camp, cfg.level, rnd) { job?.isActive == false }
            }
            val waited = clock() - t0
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (s.phase != Phase.THINKING || s.turn != turn) return@launch
            if (move == null) { nextTurn(rotate = false); return@launch }
            log("AI ${move.code} after ${clock() - t0} ms")
            commit(move)
        }
    }

    private fun alert(kind: AlertKind, hole: Int, back: Phase) {
        val at = clock()
        set(s.copy(phase = Phase.ALERT, alert = Alert(kind, hole, at, back)))
        onSound(Sound.NO, hole)
        log("ALERT $kind ${StarBoard.name(hole)} S08")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            while (holdAlert) delay(100)
            val now = s
            if (now.alert?.at == at && now.phase == Phase.ALERT) set(now.copy(alert = null, phase = back))
        }
    }

    // ---- undo ----

    /** [悔一步]: against the computer straight back to before your last move; hot-seat asks first (S13). */
    fun requestUndo() {
        val st = s
        val cfg = st.config ?: return
        if (!st.canUndo) return
        if (cfg.hotSeat) { set(st.copy(confirm = ConfirmKind.UNDO_2P)); log("CONFIRM UNDO_2P S13") } else undo()
    }

    private fun undo() {
        val st = s
        val cfg = st.config ?: return
        animJob?.cancel(); thinkJob?.cancel(); rotateJob?.cancel(); alertJob?.cancel()
        val moves = st.moves.toMutableList()
        if (cfg.hotSeat) {
            while (moves.isNotEmpty() && moves.last().from < 0) moves.removeAt(moves.lastIndex)
            if (moves.isNotEmpty()) moves.removeAt(moves.lastIndex)
        } else {
            while (moves.isNotEmpty()) {
                val i = moves.lastIndex
                val mine = cfg.campAt(i) == 0 && moves[i].from >= 0
                moves.removeAt(i)
                if (mine) break
            }
        }
        val (pos, rebuilt) = replay(cfg, moves.map { SavedMove(it.from, it.to) }) ?: return
        set(st.copy(position = pos, moves = rebuilt, anim = null, selected = null, preview = null, targets = emptyMap(), drag = null,
            alert = null, confirm = null, phase = Phase.IDLE))
        onSound(Sound.BACK, null)
        log("UNDO -> n=${rebuilt.size}")
        persist()
        nextTurn(rotate = true)
    }

    // ---- confirms ----

    fun requestConfirm(kind: ConfirmKind) { set(s.copy(confirm = kind)); log("CONFIRM $kind S13") }

    /** [新对局…]: with moves on the board ask first (S13 「这局不留，开新的？」), else straight to S02. */
    fun requestNewGame() {
        if (s.inGame && s.moves.any { it.from >= 0 }) requestConfirm(ConfirmKind.NEW_GAME) else toSetup()
    }

    fun cancelConfirm() { set(s.copy(confirm = null)) }

    /** Confirms the open question. EXIT is done by the window (it owns the navigator); here it only saves. */
    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        when (kind) {
            ConfirmKind.UNDO_2P -> undo()
            ConfirmKind.NEW_GAME -> { store.clear(); toSetup() }
            ConfirmKind.EXIT -> onBackground()
        }
    }

    // ---- debug ----

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms)); log("FREEZE ${ms ?: "off"}") }

    /** Debug: replace the game with [saved] moves (validated through the rules), keeping or setting the config. */
    fun load(config: GameConfig, saved: List<SavedMove>): Boolean {
        val (pos, moves) = replay(config, saved) ?: return false
        cancelJobs()
        val base = PlayState(phase = Phase.IDLE, config = config, position = pos, moves = moves, resumedAt = clock())
        set(base.copy(facing = facingFor(base)))
        log("LOAD n=${moves.size}")
        persist()
        config.seats.firstOrNull { StarRules.hasWon(pos, it) }?.let { finish(it); return true }
        nextTurn(rotate = false)
        return true
    }

    /** Debug: place an arbitrary position (for end-game screenshots); the moves list is emptied. */
    fun loadPosition(config: GameConfig, pos: Position, turnCamp: Int) {
        cancelJobs()
        val idx = config.seats.indexOf(turnCamp).coerceAtLeast(0)
        val cfg = config.copy(first = idx)
        val base = PlayState(phase = Phase.IDLE, config = cfg, position = pos, resumedAt = clock())
        set(base.copy(facing = facingFor(base)))
        log("LOADPOS camp=$turnCamp")
        nextTurn(rotate = false)
    }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} n=${st.moves.size} camp=${st.camp} facing=${st.facing} sel=${st.selected?.let(StarBoard::name)} " +
            "preview=${st.preview?.let(StarBoard::name)} targets=${st.targets.keys.joinToString(",") { StarBoard.name(it) }} drag=${st.drag} " +
            "alert=${st.alert?.kind} result=${st.result} confirm=${st.confirm} freeze=${st.freezeMs} last=${st.moves.lastOrNull()?.code}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.SETUP || st.phase == Phase.WELCOME) return
        val elapsed = st.elapsedMs + (clock() - st.resumedAt)
        store.save(SavedGame(cfg, st.moves.map { SavedMove(it.from, it.to) }, elapsed))
    }

    private fun cancelJobs() {
        animJob?.cancel(); thinkJob?.cancel(); alertJob?.cancel(); rotateJob?.cancel()
    }
}
