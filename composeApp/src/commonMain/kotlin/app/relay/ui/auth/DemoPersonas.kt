package app.relay.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

private data class Persona(val name: String, val role: String, val email: String)

// Emails are fixed: existing emulator data is keyed on them.
private val personas = listOf(
    Persona("Priya", "client", "priya@example.com"),
    Persona("Rohan", "freelancer", "rohan@example.com"),
    Persona("Maya", "replacement", "maya@example.com"),
)

/**
 * Demo mode (local emulators only): one-click fake Google accounts for the PRD personas.
 * [excludeEmail] hides the persona that is already signed in, so the switcher only offers real switches.
 */
@Composable
fun DemoPersonas(after: suspend () -> Unit = {}, excludeEmail: String? = null) {
    val s = LocalServices.current
    if (!s.auth.demoMode) return
    val action = rememberAction()
    OutlinePanel {
        StatusPill("Demo mode · local emulator", StatusKind.Neutral)
        RText("Sign in as a fictional persona (fake Google accounts that exist only on this machine).", RelayTheme.type.metadata, color = RelayColors.textSecondary)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            personas.filter { !it.email.equals(excludeEmail, ignoreCase = true) }.forEach { p ->
                PillButton("${p.name} · ${p.role}", { action.run { s.auth.signInAsDemoPersona(p.name, p.email); after() } }, Modifier.fillMaxWidth(), appearance = PillAppearance.Ghost, size = PillSize.Compact, enabled = !action.running)
            }
        }
        ErrorText(action.error)
    }
}
