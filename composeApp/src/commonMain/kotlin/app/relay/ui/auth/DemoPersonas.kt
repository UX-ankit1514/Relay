package app.relay.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.design.OutlinePanel
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.PillSize
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTheme
import app.relay.design.Space
import app.relay.design.StatusKind
import app.relay.design.StatusPill
import app.relay.ui.ErrorText
import app.relay.ui.LocalServices
import app.relay.ui.rememberAction

/** Demo mode (local emulators only): one-click fake Google accounts for the PRD personas. */
@Composable
fun DemoPersonas(after: suspend () -> Unit = {}) {
    val s = LocalServices.current
    if (!s.auth.demoMode) return
    val action = rememberAction()
    OutlinePanel {
        StatusPill("Demo mode · local emulator", StatusKind.Neutral)
        RText("Sign in as a fictional persona (fake Google accounts that exist only on this machine).", RelayTheme.type.metadata, color = RelayColors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            listOf("Priya" to "priya@example.com", "Rohan" to "rohan@example.com", "Maya" to "maya@example.com").forEach { (n, e) ->
                PillButton(n, { action.run { s.auth.signInAsDemoPersona(n, e); after() } }, appearance = PillAppearance.Ghost, size = PillSize.Compact)
            }
        }
        ErrorText(action.error)
    }
}
