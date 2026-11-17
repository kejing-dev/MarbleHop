package io.embodit.marblehop.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.embodit.marblehop.domain.BoardGeometry
import io.embodit.marblehop.domain.ConfirmKind
import io.embodit.marblehop.domain.Dir
import io.embodit.marblehop.domain.GameConfig
import io.embodit.marblehop.domain.Language
import io.embodit.marblehop.domain.Opponent
import io.embodit.marblehop.domain.Position
import io.embodit.marblehop.domain.Settings
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.ui.Page
import io.embodit.marblehop.ui.Ui
import io.embodit.marblehop.ui.board.DRAG_GAIN
import io.embodit.marblehop.ui.board.SceneHolder
import io.embodit.marblehop.ui.board.input.BoardInputLog
import io.embodit.marblehop.ui.board.input.HoleTargets
import io.embodit.marblehop.ui.board.input.InputSource
import io.embodit.marblehop.ui.board.input.Offset3M
import io.embodit.marblehop.ui.board.input.resolveUp

/**
 * Debug builds only. Board commands go through the SAME BoardInputListener the SpatialView uses (tap / drag start /
 * drag move / drag end), so they exercise the real hit -> hole -> nearest-candidate path (build.md: no shortcuts).
 *
 *   adb shell am broadcast -a io.embodit.marblehop.DEBUG -p io.embodit.marblehop --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S20          reach a named state (AGENTS.md lists what each one sets up)
 *   reset                  back to a fresh 2-player game against the easy bot, you to move (clears holds / freeze)
 *   new "players=4 opp=smart first=0"   opp easy|smart|hotseat; first = index of the starting seat
 *   tap q,r | tap off      a pinch / trigger on hole (q,r) (off = outside every hole)
 *   pick q,r · preview q,r (= tap) · commit (= [落下])
 *   drag "q,r q,r [hold]"  press on the first hole, move to the second, let go (hold = keep holding: S20)
 *   nav up|down|left|right · drop · undo · newgame · confirm · cancel · start (S18 [开始]) · again · setup
 *   replay "q,r [hand-ray|eye-hand|controller]"   resolve real scene entities of that hole and tap the deepest one
 *   tilt 0|30|45|60|75|90 · lang zh|en|system · sound off|1|2|3 · mute on|off · page play|rules|settings · rules 0|1
 *   hold on|off (bots wait) · alerthold on|off · freeze <ms>|off · seed N · recall · exitask · first (S01 start)
 *   s00hold on|off (next launch keeps the S00 stand-in 10 s) · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    private val main = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim()
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun hole(s: String) = StarBoard.parse(s)
        fun tap(h: Int?) {
            val l = SceneHolder.listener
            if (l == null) Log.w(TAG, "no board listener yet") else l.onTap(h, InputSource.UNKNOWN, null)
        }
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun fresh(players: Int = 2, opp: Opponent = Opponent.EASY, first: Int = 0) {
            flow.holdThinking = false; flow.holdAlert = false; flow.freeze(null)
            flow.newGame(GameConfig(players, opp, first)); Ui.page = Page.PLAY
        }
        fun later(ms: Long, block: () -> Unit) = main.postDelayed(block, ms)
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false) }; flow.start(); Ui.page = Page.PLAY }
                "S02" -> { flow.toSetup(); Ui.page = Page.PLAY }
                "S03" -> fresh()
                "S04" -> { fresh(); tap(hole("2,-5")) }
                "S19" -> { fresh(); tap(hole("2,-5")); tap(hole("2,-4")) }
                "S20" -> { fresh(); drag(graph, hole("2,-5")!!, hole("2,-4")!!, hold = true) }
                "S05" -> { sample(graph); flow.freeze(420); tap(hole("0,-4")); tap(hole("0,2")); tap(hole("0,2")) }
                "S06" -> { flow.holdThinking = true; fresh(first = 1); flow.holdThinking = true }
                "S08" -> { fresh(); flow.holdAlert = true; tap(hole("2,-5")); tap(hole("0,0")) }
                "S09" -> { endgame(graph); tap(hole("-4,4")); tap(hole("-4,5")); tap(hole("-4,5")) }
                "S11", "S14" -> Ui.page = Page.SETTINGS
                "S12" -> Ui.page = Page.RULES
                "S13" -> { fresh(); tap(hole("2,-5")); tap(hole("2,-4")); tap(hole("2,-4")); later(1200) { flow.requestNewGame() } }
                "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                "S17" -> { fresh(opp = Opponent.HOTSEAT); tap(hole("2,-5")); tap(hole("2,-4")); tap(hole("2,-4")) }
                "S18" -> { fresh(opp = Opponent.HOTSEAT); tap(hole("2,-5")); tap(hole("2,-4")); tap(hole("2,-4")) }
                else -> Log.w(TAG, "unknown state $a")
            }
            "reset" -> fresh()
            "new" -> {
                val kv = a.split(" ").mapNotNull { it.split("=").takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
                val players = kv["players"]?.toIntOrNull()?.takeIf { it in StarBoard.PLAYER_COUNTS } ?: 2
                val opp = when (kv["opp"]) { "smart" -> Opponent.SMART; "hotseat", "human" -> Opponent.HOTSEAT; else -> Opponent.EASY }
                fresh(players, opp, kv["first"]?.toIntOrNull()?.coerceIn(0, players - 1) ?: 0)
            }
            "tap", "pick", "preview" -> tap(if (a == "off") null else hole(a))
            "commit", "drop" -> flow.drop()
            "drag" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                val from = f.getOrNull(0)?.let(::hole); val to = f.getOrNull(1)?.let(::hole)
                if (from != null && to != null) drag(graph, from, to, hold = f.getOrNull(2) == "hold")
            }
            "nav" -> Dir.entries.firstOrNull { it.name.equals(a, ignoreCase = true) }?.let { flow.nav(it) }
            "undo" -> flow.requestUndo()
            "newgame" -> flow.requestNewGame()
            "confirm" -> flow.confirm()
            "cancel" -> flow.cancelConfirm()
            "start" -> flow.handoffStart()
            "again" -> flow.again()
            "setup" -> flow.toSetup()
            "first" -> flow.firstGame()
            "replay" -> replay(a)
            "tilt" -> a.toIntOrNull()?.takeIf { it in Settings.TILTS }?.let { t -> settings { it.copy(tilt = t) } }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { if (a == "off") it.copy(muted = true) else it.copy(muted = false, volume = a.toIntOrNull()?.coerceIn(1, 3) ?: it.volume) }
            "mute" -> settings { it.copy(muted = on) }
            "page" -> Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "rules" -> Ui.rulesView = a.toIntOrNull()?.coerceIn(0, 1) ?: 0
            "hold" -> flow.holdThinking = on
            "alerthold" -> flow.holdAlert = on
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "seed" -> a.toIntOrNull()?.let { flow.aiSeed = it }
            "recall" -> graph.window.recall()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }

    /** Press on [from], move in 8 steps to [to] through the listener's drag path (volume metres, like the SDK sends). */
    private fun drag(graph: Graph, from: Int, to: Int, hold: Boolean) {
        val listener = SceneHolder.listener ?: return
        if (!listener.onDragStart(from, InputSource.UNKNOWN)) { Log.w(TAG, "DRAG refused at ${StarBoard.name(from)}"); return }
        val st = graph.flow.state.value
        val g = BoardGeometry(graph.settings.value.tilt)
        val (u, v, _) = g.planeAxes(0)
        // the board's own frame -> as seen (facing turn) -> volume axes; divide by the gain the listener multiplies by
        val du = (StarBoard.u(to) - StarBoard.u(from)) / DRAG_GAIN
        val dv = (StarBoard.v(to) - StarBoard.v(from)) / DRAG_GAIN
        val ang = st.facing * Math.PI / 3
        val su = (du * Math.cos(ang) - dv * Math.sin(ang)).toFloat()
        val sv = (du * Math.sin(ang) + dv * Math.cos(ang)).toFloat()
        val total = u * su + v * sv
        for (k in 1..8) {
            val t = k / 8f
            main.postDelayed({ listener.onDragMove(Offset3M(total.x * t, total.y * t, total.z * t)) }, k * 40L)
        }
        if (!hold) main.postDelayed({ listener.onDragEnd() }, 400L)
    }

    /**
     * board-input README §4: take the REAL scene entities of a hole (its groove disc, and the marble sitting there),
     * walk resolveUp like a system hit would, log the path, and tap the deepest one through the same listener.
     */
    private fun replay(arg: String) {
        val f = arg.split(" ").filter { it.isNotBlank() }
        val h = f.getOrNull(0)?.let { StarBoard.parse(it) } ?: return
        val src = when (f.getOrNull(1)) { "eye-hand" -> InputSource.EYE_HAND; "controller" -> InputSource.CONTROLLER; else -> InputSource.HAND_RAY }
        val scene = SceneHolder.scene ?: return
        val listener = SceneHolder.listener ?: return
        val hits = scene.entitiesAt(h)
        for (e in hits) {
            val (hit, path) = resolveUp(e, HoleTargets)
            val line = "REPLAY src=${src.label} path=$path -> hole=${hit?.let(StarBoard::name) ?: "-"}"
            Log.i(TAG, line); BoardInputLog.write("BoardInput", line)
        }
        val deepest = hits.firstOrNull() ?: return
        val (hit, path) = resolveUp(deepest, HoleTargets)
        val line = "INPUT tap src=${src.label} path=$path -> square=${hit?.let(StarBoard::name) ?: "-"}"
        Log.i(TAG, line); BoardInputLog.write("BoardInput", line)
        listener.onTap(hit, src, null)
    }

    /** design sampleFirstFrame: mint (you) vs amber, a mint marble at (0,-4) with a triple-hop ladder up the middle. */
    private fun sample(graph: Graph) {
        val cells = IntArray(StarBoard.SIZE) { -1 }
        for (h in StarBoard.CAMP_HOLES[0]) cells[h] = 0
        for (h in StarBoard.CAMP_HOLES[3]) cells[h] = 3
        fun mv(from: String, to: String) { val a = StarBoard.parse(from)!!; val b = StarBoard.parse(to)!!; cells[b] = cells[a]; cells[a] = -1 }
        mv("1,-5", "0,-4"); mv("2,-5", "0,-1"); mv("-1,5", "0,-3"); mv("-2,5", "0,1")
        graph.flow.holdThinking = false; graph.flow.holdAlert = false; graph.flow.freeze(null)
        graph.flow.loadPosition(GameConfig(2, Opponent.EASY, 0), Position(cells), 0)
        Ui.page = Page.PLAY
    }

    /** S09: mint has 9 marbles home and the 10th one step outside at (-4,4); stepping it in to (-4,5) wins. */
    private fun endgame(graph: Graph) {
        val cells = IntArray(StarBoard.SIZE) { -1 }
        val missing = StarBoard.parse("-4,5")!!
        for (h in StarBoard.CAMP_HOLES[3]) if (h != missing) cells[h] = 0
        cells[StarBoard.parse("-4,4")!!] = 0
        // amber: most of its marbles already in mint's start camp, two still on the way
        StarBoard.CAMP_HOLES[0].take(8).forEach { cells[it] = 3 }
        cells[StarBoard.parse("2,-3")!!] = 3; cells[StarBoard.parse("1,-2")!!] = 3
        graph.flow.holdThinking = false; graph.flow.holdAlert = false; graph.flow.freeze(null)
        graph.flow.loadPosition(GameConfig(2, Opponent.EASY, 0), Position(cells), 0)
        Ui.page = Page.PLAY
    }
}
