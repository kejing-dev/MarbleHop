package io.embodit.marblehop.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.marblehop.domain.BoardGeometry
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The few shared meshes / materials / shapes (design budget: <= 12 meshes, <= 12 materials). Creating a resource
 * waits on the renderer (~1 s round trip on the emulator), so everything is created concurrently on Dispatchers.IO
 * (Xiangqi BoardScene.createResources) and kept global until [close].
 *
 * Geometry note (B12 / ui_gates "geometry"): the design package's asset list makes the board and the glass marbles
 * procedural on purpose (gate B item 2: "弹珠和木盘走程序化，不用 Tripo"); every create* call says so on its gate:allow line.
 * No Blender on this machine, so the board is the design's fallback: a walnut slab, a beech face disc, a rim ring and a
 * dark groove disc per hole (camp holes tinted toward the seat colour).
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var slab: MeshResource; private set
    lateinit var face: MeshResource; private set
    lateinit var rim: MeshResource; private set
    lateinit var groove: MeshResource; private set
    lateinit var marble: MeshResource; private set
    lateinit var dot: MeshResource; private set
    lateinit var ring: MeshResource; private set
    lateinit var bigRing: MeshResource; private set
    lateinit var pip: MeshResource; private set
    lateinit var spark: MeshResource; private set

    lateinit var walnut: Material; private set
    lateinit var beech: Material; private set
    lateinit var grooveMat: Material; private set
    /** Groove discs of each camp, tinted 25 % toward the seat colour (environmentHarmony.camps). */
    lateinit var campGroove: List<Material>; private set
    lateinit var marbleMat: List<Material>; private set
    lateinit var ghostMat: List<Material>; private set
    lateinit var trailMat: List<Material>; private set
    lateinit var pipMat: List<Material>; private set
    lateinit var pipGlow: List<Material>; private set
    lateinit var pipIdle: Material; private set
    lateinit var stepDot: Material; private set
    lateinit var hopRing: Material; private set
    lateinit var selectRing: Material; private set
    lateinit var chainDot: Material; private set
    lateinit var red: Material; private set
    lateinit var landGlow: List<Material>; private set

    lateinit var holeShape: ShapeResource; private set
    lateinit var marbleShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        const val GROOVE_R = 0.0245f
        const val GROOVE_H = 0.0015f

        private fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

        private fun pbr(color: Color, roughness: Float, metallic: Float = 0f, glow: Float = 0f): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                setRoughness(roughness)
                setMetallic(metallic)
                if (glow > 0f) setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
            }

        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        /** Builds everything concurrently off the main thread. */
        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            val seat = Palette.Seat
            val lists = listOf(
                async(io) { r.campGroove = seat.map { pbr(mix(Palette.Groove, it, 0.25f), roughness = 0.6f).keep() } },
                // glossy candy glass: opaque glaze (N1 / N11: no transmission, no 60 blended spheres)
                async(io) { r.marbleMat = seat.map { pbr(it, roughness = 0.08f, glow = 0.06f).keep() } },
                async(io) { r.ghostMat = seat.map { unlit(it, 0.55f).keep() } },
                async(io) { r.trailMat = seat.map { unlit(mix(it, Color.White, 0.35f), 0.7f).keep() } },
                async(io) { r.pipMat = seat.map { pbr(it, roughness = 0.3f).keep() } },
                async(io) { r.pipGlow = seat.map { pbr(it, roughness = 0.2f, glow = 0.9f).keep() } },
                async(io) { r.landGlow = seat.map { unlit(it, 0.8f).keep() } },
            )
            listOf(
                // gate:allow geometry board slab: design entities3d.board fallback "createCylinder 平盘" (no Blender on this machine)
                async(io) { r.slab = MeshResource.createCylinder(radius = BoardGeometry.BOARD_R, height = BoardGeometry.THICK).keep() },
                // gate:allow geometry beech face disc of the procedural board (gate B item 2)
                async(io) { r.face = MeshResource.createCylinder(radius = BoardGeometry.FACE_R, height = 0.002f).keep() },
                async(io) { r.rim = MeshResource.createTorus(outerRingRadius = BoardGeometry.BOARD_R, innerRingRadius = BoardGeometry.FACE_R).keep() },
                // gate:allow geometry groove disc: the visible hole marker every hole collider carries (system hover draws on it, D50)
                async(io) { r.groove = MeshResource.createCylinder(radius = GROOVE_R, height = GROOVE_H).keep() },
                // gate:allow geometry glass marble: a sphere is a marble (gate B item 2, design assets "弹珠 SDK createSphere")
                async(io) { r.marble = MeshResource.createSphere(radius = BoardGeometry.MARBLE_R).keep() },
                // gate:allow geometry lit-hole dot / chain dots / trail sparks: abstract markers (design entities3d.markers)
                async(io) { r.dot = MeshResource.createCylinder(radius = 0.012f, height = 0.002f).keep() },
                async(io) { r.ring = MeshResource.createTorus(outerRingRadius = 0.020f, innerRingRadius = 0.016f).keep() },
                async(io) { r.bigRing = MeshResource.createTorus(outerRingRadius = 0.030f, innerRingRadius = 0.026f).keep() },
                // gate:allow geometry seat pips on the rim (design entities3d.pips "createCylinder ×6")
                async(io) { r.pip = MeshResource.createCylinder(radius = 0.012f, height = 0.006f).keep() },
                // gate:allow geometry trail sparks behind a hop (design entities3d.trail, abstract light)
                async(io) { r.spark = MeshResource.createSphere(radius = 0.006f).keep() },
                async(io) { r.holeShape = ShapeResource.createBox(Vector3(StarBoard.PITCH * 0.92f, 0.012f, StarBoard.PITCH * 0.86f)).keep() },
                async(io) { r.marbleShape = ShapeResource.createBox(Vector3(2 * BoardGeometry.MARBLE_R, 2 * BoardGeometry.MARBLE_R, 2 * BoardGeometry.MARBLE_R)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                async(io) { r.walnut = pbr(Palette.Walnut, roughness = 0.55f).keep() },
                async(io) { r.beech = pbr(Palette.Beech, roughness = 0.62f).keep() },
                async(io) { r.grooveMat = pbr(Palette.Groove, roughness = 0.6f).keep() },
                async(io) { r.pipIdle = pbr(Palette.Beech, roughness = 0.5f).keep() },
                // marker colours measured against what is behind them (ui-audit contrast table): pale sky / gold on the dark groove,
                // white chain dots and a dark red ring on the beech face, all >= 3:1
                async(io) { r.stepDot = unlit(Palette.StepDot).keep() },
                async(io) { r.hopRing = unlit(Palette.HopRing).keep() },
                async(io) { r.selectRing = unlit(Color(0xFFFFFFFF)).keep() },
                async(io) { r.chainDot = unlit(Color(0xFFFFFAEE)).keep() },
                async(io) { r.red = unlit(Palette.RedRing).keep() },
            ).awaitAll()
            lists.awaitAll()
            r
        }
    }
}
