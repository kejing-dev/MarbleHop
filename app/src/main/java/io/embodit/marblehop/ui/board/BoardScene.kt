package io.embodit.marblehop.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.marblehop.domain.AlertKind
import io.embodit.marblehop.domain.BoardGeometry
import io.embodit.marblehop.domain.GameFlow
import io.embodit.marblehop.domain.Phase
import io.embodit.marblehop.domain.PlayState
import io.embodit.marblehop.domain.Settings
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.domain.V3
import io.embodit.marblehop.platform.TAG
import io.embodit.marblehop.ui.board.input.BoardNames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; per-frame motion
 * (hops, lifts, the lit-hole ripple, the board turning and tilting, pips breathing) runs in [onFrame] from the ECS
 * system [BoardFrameSystem] (never in Compose).
 *
 * ```
 * root (volume centre)
 * ├ tiltRoot (near bottom edge of the board, turned about X by the tilt)
 * │ └ boardRoot (board bottom centre, turned by facing x 60° so the current player's camp is near)
 * │   ├ slab, face, rim, 6 seat pips
 * │   ├ 121 hole holders "hole:<i>" (collider + Interactable + HoverEffect) each with a visible groove "groove:<i>"
 * │   ├ 60 marbles "marble:<hole>" (collider + Interactable + HoverEffect on the sphere)
 * │   └ lit dots / hop rings, select ring, ghost, chain dots, red ring, landing glow, trail sparks
 * └ sign (AttachmentPanel, upright beyond the far edge)
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var sign: Entity? = null

    private var state = PlayState()
    private var settings = Settings()

    private val holes = ArrayList<Entity>()
    private val grooves = ArrayList<Entity>()
    private var holesBuilt = 0
    private val marbles = ArrayList<ModelEntity>()
    /** hole -> marble entity drawn there (the animating marble is moved by [onFrame]). */
    private val atHole = HashMap<Int, ModelEntity>()
    private val pips = ArrayList<ModelEntity>()
    private val litDots = ArrayList<ModelEntity>()
    private val litRings = ArrayList<ModelEntity>()
    /** Which marker shows which target hole this update. */
    private val litShown = ArrayList<Pair<ModelEntity, Int>>()
    private val chainDots = ArrayList<ModelEntity>()
    private val sparks = ArrayList<ModelEntity>()
    private var selectRing: ModelEntity? = null
    private var ghost: ModelEntity? = null
    private var redRing: ModelEntity? = null
    private var landGlow: ModelEntity? = null

    // animation
    private var clock = 0f
    private var tiltShown = 75f
    private var tiltFrom = 75f
    private var tiltTarget = 75
    private var tiltStart = 0L
    private var litStart = 0L
    private var litKey = ""
    private var selectStart = 0L
    private var selectKey: Int? = null
    private var winStart = 0L
    private var landAt = 0L
    private var landHole = -1
    private var landCamp = 0
    private var lastAnimKey: Any? = null
    private val trails = ArrayList<Pair<Long, List<V3>>>()
    private var trailCamp = 0
    private val trailDone = HashSet<Int>()

    init {
        root.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        place(tiltRoot, BoardGeometry.PIVOT)
        place(boardRoot, V3(0f, 0f, -BoardGeometry.BOARD_R))
        setTilt(75f)
    }

    val ready: Boolean get() = built

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    private fun setTilt(deg: Float) {
        tiltShown = deg
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (deg * PI / 180.0).toFloat()))
        sign?.let { place(it, signCentreAt(deg)) }
    }

    private fun signCentreAt(deg: Float): V3 {
        val th = (deg * PI / 180.0).toFloat()
        val p = V3(0f, BoardGeometry.TOP + 0.006f, -2 * BoardGeometry.BOARD_R - 0.02f)
        val c = cos(th); val s = sin(th)
        return BoardGeometry.PIVOT + V3(p.x, p.y * c - p.z * s, p.y * s + p.z * c) + V3(0f, BoardGeometry.SIGN_H / 2f, 0f)
    }

    private fun setYaw(deg: Float) = boardRoot.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), (deg * PI / 180.0).toFloat()))

    /** S00 stand-in: a plain disc and two plain balls, shown until the real board is built. */
    fun showFallback(disc: MeshResource, ball: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        val board = ModelEntity(disc, material).apply { setName("fallbackBoard") }
        place(board, V3(0f, BoardGeometry.THICK / 2, 0f))
        boardRoot.addChild(board)
        val balls = listOf(StarBoard.APEX[0], StarBoard.APEX[3]).map { h ->
            ModelEntity(ball, material).apply { setName("fallbackBall"); place(this, marblePos(h)); boardRoot.addChild(this) }
        }
        fallback = listOf(board) + balls
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun marblePos(h: Int, lift: Float = 0f): V3 = V3(StarBoard.u(h), BoardGeometry.MARBLE_Y + lift, -StarBoard.v(h))

    private fun onFace(h: Int, lift: Float): V3 = V3(StarBoard.u(h), BoardGeometry.TOP + lift, -StarBoard.v(h))

    fun install(r: SceneResources, signEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        sign = signEntity
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(ModelEntity(r.slab, r.walnut).apply { setName("slab"); place(this, V3(0f, BoardGeometry.THICK / 2 - 0.001f, 0f)) })
        boardRoot.addChild(ModelEntity(r.face, r.beech).apply { setName("face"); place(this, V3(0f, BoardGeometry.TOP - 0.001f, 0f)) })
        boardRoot.addChild(ModelEntity(r.rim, r.walnut).apply {
            setName("rim"); place(this, V3(0f, BoardGeometry.TOP, 0f)); transform().setScaleVector(Vector3(1f, 0.36f, 1f))
        })
        val g = BoardGeometry()
        for (c in 0 until 6) pips += ModelEntity(r.pip, r.pipIdle).apply { setName("pip:$c"); place(this, g.pip(c)); boardRoot.addChild(this) }
        repeat(60) { i ->
            marbles += ModelEntity(r.marble, r.marbleMat[0]).apply {
                setName("marble:-")
                components.set(CollisionComponent(collisionShape = listOf(r.marbleShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
                enabled = false
                boardRoot.addChild(this)
            }
        }
        repeat(40) { litDots += ModelEntity(r.dot, r.stepDot).apply { setName("litStep"); enabled = false; boardRoot.addChild(this) } }
        repeat(80) { litRings += ModelEntity(r.ring, r.hopRing).apply { setName("litHop"); enabled = false; boardRoot.addChild(this) } }
        repeat(48) { chainDots += ModelEntity(r.dot, r.chainDot).apply { setName("chain"); enabled = false; transform().setScaleVector(Vector3(0.35f, 1f, 0.35f)); boardRoot.addChild(this) } }
        repeat(48) { sparks += ModelEntity(r.spark, r.trailMat[0]).apply { setName("spark"); enabled = false; boardRoot.addChild(this) } }
        selectRing = ModelEntity(r.bigRing, r.selectRing).apply { setName("selectRing"); enabled = false; boardRoot.addChild(this) }
        ghost = ModelEntity(r.marble, r.ghostMat[0]).apply { setName("ghost"); enabled = false; boardRoot.addChild(this) }
        redRing = ModelEntity(r.bigRing, r.red).apply { setName("redRing"); enabled = false; boardRoot.addChild(this) }
        landGlow = ModelEntity(r.bigRing, r.landGlow[0]).apply { setName("landGlow"); enabled = false; boardRoot.addChild(this) }
        sign?.let { root.addChild(it) }

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        setTilt(settings.tilt.toFloat()); tiltTarget = settings.tilt
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms (holes follow in 6 frames)")
        apply(state, settings, force = true)
    }

    /** 121 hole holders, a few per frame (resource-light; each entity is a renderer round trip on the emulator). */
    private fun buildHoles(count: Int) {
        val r = res ?: return
        val end = min(StarBoard.SIZE, holesBuilt + count)
        for (i in holesBuilt until end) {
            // D50: the system hover is drawn only on the visible mesh under the hit collider, so every hole is a holder
            // (collider tiling the face: 0.92 x 0.86 pitch) with its visible groove disc as a child.
            val holder = Entity().apply {
                setName(BoardNames.hole(i))
                components.set(CollisionComponent(collisionShape = listOf(r.holeShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
            }
            place(holder, onFace(i, 0f))
            val camp = StarBoard.CAMP[i]
            val groove = ModelEntity(r.groove, if (camp >= 0) r.campGroove[camp] else r.grooveMat).apply {
                setName(BoardNames.groove(i))
                place(this, V3(0f, SceneResources.GROOVE_H / 2, 0f))
            }
            holder.addChild(groove)
            grooves += groove
            boardRoot.addChild(holder)
            holes += holder
        }
        holesBuilt = end
        if (holesBuilt == StarBoard.SIZE) Log.i(TAG, "HOLES built ${holes.size}")
    }

    // ---- state ----

    fun apply(next: PlayState, s: Settings, force: Boolean = false) {
        val prev = state
        state = next
        settings = s
        if (!built) return
        val r = res ?: return
        if (s.tilt != tiltTarget) { tiltFrom = tiltShown; tiltTarget = s.tilt; tiltStart = SystemClock.uptimeMillis() }

        // marbles: one per occupied hole; the animating one is placed by onFrame
        val pos = next.position
        atHole.clear()
        var k = 0
        if (pos != null) for (h in 0 until StarBoard.SIZE) {
            val camp = pos[h]
            if (camp < 0) continue
            val e = marbles.getOrNull(k++) ?: break
            e.setName(BoardNames.marble(h))
            e.components[ModelComponent::class.java]?.materials?.set(0, r.marbleMat[camp])
            e.enabled = true
            atHole[h] = e
            place(e, marblePos(h))
        }
        while (k < marbles.size) { marbles[k].enabled = false; marbles[k].setName("marble:-"); k++ }

        // lit holes (all reachable holes light at once, rippling out from the marble: animation.reachable)
        val key = "${next.selected}:${next.targets.keys.sorted()}"
        if (key != litKey) { litKey = key; litStart = SystemClock.uptimeMillis() }
        litShown.clear()
        val showLit = next.phase !in setOf(Phase.ANIMATING, Phase.THINKING, Phase.RESULT)
        var di = 0; var ri = 0
        if (showLit) for (m in next.targets.values) {
            val e = (if (m.jump) litRings.getOrNull(ri++) else litDots.getOrNull(di++)) ?: continue
            litShown += e to m.to
        }
        val used = litShown.map { it.first }.toSet()
        (litDots + litRings).forEach { it.enabled = it in used }

        if (next.selected != selectKey) { selectKey = next.selected; selectStart = SystemClock.uptimeMillis() }

        // ghost + chain
        val camp = next.camp
        ghost?.components?.get(ModelComponent::class.java)?.materials?.set(0, r.ghostMat[camp.coerceIn(0, 5)])
        val chainMove = next.preview?.let { next.targets[it] }
        val chainPts = ArrayList<V3>()
        if (chainMove != null) {
            var a = chainMove.from
            for (b in chainMove.path) {
                for (t in listOf(0.25f, 0.5f, 0.75f)) chainPts += onFace(a, 0.0035f).lerp(onFace(b, 0.0035f), t)
                a = b
            }
        }
        chainDots.forEachIndexed { i, e -> val p = chainPts.getOrNull(i); if (p != null) { place(e, p); e.enabled = true } else e.enabled = false }

        // a newly started move: remember the trail colour, start the clock
        val anim = next.anim
        if (anim != null && anim !== lastAnimKey) { lastAnimKey = anim; trailCamp = anim.camp; trailDone.clear() }
        sparks.forEach { it.components[ModelComponent::class.java]?.materials?.set(0, r.trailMat[trailCamp]) }
        if (prev.anim != null && next.anim == null) { landAt = SystemClock.uptimeMillis(); landHole = prev.anim.move.to; landCamp = prev.anim.camp }
        landGlow?.components?.get(ModelComponent::class.java)?.materials?.set(0, r.landGlow[landCamp])

        if (next.result != null && prev.result == null) winStart = SystemClock.uptimeMillis()

        // seat pips: seated camps in their colour, the player to move glows
        val seats = next.config?.seats?.toSet().orEmpty()
        pips.forEachIndexed { c, e ->
            val mat = when {
                c !in seats -> r.pipIdle
                next.result == null && c == camp && next.phase !in setOf(Phase.SETUP, Phase.WELCOME) -> r.pipGlow[c]
                next.result?.winner == c -> r.pipGlow[c]
                else -> r.pipMat[c]
            }
            e.components[ModelComponent::class.java]?.materials?.set(0, mat)
        }
        onFrame(0f)
    }

    // ---- per frame ----

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        if (holesBuilt < StarBoard.SIZE) buildHoles((StarBoard.SIZE + 5) / 6)
        val now = SystemClock.uptimeMillis()
        val st = state

        // tilt (350 ms easeInOutCubic)
        if (tiltShown != tiltTarget.toFloat()) {
            val t = ((now - tiltStart) / 350f).coerceIn(0f, 1f)
            setTilt(tiltFrom + (tiltTarget - tiltFrom) * easeInOutCubic(t))
        }

        // facing (S17: 60° steps, 400 ms each, at most 600 ms, the short way)
        val yaw = if (st.phase == Phase.ROTATING) {
            val ms = GameFlow.rotateMs(st.rotateFrom, st.facing).coerceAtLeast(1)
            val t = ((now - st.rotateStartedAt).toFloat() / ms).coerceIn(0f, 1f)
            var d = ((st.facing - st.rotateFrom) % 6 + 6) % 6
            if (d > 3) d -= 6
            (st.rotateFrom + d * easeInOutCubic(t)) * 60f
        } else st.facing * 60f
        setYaw(yaw)

        // the moving marble
        val anim = st.anim
        if (anim != null) {
            var elapsed = now - anim.startedAt
            st.freezeMs?.let { elapsed = min(elapsed, it) }
            val e = atHole[anim.move.to]
            if (e != null) place(e, animPos(anim.move, elapsed))
        }

        // trails fade (300 ms) and get pruned
        trails.removeAll { now - it.first > 300 }
        var si = 0
        for ((t0, pts) in trails) {
            val k = 1f - (now - t0) / 300f
            for (p in pts) { val sp = sparks.getOrNull(si++) ?: break; place(sp, p); sp.transform().setScaleVector(Vector3(k.coerceIn(0.05f, 1f), k.coerceIn(0.05f, 1f), k.coerceIn(0.05f, 1f))); sp.enabled = true }
        }
        while (si < sparks.size) sparks[si++].enabled = false

        // selected marble lifts 12 mm (120 ms) with a white ring; a dragged one rides 3 cm above the face
        val sel = st.selected
        val drag = st.drag
        val ring = selectRing
        if (drag != null) {
            val p = BoardGeometry().plane(drag.px, drag.py, BoardGeometry.MARBLE_Y - BoardGeometry.TOP + BoardGeometry.DRAG_LIFT)
            atHole[drag.from]?.let { place(it, p) }
            ring?.enabled = false
        } else if (sel != null && st.phase in setOf(Phase.SELECTED, Phase.PREVIEW, Phase.ALERT)) {
            val t = ((now - selectStart) / 120f).coerceIn(0f, 1f)
            val lift = BoardGeometry.PICK_LIFT * (1f - (1f - t) * (1f - t) * (1f - t))
            atHole[sel]?.let { place(it, marblePos(sel, lift)) }
            ring?.let { place(it, onFace(sel, 0.003f)); it.enabled = true }
        } else ring?.enabled = false

        // lit holes ripple in: each ring of distance 18 ms later, 120 ms fade-in
        val from = st.selected
        for ((e, h) in litShown) {
            val ringIdx = if (from != null) StarBoard.dist(from, h) else 0
            val t = ((now - litStart - ringIdx * 18) / 120f).coerceIn(0.01f, 1f)
            place(e, onFace(h, 0.0028f))
            val k = (if (h == st.preview) 1.25f else 1f) * t
            e.transform().setScaleVector(Vector3(k, 1f, k))
        }

        // ghost marble in the previewed / snapped hole
        ghost?.let { g ->
            val p = st.preview
            if (p != null && st.phase in setOf(Phase.PREVIEW, Phase.DRAGGING, Phase.ALERT)) { place(g, marblePos(p)); g.enabled = true } else g.enabled = false
        }

        // red ring flashes twice (300 ms) on a refused hole; a stuck marble shakes ±3 mm
        val al = st.alert
        redRing?.let { rr ->
            val age = if (al != null) now - al.at else Long.MAX_VALUE
            val on = al != null && age < 300 && (age / 75) % 2 == 0L
            if (on) { place(rr, onFace(al!!.hole, 0.004f)); rr.enabled = true } else rr.enabled = false
            if (al != null && al.kind == AlertKind.STUCK && age < 180) {
                val dx = 0.003f * sin(age / 180f * 4f * PI.toFloat())
                atHole[al.hole]?.let { e -> val p = marblePos(al.hole); place(e, V3(p.x + dx, p.y, p.z)) }
            }
        }

        // landing glow in the seat colour (200 ms)
        landGlow?.let { lg ->
            val age = now - landAt
            if (landHole >= 0 && age < 200) {
                place(lg, onFace(landHole, 0.003f)); val k = 1f + age / 400f; lg.transform().setScaleVector(Vector3(k, 1f, k)); lg.enabled = true
            } else lg.enabled = false
        }

        // the winner's ten marbles light up one after another (80 ms apart, 240 ms each), round and round
        val res = st.result
        val pos = st.position
        if (res != null && pos != null) {
            val mine = (0 until StarBoard.SIZE).filter { pos[it] == res.winner }
            val cycle = mine.size * 80L + 600L
            val tt = (now - winStart) % cycle
            mine.forEachIndexed { i, h ->
                val local = tt - i * 80L
                val k = if (local in 0..240) 1f + 0.18f * sin(local / 240f * PI.toFloat()) else 1f
                atHole[h]?.transform()?.setScaleVector(Vector3(k, k, k))
            }
        } else marbles.forEach { if (it.enabled) it.transform().setScaleVector(Vector3(1f, 1f, 1f)) }

        // the glowing pip breathes (1.2 s)
        val k = 1f + 0.12f * sin(clock * 2f * PI.toFloat() / 1.2f)
        pips.forEachIndexed { c, e -> e.transform().setScaleVector(if (c == st.camp && st.result == null && st.inGame) Vector3(k, 1f, k) else Vector3(1f, 1f, 1f)) }
    }

    /** Where a moving marble is [elapsed] ms after its move started (step 220 ms; hops 260 ms + 60 ms pauses). */
    private fun animPos(m: io.embodit.marblehop.domain.Move, elapsed: Long): V3 {
        val g = BoardGeometry()
        if (!m.jump) {
            val t = (elapsed / GameFlow.STEP_MS.toFloat()).coerceIn(0f, 1f)
            return g.arc(m.from, m.to, easeInOutCubic(t), BoardGeometry.STEP_APEX)
        }
        val per = GameFlow.HOP_MS + GameFlow.HOP_GAP_MS
        val k = (elapsed / per).toInt().coerceAtMost(m.path.lastIndex)
        val a = if (k == 0) m.from else m.path[k - 1]
        val b = m.path[k]
        val local = elapsed - k * per
        val t = (local / GameFlow.HOP_MS.toFloat()).coerceIn(0f, 1f)
        // a finished hop leaves a 300 ms trail of sparks along its arc
        if (t >= 1f && trailDone.add(k)) trails += SystemClock.uptimeMillis() to (1..6).map { g.arc(a, b, it / 7f, BoardGeometry.HOP_APEX) }
        return g.arc(a, b, t, BoardGeometry.HOP_APEX)
    }

    private fun easeInOutCubic(t: Float): Float = if (t < 0.5f) 4 * t * t * t else 1 - Math.pow((-2.0 * t + 2), 3.0).toFloat() / 2

    fun evidence(): String {
        val g = BoardGeometry(tiltTarget)
        return "tilt=$tiltTarget shown=$tiltShown holes=$holesBuilt marbles=${atHole.size} lit=${litShown.size} sign=${g.signCentre()} " +
            "near=${g.boardToVolume(g.hole(StarBoard.APEX[0]))} far=${g.boardToVolume(g.hole(StarBoard.APEX[3]))} facing=${state.facing}"
    }

    /** The real entities a hit on hole [h] can report, deepest first: its groove disc, then the marble sitting there (debug replay). */
    fun entitiesAt(h: Int): List<Entity> = listOfNotNull(grooves.getOrNull(h), atHole[h])

    fun dispose() {
        res?.close()
        res = null
    }
}
