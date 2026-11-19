package io.embodit.marblehop.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.marblehop.BuildConfig
import io.embodit.marblehop.R
import io.embodit.marblehop.domain.ConfirmKind
import io.embodit.marblehop.domain.Dir
import io.embodit.marblehop.domain.Language
import io.embodit.marblehop.domain.Opponent
import io.embodit.marblehop.domain.Phase
import io.embodit.marblehop.domain.PlayState
import io.embodit.marblehop.domain.Settings
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.domain.StarRules
import io.embodit.marblehop.platform.Graph
import io.embodit.marblehop.platform.TAG
import io.embodit.marblehop.ui.Containers
import io.embodit.marblehop.ui.Page
import io.embodit.marblehop.ui.SignText
import io.embodit.marblehop.ui.Ui
import io.embodit.marblehop.ui.seatName
import io.embodit.marblehop.ui.theme.Palette
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the board, 560 x 540 dp): all 2D UI of the app, three pages
 * switched by the native SpatialUI TabBar (D8): Game / Rules / Settings. The new-game setup lives on the game page
 * (design windowModel, B31). Dragging and closing are the system window controls under the window (D21).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // Milestones bring the game page forward: welcome, setup, the result, a hand-over.
    LaunchedEffect(state.phase) {
        if (state.phase in setOf(Phase.WELCOME, Phase.SETUP, Phase.RESULT, Phase.HANDOFF)) Ui.page = Page.PLAY
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.RULES -> RulesPage()
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The TabBar is its own system window: its items do not see this window's localised context, so the labels are
    // resolved here and passed in as plain strings (template exemplar QA: tabs stayed Chinese in English).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    androidx.compose.runtime.key(settings.language) { ControlTabBar(tabs) }
}

@Composable
private fun seat(camp: Int): String = stringResource(seatName(camp))

// ------------------------------------------------------------------ Game page

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    when {
        state.phase == Phase.WELCOME -> WelcomeBlock(graph)
        state.phase == Phase.SETUP || state.config == null || state.phase == Phase.LOADING -> SetupBlock(state, settings, graph)
        state.result != null -> ResultBlock(state, graph)
        state.phase == Phase.HANDOFF || state.phase == Phase.ROTATING -> HandoffBlock(state, graph)
        else -> GameBlock(state, settings, graph)
    }
}

/** S01: title + the three steps + [开始第一局] + [自己选人数]. */
@Composable
private fun WelcomeBlock(graph: Graph) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Txt(stringResource(R.string.welcome_title), "welcome/title", style = PicoTheme.typography.headlineSmall)
        listOf(R.string.welcome_step_1, R.string.welcome_step_2, R.string.welcome_step_3).forEachIndexed { i, res ->
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.PrimaryContainer), contentAlignment = Alignment.Center) {
                    Txt("${i + 1}", "welcome/n", style = PicoTheme.typography.titleMedium, color = Palette.OnPrimaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Txt(stringResource(res), "welcome/step", style = PicoTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        PrimaryButton(stringResource(R.string.btn_first_game), "welcome/first", onClick = { graph.flow.firstGame() }, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.btn_choose_players), "welcome/choose", onClick = { graph.flow.welcomeChoose() }, modifier = Modifier.fillMaxWidth())
    }
}

/** S02: players 2 / 3 / 4 / 6 + opponent + a note + [开始]; [回到这局] when a game is saved. */
@Composable
private fun SetupBlock(state: PlayState, settings: Settings, graph: Graph) {
    val players = settings.lastPlayers
    val opp = settings.lastOpponent
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(R.string.setup_players), "setup/players-label", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Segments(StarBoard.PLAYER_COUNTS.map { it to "$it" }, players, "setup/players", { n -> graph.flow.previewSetup(n, opp) }, Modifier.weight(1f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(R.string.setup_opponent), "setup/opp-label", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Segments(
                    listOf(Opponent.EASY to stringResource(R.string.opp_easy), Opponent.SMART to stringResource(R.string.opp_smart), Opponent.HOTSEAT to stringResource(R.string.opp_hotseat)),
                    opp, "setup/opp", { o -> graph.flow.previewSetup(players, o) }, Modifier.weight(1f),
                )
            }
        }
        Txt(stringResource(if (opp == Opponent.HOTSEAT) R.string.setup_note_hotseat else R.string.setup_note_bot), "setup/note",
            style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        PrimaryButton(stringResource(R.string.btn_start), "setup/start", onClick = { graph.flow.newGame(players, opp) }, modifier = Modifier.fillMaxWidth())
        if (state.setupReturn) SecondaryButton(stringResource(R.string.btn_back_to_game), "setup/back", onClick = { graph.flow.backToGame() }, modifier = Modifier.fillMaxWidth())
    }
}

