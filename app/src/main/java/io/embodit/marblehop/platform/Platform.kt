package io.embodit.marblehop.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.marblehop.data.FileGameStore
import io.embodit.marblehop.data.SettingsStore
import io.embodit.marblehop.domain.GameFlow
import io.embodit.marblehop.domain.Sound
import io.embodit.marblehop.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

const val TAG = "MarbleHop"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.marblehop.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        engineDispatcher = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { s, _ -> sounds.play(s) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = !s.muted
                sounds.volumeLevel = s.volume
            }
        }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Effects from assets/sounds (profile D51, template §15.1–15.2; build/sound-sources.md). The marble-into-groove
 * clicks are CC0 recordings of a glass marble landing on wood (freesound KaleidacousticsAudio, tools/import_marbles.py):
 * place / place_2 / place_3 rotate with ±5 % pitch; a hop mid-chain is the same family, lighter and a little higher;
 * home = two glass marbles touching (home / home_2); pick / back = the softer retract click. UI cues (no, edge, win, lose)
 * are CC0 interface recordings, quieter. Pre-selection / arrow moves / hover are silent. One sound of a kind at a time.
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastVariant = HashMap<String, Int>()
    private val lastStream = HashMap<String, Int>()

    private fun fileOf(s: Sound): String = when (s) {
        Sound.PLACE, Sound.HOP -> "place"
        Sound.HOME -> "home"
        Sound.PICK, Sound.BACK -> "retract"
        Sound.NO, Sound.EDGE -> "illegal"
        Sound.WIN -> "win"
        Sound.LOSE -> "lose"
    }

    /** Relative gains (design sound.list; files all peak at -1 dBFS). */
    private fun gainOf(s: Sound): Float = when (s) {
        Sound.PLACE -> 1f; Sound.HOP -> 0.8f; Sound.HOME -> 0.8f; Sound.PICK -> 0.55f; Sound.BACK -> 0.6f
        Sound.NO -> 0.6f; Sound.EDGE -> 0.3f; Sound.WIN -> 0.9f; Sound.LOSE -> 0.6f
    }

    private fun rateOf(s: Sound): Float = when (s) {
        Sound.HOP -> 1.06f + random.nextFloat() * 0.06f
        Sound.HOME -> 0.97f + random.nextFloat() * 0.06f
        Sound.PICK -> 1.12f
        Sound.PLACE, Sound.BACK -> 0.95f + random.nextFloat() * 0.10f
        else -> 1f
    }

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (base in Sound.entries.map { fileOf(it) }.toSet()) {
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[base] = found else Log.w(TAG, "no sound for $base")
        }
    }

    fun play(s: Sound) {
        if (!enabled) return
        val base = fileOf(s)
        val variants = ids[base] ?: return
        val vol = io.embodit.marblehop.domain.Settings.VOLUME_PERCENT[volumeLevel.coerceIn(1, 3)] / 100f * gainOf(s)
        var v = if (variants.size > 1) random.nextInt(variants.size) else 0
        if (variants.size > 1 && v == lastVariant[base]) v = (v + 1) % variants.size
        lastVariant[base] = v
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        lastStream[base]?.let { pool.stop(it) }
        lastStream[base] = pool.play(id, vol, vol, 1, 0, rateOf(s))
    }

    fun release() = pool.release()
}
