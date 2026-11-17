package io.embodit.marblehop.data

import android.content.Context
import android.util.Log
import io.embodit.marblehop.domain.GameStore
import io.embodit.marblehop.domain.Language
import io.embodit.marblehop.domain.Opponent
import io.embodit.marblehop.domain.StarBoard
import io.embodit.marblehop.domain.SaveCodec
import io.embodit.marblehop.domain.SavedGame
import io.embodit.marblehop.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt, written atomically after every move: a temp file, a read-back check, then a rename; the
 * previous good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == game) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("MarbleHop", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putInt("volume", s.volume)
            .putBoolean("muted", s.muted)
            .putInt("tilt", s.tilt)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putBoolean("coachDone", s.coachDone)
            .putInt("lastPlayers", s.lastPlayers)
            .putString("lastOpponent", s.lastOpponent.name)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            volume = prefs.getInt("volume", 3).coerceIn(1, 3),
            muted = prefs.getBoolean("muted", false),
            tilt = prefs.getInt("tilt", 75).takeIf { it in Settings.TILTS } ?: 75,
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            coachDone = prefs.getBoolean("coachDone", false),
            lastPlayers = prefs.getInt("lastPlayers", 2).takeIf { it in StarBoard.PLAYER_COUNTS } ?: 2,
            lastOpponent = Opponent.valueOf(prefs.getString("lastOpponent", Opponent.EASY.name)!!),
        )
    }.getOrElse { Settings() }
}