/** S03 / S04 / S19 / S20 / S05 / S06 / S08: whose turn + progress + hint + arrows / [落下] + [悔一步] [新对局…]. */
@Composable
private fun GameBlock(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val cfg = state.config ?: return
    val camp = state.camp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        // whose turn (same source as the sign, D38) + mute always on the main page (B31 exception, D15)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            SeatDot(camp, 30.dp)
            Spacer(Modifier.width(12.dp))
            val turn = when {
                state.phase == Phase.THINKING -> stringResource(R.string.turn_bot, seat(camp))
                cfg.isHuman(camp) && !cfg.hotSeat -> stringResource(R.string.turn_you, seat(camp))
                else -> stringResource(R.string.turn_seat, seat(camp))
            }
            Txt(turn, "play/turn", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.phase == Phase.THINKING) { CircularProgressIndicator(modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
            ToggleButton(stringResource(R.string.btn_mute), "play/mute", on = settings.muted, onClick = { graph.settings.update { it.copy(muted = !it.muted) } })
        }
        ProgressChips(state)
        HintLine(state)
        if (state.confirm != null) ConfirmBlock(state.confirm, graph)
        else {
            val navOn = state.humansTurn && state.phase in setOf(Phase.IDLE, Phase.SELECTED, Phase.PREVIEW, Phase.ALERT)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                NavKey("▲", stringResource(R.string.nav_up), "play/up", navOn) { flow.nav(Dir.UP) }
                NavKey("▼", stringResource(R.string.nav_down), "play/down", navOn) { flow.nav(Dir.DOWN) }
                NavKey("◀", stringResource(R.string.nav_left), "play/left", navOn) { flow.nav(Dir.LEFT) }
                NavKey("▶", stringResource(R.string.nav_right), "play/right", navOn) { flow.nav(Dir.RIGHT) }
                PrimaryButton(stringResource(R.string.btn_drop), "play/drop", onClick = { flow.drop() }, enabled = state.canDrop, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.requestUndo() }, enabled = state.canUndo, modifier = Modifier.weight(1f), tall = true)
                SecondaryButton(stringResource(R.string.btn_new_game), "play/new", onClick = { flow.requestNewGame() }, modifier = Modifier.weight(1f), tall = true)
            }
        }
    }
}

/** An arrow key: 76 x 72 dp, arrow glyph over its word (B19: icons carry text). */
@Composable
private fun NavKey(glyph: String, word: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    KeyButton(glyph, word, tag, onClick = onClick, enabled = enabled, modifier = Modifier.width(76.dp).height(72.dp))
}

