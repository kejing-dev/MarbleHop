package io.embodit.marblehop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.embodit.marblehop.R
import io.embodit.marblehop.domain.AlertKind
import io.embodit.marblehop.domain.GameFlow
import io.embodit.marblehop.domain.Phase
import io.embodit.marblehop.domain.PlayState

/**
 * The one status line (design panels.sign + content.signStrings): the 3D sign shows it, the window's turn line reads the
 * same source (D38). [seat] fills the "%1$s" with a seat name; [count] fills "%1$d".
 */
data class SignLine(val res: Int, val seat: Int? = null, val count: Int? = null, val camp: Int? = null, val alert: Boolean = false) {
    @Composable
    fun text(): String = when {
        seat != null -> stringResource(res, stringResource(seatName(seat)))
        count != null -> stringResource(res, count)
        else -> stringResource(res)
    }
}

fun seatName(camp: Int): Int = when (camp) {
    0 -> R.string.seat_0; 1 -> R.string.seat_1; 2 -> R.string.seat_2; 3 -> R.string.seat_3; 4 -> R.string.seat_4; else -> R.string.seat_5
}

object SignText {
    /** [now] is the uptime clock (the restored note lasts 1.5 s). */
    fun of(st: PlayState, @Suppress("UNUSED_PARAMETER") tick: Long = 0L, now: Long = android.os.SystemClock.uptimeMillis()): SignLine {
        val camp = st.camp
        st.alert?.let { a ->
            return SignLine(when (a.kind) { AlertKind.ILLEGAL -> R.string.sign_illegal; AlertKind.STUCK -> R.string.sign_stuck; AlertKind.CAMP -> R.string.sign_camp }, alert = true)
        }
        return when (st.phase) {
            Phase.LOADING -> SignLine(R.string.sign_loading)
            Phase.WELCOME -> SignLine(R.string.sign_welcome)
            Phase.SETUP -> SignLine(R.string.sign_setup)
            Phase.RESULT -> st.result?.winner.let { w -> SignLine(R.string.sign_win, seat = w ?: 0, camp = w) }
            Phase.ROTATING, Phase.HANDOFF -> SignLine(R.string.sign_handoff, seat = camp, camp = camp)
            Phase.THINKING -> SignLine(R.string.sign_thinking, seat = camp, camp = camp)
            Phase.ANIMATING -> {
                val a = st.anim
                when {
                    a == null -> SignLine(R.string.sign_turn, seat = camp, camp = camp)
                    a.move.hops == 2 -> SignLine(R.string.sign_chain_2, camp = a.camp)
                    a.move.hops == 3 -> SignLine(R.string.sign_chain_3, camp = a.camp)
                    a.move.hops > 3 -> SignLine(R.string.sign_chain_n, count = a.move.hops, camp = a.camp)
                    a.move.jump -> SignLine(R.string.sign_hop, seat = a.camp, camp = a.camp)
                    else -> SignLine(R.string.sign_step, seat = a.camp, camp = a.camp)
                }
            }
            Phase.DRAGGING -> SignLine(if (st.firstGame) R.string.sign_coach_3 else R.string.sign_dragging, camp = camp)
            Phase.PREVIEW -> SignLine(if (st.firstGame) R.string.sign_coach_3 else R.string.sign_preview, camp = camp)
            Phase.SELECTED -> SignLine(if (st.firstGame) R.string.sign_coach_2 else R.string.sign_selected, camp = camp)
            else -> when {
                st.restored && now - st.restoredAt < GameFlow.RESTORED_MS -> SignLine(R.string.sign_restored, camp = camp)
                st.passed != null -> SignLine(R.string.sign_passed, seat = st.passed, camp = st.passed)
                st.firstGame -> SignLine(R.string.sign_coach_1, seat = camp, camp = camp)
                else -> SignLine(R.string.sign_turn, seat = camp, camp = camp)
            }
        }
    }
}
