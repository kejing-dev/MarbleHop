package io.embodit.marblehop.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.marblehop.domain.Language
import io.embodit.marblehop.platform.Graph
import java.util.Locale

/** design-package theme.colorScheme + gameSpec.pieces: beech board, candy glass marbles, mint primary, amber accent. */
object Palette {
    val Primary = Color(0xFF2E7D6B)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFBDEBDD)
    val OnPrimaryContainer = Color(0xFF0C3A30)
    val Secondary = Color(0xFFB07A3A)
    val SecondaryContainer = Color(0xFFF3DDC2)
    val OnSecondaryContainer = Color(0xFF4A2E0E)
    val Tertiary = Color(0xFFD99A2B)
    val OnTertiary = Color(0xFF2B1D05)
    val TertiaryContainer = Color(0xFFFFE2A8)
    val OnTertiaryContainer = Color(0xFF3E2A00)
    val Error = Color(0xFFC8423F)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass; the brighter Error stays for 3D marks and button fills. */
    val ErrorText = Color(0xFF7A1A18)

    // 3D (environmentHarmony): beech face, walnut rim, groove wall
    val Beech = Color(0xFFB08A5E)
    val Walnut = Color(0xFF7A5638)
    val Groove = Color(0xFF94714A)

    /** Seat colours by camp (gameSpec.coords): 0 mint · 1 lemon · 2 indigo · 3 amber · 4 cherry · 5 grape. */
    val Seat = listOf(Color(0xFF5FD6B0), Color(0xFFEAE062), Color(0xFF4C63D2), Color(0xFFF2B544), Color(0xFFE0555F), Color(0xFF9B6BD6))
    /** Seat colours dark enough for text / rims on light glass. */
    val SeatInk = listOf(Color(0xFF1F6E57), Color(0xFF6B6410), Color(0xFF2C3E9E), Color(0xFF7A5200), Color(0xFF9E2630), Color(0xFF5B3696))
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). [language] localises resources below this point
 * through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun MarbleTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = Palette.OnPrimary,
        labelPrimary = Palette.Ink,
        labelSecondary = Palette.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = Palette.Error,
        alert = Palette.Tertiary,
        passable = Palette.Primary,
        interaction = Palette.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