/** Marbles home per seat: one chip each (4 players: two rows of two; 6 players: two rows of three). */
@Composable
private fun ProgressChips(state: PlayState) {
    val cfg = state.config ?: return
    val pos = state.position ?: return
    val seats = cfg.seats.toList()
    // 4+ seats: two rows (4 -> 2 + 2, 6 -> 3 + 3) so English seat names never clip (QA #1 q1-1, D16)
    val rows = if (seats.size > 3) seats.chunked((seats.size + 1) / 2) else listOf(seats)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    val home = StarRules.homeCounts(pos, c).first
                    val current = c == state.camp && state.result == null
                    Row(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                            .background(if (current) Palette.PrimaryContainer else PicoTheme.colorScheme.fillSecondary).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SeatDot(c, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Txt(seat(c), "play/chip-name", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Txt(stringResource(R.string.progress_home, home), "play/chip-home", style = PicoTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** Two lines: the sign's line, then what you can do now. */
@Composable
private fun HintLine(state: PlayState) {
    val line = SignText.of(state)
    val hint = when (state.phase) {
        Phase.IDLE -> if (state.humansTurn) R.string.hint_idle else R.string.hint_bot
        Phase.SELECTED, Phase.ALERT -> R.string.hint_selected
        Phase.PREVIEW -> R.string.hint_preview
        Phase.DRAGGING -> R.string.hint_dragging
        Phase.ANIMATING -> R.string.hint_animating
        Phase.THINKING -> R.string.hint_bot
        else -> R.string.hint_rotating
    }
    Column(Modifier.fillMaxWidth().height(60.dp), verticalArrangement = Arrangement.Center) {
        Txt(line.text(), "play/status", style = PicoTheme.typography.titleMedium, color = if (line.alert) Palette.ErrorText else PicoTheme.colorScheme.labelPrimary)
        Txt(stringResource(hint), "play/hint", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
    }
}

/** S13, opened where the button row was: one question + two equal buttons ([算了] on the left). */
@Composable
private fun ConfirmBlock(kind: ConfirmKind, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val (q, yes) = when (kind) {
        ConfirmKind.NEW_GAME -> R.string.confirm_new to R.string.confirm_yes_new
        ConfirmKind.UNDO_2P -> R.string.confirm_undo_2p to R.string.confirm_yes_undo
        ConfirmKind.EXIT -> R.string.confirm_exit to R.string.confirm_yes_exit
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(stringResource(q), "confirm/${kind.name}", style = PicoTheme.typography.titleMedium, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = { flow.cancelConfirm() }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(yes), "confirm/yes", danger = kind != ConfirmKind.UNDO_2P, onClick = {
                if (kind == ConfirmKind.EXIT) exitApp(context, navigator, graph) else flow.confirm()
            }, modifier = Modifier.weight(1f), tall = true)
        }
    }
}

/** S18: the next player's colour, the last move, a big [开始]; the board ignores input meanwhile. */
@Composable
private fun HandoffBlock(state: PlayState, graph: Graph) {
    val camp = state.camp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
        SeatDot(camp, 96.dp)
        Txt(stringResource(R.string.handoff_title, seat(camp)), "handoff/title", style = PicoTheme.typography.headlineSmall)
        val last = state.moves.withIndex().lastOrNull { it.value.from >= 0 }
        val lastText = if (last == null) stringResource(R.string.handoff_none) else {
            val m = last.value
            val what = when {
                m.hops == 2 -> stringResource(R.string.sign_chain_2)
                m.hops == 3 -> stringResource(R.string.sign_chain_3)
                m.hops > 3 -> stringResource(R.string.sign_chain_n, m.hops)
                m.jump -> stringResource(R.string.rules_hop)
                else -> stringResource(R.string.rules_step)
            }
            stringResource(R.string.handoff_last, seat(state.config!!.campAt(last.index)), what)
        }
        Txt(lastText, "handoff/last", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary)
        if (state.confirm != null) ConfirmBlock(state.confirm, graph)
        else {
            PrimaryButton(stringResource(R.string.btn_handoff_start), "handoff/start", onClick = { graph.flow.handoffStart() }, enabled = state.phase == Phase.HANDOFF,
                modifier = Modifier.fillMaxWidth(), height = 88)
            SecondaryButton(stringResource(R.string.btn_undo), "handoff/undo", onClick = { graph.flow.requestUndo() }, enabled = state.canUndo, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** S09: who won + one stats line + places + [再来一局] [换人数…]. */
@Composable
private fun ResultBlock(state: PlayState, graph: Graph) {
    val r = state.result ?: return
    val cfg = state.config ?: return
    val pos = state.position ?: return
    val secs = r.elapsedMs / 1000
    val time = "%d:%02d".format(secs / 60, secs % 60)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeatDot(r.winner, 36.dp)
            Spacer(Modifier.width(12.dp))
            val title = if (!cfg.hotSeat && r.winner == 0) stringResource(R.string.result_you_win) else stringResource(R.string.result_wins, seat(r.winner))
            Txt(title, "result/title", style = PicoTheme.typography.headlineSmall)
        }
        Txt(stringResource(R.string.result_stats, r.moves, time, r.longestChain), "result/stats", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary)
        val places = r.ranking.withIndex().toList()
        val cols = if (places.size >= 4) places.chunked((places.size + 1) / 2) else listOf(places)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cols.forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    col.forEach { (i, c) ->
                        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            SeatDot(c, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Txt(stringResource(R.string.result_place, i + 1, seat(c), StarRules.homeCounts(pos, c).first), "result/place", style = PicoTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.btn_change_players), "result/change", onClick = { graph.flow.toSetup() }, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        // same-page option (rules / how to play), not page navigation
        Row(Modifier.fillMaxWidth()) {
            Segments(listOf(0 to stringResource(R.string.rules_title), 1 to stringResource(R.string.how_to_title)), Ui.rulesView, "rules/view", { Ui.rulesView = it }, Modifier.weight(1f))
        }
        if (Ui.rulesView == 0) {
            RulesDiagram()
            listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4, R.string.rules_5).forEach {
                Txt(stringResource(it), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 3)
            }
        } else {
            // QA #1 q1-2: the three lines used to be spread by SpaceBetween (inner gap 24 % of the window);
            // now three cards packed together, centred in the space under the segments.
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                listOf(R.string.how_to_drag, R.string.how_to_tap, R.string.how_to_arrows).forEachIndexed { i, res ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(20.dp))
                            .background(PicoTheme.colorScheme.fillSecondary).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.PrimaryContainer), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "howto/n", style = PicoTheme.typography.titleMedium, color = Palette.OnPrimaryContainer)
                        }
                        Spacer(Modifier.width(14.dp))
                        Txt(stringResource(res), "howto/line", style = PicoTheme.typography.titleMedium, maxLines = 3, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One small picture: a step to the next hole, and a hop over a neighbour into the hole behind. */
@Composable
private fun RulesDiagram() {
    val step = stringResource(R.string.rules_step)
    val hop = stringResource(R.string.rules_hop)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(width = 300.dp, height = 72.dp)) {
            val r = size.height * 0.18f
            val y = size.height * 0.62f
            val gap = size.width / 7f
            drawRoundRect(Palette.Beech, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f))
            for (i in 0 until 7) drawCircle(Palette.Groove, r * 0.9f, Offset(gap * (i + 0.5f), y))
            // step: marble at 0 -> 1
            drawCircle(Palette.Seat[0], r, Offset(gap * 0.5f, y))
            drawCircle(Palette.StepDot, r * 0.5f, Offset(gap * 1.5f, y))
            // hop: marble at 3 over 4 into 5
            drawCircle(Palette.Seat[0], r, Offset(gap * 3.5f, y))
            drawCircle(Palette.Seat[3], r, Offset(gap * 4.5f, y))
            drawCircle(Palette.HopRing, r * 0.8f, Offset(gap * 5.5f, y), style = Stroke(r * 0.3f))
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(gap * 3.5f, y - r); quadraticTo(gap * 4.5f, y - r * 4.2f, gap * 5.5f, y - r) }
            drawPath(path, Palette.Ink, style = Stroke(3f))
        }
        Column {
            Txt("● → ·  $step", "rules/legend-step", style = PicoTheme.typography.bodyMedium)
            Txt("● ⤳ ○  $hop", "rules/legend-hop", style = PicoTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    fun upd(change: (Settings) -> Settings) = graph.settings.update(change)
    // No scrolling: [重开控制窗] and [退出应用] are always on the first screen (D44).
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        OptionRow(stringResource(R.string.settings_language), "settings/lang") {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)),
                settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound") {
            val level = if (settings.muted) 0 else settings.volume
            Segments(listOf(0 to stringResource(R.string.sound_mute), 1 to stringResource(R.string.sound_low), 2 to stringResource(R.string.sound_mid), 3 to stringResource(R.string.sound_high)),
                level, "settings/sound", { v -> upd { if (v == 0) it.copy(muted = true) else it.copy(muted = false, volume = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt") {
            val i = Settings.TILTS.indexOf(settings.tilt).coerceAtLeast(0)
            SecondaryButton("− " + stringResource(R.string.tilt_less), "settings/tilt-less", onClick = { upd { it.copy(tilt = Settings.TILTS[(i - 1).coerceAtLeast(0)]) } },
                enabled = i > 0, modifier = Modifier.weight(1f))
            Txt(stringResource(R.string.tilt_value, settings.tilt), "settings/tilt-value", style = PicoTheme.typography.titleMedium, align = TextAlign.Center, modifier = Modifier.width(64.dp))
            SecondaryButton(stringResource(R.string.tilt_more) + " +", "settings/tilt-more", onClick = { upd { it.copy(tilt = Settings.TILTS[(i + 1).coerceAtMost(Settings.TILTS.lastIndex)]) } },
                enabled = i < Settings.TILTS.lastIndex, modifier = Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.recall() }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        // D26: quit; the confirmation opens in the same place (no second panel, no scrolling).
        if (!Ui.exitConfirm) {
            SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, modifier = Modifier.fillMaxWidth(), danger = true)
        } else {
            Txt(stringResource(R.string.confirm_exit), "settings/exit-q", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-cancel", onClick = { Ui.exitConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.confirm_yes_exit), "settings/exit-yes", onClick = { exitApp(context, navigator, graph) }, modifier = Modifier.weight(1f), danger = true)
            }
        }
        Txt(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

/** D26 exit: release, close BOTH windows (D44), remove every task, then end the process. */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    Log.i(TAG, "EXIT release + close + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground(); graph.sounds.release(); graph.window.detach() }
    val closeBoard = graph.window.closeBoard
    runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
    runCatching { closeBoard?.invoke() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
    Log.i(TAG, "EXIT closed control + board (board closer=${closeBoard != null})")
    val main = Handler(Looper.getMainLooper())
    main.postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Log.i(TAG, "EXIT finishAndRemoveTask")
        main.postDelayed({ exitProcess(0) }, 300)
    }, 400)
}

/** The native TabBar with labels already resolved in the app language (kept last in the file: no string lookups below). */
@Composable
private fun ControlTabBar(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                // the label follows the tab's own content colour (selected tabs turn white; tab-label-color gate)
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium, color = LocalContentColor.current) },
            )
        }
    }
}
