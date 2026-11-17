package io.embodit.marblehop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "MarbleBoard"
    const val CONTROL = "MarbleControl"
    const val CONTROL_TAG = "control"
}

/** The three pages of the native TabBar (windowModel: 对局 / 规则 / 设置; the new-game setup lives on the game page). */
enum class Page(val stateId: String) { PLAY("S03"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
    var rulesView by mutableStateOf(0)
}
