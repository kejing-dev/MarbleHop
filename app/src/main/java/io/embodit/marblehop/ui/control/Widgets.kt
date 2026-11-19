package io.embodit.marblehop.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import androidx.compose.foundation.selection.toggleable
import com.pico.spatial.ui.design.Text
import io.embodit.marblehop.platform.TAG
import io.embodit.marblehop.ui.theme.Palette

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Int = 72) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = height.dp, minWidth = 120.dp),
        shape = RoundedCornerShape((height / 2).dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, tall: Boolean = false) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = if (tall) 64.dp else 52.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(if (tall) 32.dp else 26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Palette.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) Palette.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) Palette.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A key with a glyph over its word (arrow keys >= 52 dp wide x 72 dp; B19 icons carry text). */
@Composable
internal fun KeyButton(glyph: String, word: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 52.dp),
        // the 320 dp window gives each key ≈ 64 dp: the default side padding left "Down" no room (OVERFLOW play/down/word)
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // design-style: inherited-content-color Button
            Txt(glyph, "$tag/glyph", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary)
            Txt(word, "$tag/word", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelPrimary)
        }
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Palette.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        Box(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, rowHeight: Dp = 56.dp, content: @Composable RowScope.() -> Unit) {
    // narrow window (v0.1.2, 320 dp): the label sits above its control so the control gets the full width
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false, twoLines: Boolean = false) {
    // B19: the control itself is >= 48 dp tall (the default track is ~37 dp).
    SegmentControl(modifier = modifier.height(if (twoLines) 72.dp else 52.dp)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    // twoLines: long labels (English opponent names) wrap inside the 52 dp segment instead of clipping (320 dp window)
                    Txt(label, "$tag/segment", style = if (small || twoLines) PicoTheme.typography.labelMedium else PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary,
                        maxLines = if (twoLines) 2 else 1, align = androidx.compose.ui.text.style.TextAlign.Center)
                },
            )
        }
    }
}

/** A whole >= 48 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (QA q1-4, D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A seat-coloured dot with a dark rim so light colours (lemon) show on light glass. */
@Composable
internal fun SeatDot(camp: Int, size: Dp = 18.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.Seat[camp.coerceIn(0, 5)]).border(1.5.dp, Palette.SeatInk[camp.coerceIn(0, 5)], CircleShape))
}
