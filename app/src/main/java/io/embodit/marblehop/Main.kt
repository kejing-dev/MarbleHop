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
import io.embodit.marblehop.domain.ControlPlacement
import io.embodit.marblehop.ui.Containers
import io.embodit.marblehop.ui.board.BoardScreen
import io.embodit.marblehop.ui.control.ControlWindow
import io.embodit.marblehop.ui.theme.MarbleTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the board volume
 * (default container, 1.02 x 1.16 x 1.16 m from the manifest) and the control window placed to its right by the system.
 * v0.1.2 (QA #2 q2-1): the window is 320 x 640 dp (the narrowest Planar window) and the volume 1.02 m wide, fixed (min = max), so all of it stays inside the default view on
 * every cold start (ControlPlacement + ControlPlacementTest); 560 x 540 dp hung up to ~+48° off the right edge.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            MarbleTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = ControlPlacement.WINDOW_W_DP.dp, height = ControlPlacement.WINDOW_H_DP.dp),
            // First open and every reopen: beside the board volume (profile D44 2026-11-05: Shared Space has no "bring it in
            // front"; Placement.none() does not follow the head and lands on top of the board).
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.takeIf { !io.embodit.marblehop.ui.Ui.debugPanelCentre }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            MarbleTheme {
                Box(Modifier.windowConstraints(minWidth = ControlPlacement.WINDOW_W_DP.dp, minHeight = ControlPlacement.WINDOW_H_DP.dp, maxWidth = ControlPlacement.WINDOW_W_DP.dp, maxHeight = ControlPlacement.WINDOW_H_DP.dp)) { ControlWindow() }
            }
        }
    }
