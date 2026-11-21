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
    val mode = app.relay.platform.BrowserAppMode()
    val router = BrowserRouter()
    // Demo version: ready-made story kept in this browser. Original: real Firebase data, Google sign-in.
    val services = if (mode.isDemo) {
        app.relay.demo.demoServices(platform, router, app.relay.platform.DemoFileStore(app.relay.firebase.FirebaseFileStore()), mode.kv, mode)
    } else AppServices(
        auth = FirebaseAuthService(),
        repo = RelayRepository(FirestoreDb(), CallableBackend(), platform),
        router = router,
        platform = platform,
        files = app.relay.firebase.FirebaseFileStore(),
        mode = mode,
    )
    onWasmReady {
        document.getElementById("loading")?.remove()
        ComposeViewport(document.body!!) { App(services) }
    }
}
