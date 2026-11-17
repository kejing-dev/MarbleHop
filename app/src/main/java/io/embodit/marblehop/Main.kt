package io.embodit.marblehop

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import androidx.compose.foundation.layout.Box
import io.embodit.marblehop.ui.Containers
import io.embodit.marblehop.ui.board.BoardScreen
import io.embodit.marblehop.ui.control.ControlWindow
import io.embodit.marblehop.ui.theme.MarbleTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the board volume
 * (default container, 1.08 x 1.16 x 1.16 m from the manifest) and the control window (Planar 560 x 540 dp,
 * min 520 x 500, max 900 x 720) placed to its right by the system.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            MarbleTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 540.dp),
            // First open and every reopen: beside the board volume (profile D44 2026-11-05: Shared Space has no "bring it in
            // front"; Placement.none() does not follow the head and lands on top of the board).
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            MarbleTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 500.dp, maxWidth = 900.dp, maxHeight = 720.dp)) { ControlWindow() }
            }
        }
    }
