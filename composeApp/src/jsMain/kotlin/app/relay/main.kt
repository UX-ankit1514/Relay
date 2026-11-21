package app.relay

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.relay.data.RelayRepository
import app.relay.firebase.CallableBackend
import app.relay.firebase.FirebaseAuthService
import app.relay.firebase.FirestoreDb
import app.relay.platform.BrowserPlatform
import app.relay.platform.BrowserRouter
import app.relay.ui.AppServices
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val platform = BrowserPlatform()
    val services = AppServices(
        auth = FirebaseAuthService(),
        repo = RelayRepository(FirestoreDb(), CallableBackend(), platform),
        router = BrowserRouter(),
        platform = platform,
    )
    onWasmReady {
        document.getElementById("loading")?.remove()
        ComposeViewport(document.body!!) { App(services) }
    }
}
