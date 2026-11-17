package io.embodit.marblehop.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.marblehop.R
import io.embodit.marblehop.domain.BoardGeometry
import io.embodit.marblehop.domain.PlayState
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.platform.Graph
import io.embodit.marblehop.platform.TAG
import io.embodit.marblehop.ui.Containers
import io.embodit.marblehop.ui.SignText
import io.embodit.marblehop.ui.board.input.BoardInputListener
import io.embodit.marblehop.ui.board.input.HoleTargets
import io.embodit.marblehop.ui.board.input.InputSource
import io.embodit.marblehop.ui.board.input.Offset3M
import io.embodit.marblehop.ui.board.input.boardInput
import io.embodit.marblehop.ui.theme.Palette
import io.embodit.marblehop.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    /** The same listener the SpatialView uses: debug replays hand real hits to it (board-input README §4). */
    @Volatile var listener: BoardInputListener<Int>? = null
}

private const val SIGN = "sign"

/** Hand travel -> held-marble travel (D1 / input.drag: the hand, never the head; a little gain so you needn't reach far). */
const val DRAG_GAIN = 1.5f

/** The board's input listener: every input (controller ray, hand ray, eye-hand, debug replays) goes through here. */
internal class BoardListener(private val graph: Graph) : BoardInputListener<Int> {
    override fun onTap(target: Int?, source: InputSource, position: Offset3D?) {
        Log.i(TAG, "PINCH src=${source.label} hole=${target?.let(StarBoard::name) ?: "-"}")
        graph.flow.tap(target)
    }

    override fun onDragStart(target: Int, source: InputSource): Boolean = graph.flow.dragStart(target)

    override fun onDragMove(totalM: Offset3M) {
        val st = graph.flow.state.value
        val (du, dv) = BoardGeometry(graph.settings.value.tilt).dragOnPlane(totalM.x, totalM.y, totalM.z, st.facing)
        graph.flow.dragMove(du * DRAG_GAIN, dv * DRAG_GAIN)
    }

    /** Shared Space does not hand gaze to the app (board-input README); the system HoverEffect on the groove / marble shows it. */
    override fun onHover(target: Int?, source: InputSource) {}

    override fun onDragEnd() = graph.flow.dragEnd()
    override fun onDragCancel() = graph.flow.dragCancel()
}

/**
 * The board volume (default container, 1.08 x 1.16 x 1.16 m). Opens the control window beside it through the level
 * gate, shows the board and routes pinch / trigger taps and drags on the 121 holes to GameFlow.
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    val listener = remember(graph) { BoardListener(graph) }

    DisposableEffect(navigator) {
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
            close = { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
        )
        graph.window.closeBoard = { navigator.closeWindowContainer() }
        graph.window.request("start")
        onDispose {
            graph.window.detach()
            runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
        }
    }

    // Leaving for the background stops the computer and saves; coming back resumes (design container.lifecycle).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        SceneHolder.listener = listener
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            SceneHolder.listener = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            // Factory template board-input (D50): system taps, short drags turned back into taps, real drags; all three
            // inputs (controller ray, hand ray, eye-hand). Targets resolve up the parent chain to hole / groove / marble.
            .boardInput(HoleTargets, listener),
        attachments = {
            AttachmentPanel(SIGN) { SignRoot(graph) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            // S00: a plain disc and two plain balls first (2 resources), then the real board.
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design states S00 "平圆盘 + 素球兜底形状")
                    val disc = MeshResource.createCylinder(radius = BoardGeometry.BOARD_R, height = BoardGeometry.THICK)
                    // gate:allow geometry S00 loading stand-in marbles
                    val ball = MeshResource.createSphere(radius = BoardGeometry.MARBLE_R)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.69f, 0.54f, 0.37f, 1f)); setRoughness(0.7f) }
                    Triple(disc, ball, mat)
                }.getOrNull()
            }
            quick?.let { (d, b, m) -> scene.showFallback(d, b, m) }
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            // QA only (debug broadcast `s00hold on`): keep the S00 stand-in for 10 s on the next launch so it can be captured.
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); delay(10_000) }
            scene.install(resources, attachments.entity(SIGN))
        },
        update = { _, _ -> scene.apply(state, settings) },
    )
}

/** The sign is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun SignRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(s.language) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContext provides base.localized(s.language)) { Sign(st, graph) }
    }
}

/**
 * The status sign beyond the far edge (spatialLayout.sign: 0.58 x 0.075 m, 28 mm letters, read-only): one line from
 * the same source as the window's status line (D38). Its dp size comes from the metres via the length converter.
 */
@Composable
private fun Sign(state: PlayState, graph: Graph) {
    val window = graph.window
    val conv = LocalPhysicalLengthConverter.current
    val mPerDp = with(conv) { conv.dpToLength(100.dp, LengthUnit.Meters) } / 100f
    val w = (BoardGeometry.SIGN_W / mPerDp).dp
    val h = (BoardGeometry.SIGN_H / mPerDp).dp
    // 28 mm cap-to-baseline letters: font size ≈ 0.028 / 0.72 m
    val fontSp = (0.028f / 0.72f / mPerDp).sp
    // restored note and alerts expire on their own: tick so the sign re-reads the clock
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.restored, state.alert) { while (state.restored || state.alert != null) { delay(250); tick++ } }
    LaunchedEffect(Unit) { Log.i(TAG, "SIGN mPerDp=$mPerDp size=${w}x$h font=$fontSp") }
    Box(
        modifier = Modifier.size(w, h).clip(RoundedCornerShape(h / 2)).background(Color(0xF2FBF6EC)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> Text(androidx.compose.ui.res.stringResource(R.string.level_hint), color = Palette.Ink, fontSize = fontSp * 0.6f, textAlign = TextAlign.Center)
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("chip") }) {
                // design-style: inherited-content-color Button
                Text(androidx.compose.ui.res.stringResource(R.string.open_panel), fontSize = fontSp * 0.6f)
            }
            else -> {
                val line = SignText.of(state, tick)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(h * 0.2f), modifier = Modifier.padding(horizontal = h * 0.4f)) {
                    line.camp?.let { c -> Box(Modifier.size(h * 0.42f).clip(CircleShape).background(Palette.Seat[c])) }
                    val text = line.text()
                    Text(text, color = if (line.alert) Palette.ErrorText else Palette.Ink, fontSize = fontSp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW sign/line \"$text\"") })
                }
            }
        }
    }
}
