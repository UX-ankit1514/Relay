package app.relay.demo

import app.relay.data.AuthService
import app.relay.data.AuthState
import app.relay.data.AuthUser
import app.relay.data.KeyValue
import app.relay.ui.auth.DemoCast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The uid a demo persona signs in with. Matches the emulator convention ("demo-priya"). */
fun demoUid(email: String): String = "demo-" + email.substringBefore('@').lowercase()

/**
 * Demo version sign-in: no Google, no server. Picking a persona signs in instantly; the choice is
 * remembered in this browser. The first visit starts as Priya so the story opens on her project.
 */
class DemoAuthService(private val kv: KeyValue) : AuthService {
    private val _state = MutableStateFlow(initial())
    override val state: StateFlow<AuthState> = _state

    override val demoMode: Boolean get() = true

    val user: AuthUser? get() = (_state.value as? AuthState.SignedIn)?.user

    override suspend fun signInWithGoogle() {
        val priya = DemoCast.first()
        signInAsDemoPersona(priya.name, priya.email)
    }

    override suspend fun signOut() {
        kv.put(KEY, "")
        _state.value = AuthState.SignedOut
    }

    override suspend fun signInAsDemoPersona(name: String, email: String) {
        delay(200) // long enough to read "Switching to …"
        kv.put(KEY, email)
        _state.value = AuthState.SignedIn(AuthUser(demoUid(email), name, email, null))
    }

    private fun initial(): AuthState {
        val stored = kv.get(KEY)
        if (stored == "") return AuthState.SignedOut
        val p = DemoCast.firstOrNull { it.email.equals(stored, ignoreCase = true) } ?: DemoCast.first()
        return AuthState.SignedIn(AuthUser(demoUid(p.email), p.name, p.email, null))
    }

    companion object { const val KEY = "relay.demo.user" }
}
