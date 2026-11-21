package app.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.relay.data.AuthService
import app.relay.data.AuthUser
import app.relay.data.Platform
import app.relay.data.RelayRepository
import app.relay.data.Router
import app.relay.design.CenteredColumn
import app.relay.design.Page
import app.relay.design.PillAppearance
import app.relay.design.PillButton
import app.relay.design.RText
import app.relay.design.RelayColors
import app.relay.design.RelayTheme
import app.relay.design.Space
import app.relay.design.StatusKind
import app.relay.design.StatusPill
import app.relay.design.TopBar
import app.relay.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AppServices(
    val auth: AuthService,
    val repo: RelayRepository,
    val router: Router,
    val platform: Platform,
)

val LocalServices = staticCompositionLocalOf<AppServices> { error("AppServices not provided") }
val LocalUser = staticCompositionLocalOf<AuthUser?> { null }

/** Runs a suspend action with busy + error state. Errors are shown in words; nothing is shown as saved unless the write resolved. */
@Stable
class ActionRunner(private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun run(block: suspend () -> Unit) {
        if (running) return // duplicate clicks never create duplicate writes
        scope.launch {
            running = true; error = null
            try { block() } catch (t: Throwable) { error = friendlyError(t) } finally { running = false }
        }
    }
}

@Composable
fun rememberAction(): ActionRunner {
    val scope = rememberCoroutineScope()
    return remember { ActionRunner(scope) }
}

fun friendlyError(t: Throwable): String {
    val m = t.message ?: t.toString()
    return when {
        m.contains("popup-closed-by-user") || m.contains("cancelled-popup-request") -> "Sign-in was cancelled. Try again when you’re ready."
        m.contains("popup-blocked") -> "Your browser blocked the Google sign-in window. Allow pop-ups for this site and try again."
        m.contains("permission-denied") || m.contains("PERMISSION_DENIED") -> "Access not permitted."
        m.contains("unavailable") || m.contains("network") || m.contains("Failed to fetch") -> "Connection lost. Nothing was saved — try again when you’re online."
        m.contains("unauthenticated") -> "Your session expired. Sign in again."
        m.contains("not-found") -> m.substringAfter("not-found").trim(' ', ':', '.').ifEmpty { "That item no longer exists." }
        m.contains("failed-precondition") -> m.substringAfter("failed-precondition").trim(' ', ':').ifEmpty { "That action isn’t allowed right now." }
        m.contains("internal") -> "Something went wrong on our side. Nothing was changed — try again."
        else -> m.removePrefix("FirebaseError: ").take(240)
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) RText("! $message", RelayTheme.type.metadata, color = RelayColors.textOnAccent)
}

@Composable
fun LoadingPage(label: String = "Loading…") {
    Page(header = { TopBar(onLogo = {}) }) {
        CenteredColumn { RText(label, RelayTheme.type.ui, color = RelayColors.textSecondary) }
    }
}

/** Single-message pages: expired links, access not permitted, not found. */
@Composable
fun MessagePage(
    status: Pair<String, StatusKind>,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
    meta: String? = null,
    header: @Composable () -> Unit = { val home = LocalNavHome.current; TopBar(onLogo = home) },
) {
    Page(header = { header() }) {
        CenteredColumn {
            StatusPill(status.first, status.second)
            RText(title, RelayTheme.type.heading)
            RText(body, RelayTheme.type.body, color = RelayColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                primary?.let { PillButton(it.first, it.second) }
                secondary?.let { PillButton(it.first, it.second, appearance = PillAppearance.Ghost) }
            }
            if (meta != null) RText(meta, RelayTheme.type.metadata, color = RelayColors.textSecondary)
        }
    }
}

val LocalNavHome = staticCompositionLocalOf<() -> Unit> { {} }

fun initialsOf(name: String): String = name.split(' ', '@').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

fun Router.go(route: Route) = navigate(route)
