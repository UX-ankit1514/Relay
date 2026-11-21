package app.relay.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.design.CategoryLabel
import app.relay.design.CenteredColumn
import app.relay.design.Page
import app.relay.design.PillButton
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTheme
import app.relay.design.TopBar
import app.relay.nav.Route
import app.relay.ui.ErrorText
import app.relay.ui.LocalServices
import app.relay.ui.rememberAction

/** F1-02 · Sign up / sign in — Google only. */
@Composable
fun SignInScreen(next: String?) {
    val s = LocalServices.current
    val action = rememberAction()
    val joining = next?.startsWith("/invite") == true || next?.startsWith("/h/") == true
    Page(header = { TopBar(onLogo = { s.router.navigate(Route.Landing) }) }) {
        CenteredColumn(width = 440.androidx_dp) {
            CategoryLabel(if (joining) "Sign in to continue" else "Create your account or sign in")
            RText(if (joining) "Continue to your project" else "Start your first project", RelayTheme.type.heading)
            RText(
                if (joining) "Sign in with Google to accept and save your agreement. You’ll return to the same page."
                else "Save your project and invite your freelancer. It takes about a minute.",
                RelayTheme.type.body, color = RelayColors.textSecondary,
            )
            PillButton(
                "Continue with Google",
                onClick = { action.run { s.auth.signInWithGoogle() } },
                modifier = Modifier.fillMaxWidth(),
                loading = action.running,
            )
            ErrorText(action.error)
            DemoPersonas()
            RText(
                "Relay uses Google sign-in only. We keep your name and email so everyone can see who agreed to what. This demo should use fictional project data.",
                RelayTheme.type.metadata, color = RelayColors.textSecondary,
            )
        }
    }
}

private val Int.androidx_dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
