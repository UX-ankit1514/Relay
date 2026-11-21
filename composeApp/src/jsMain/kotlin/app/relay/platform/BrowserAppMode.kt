package app.relay.platform

import app.relay.data.AppMode
import app.relay.data.FileStore
import app.relay.data.KeyValue
import app.relay.data.PickedFile
import app.relay.demo.DemoAuthService
import app.relay.demo.DemoDb
import kotlinx.browser.window

/** localStorage. Failures (private mode, quota) are ignored: the app still works, it just forgets. */
class BrowserKeyValue : KeyValue {
    override fun get(key: String): String? = runCatching { window.localStorage.getItem(key) }.getOrNull()
    override fun put(key: String, value: String?) {
        runCatching { if (value == null) window.localStorage.removeItem(key) else window.localStorage.setItem(key, value) }
    }
}

/**
 * Original ⇄ Demo version, remembered in this browser. `?mode=demo` / `?mode=original` in any link sets it.
 * Switching reloads the app so each version starts clean.
 */
class BrowserAppMode(val kv: KeyValue = BrowserKeyValue()) : AppMode {
    init {
        val params = js("new URLSearchParams(window.location.search)")
        val m = params.get("mode") as String?
        if (m != null) {
            if (m == "demo" || m == "original") kv.put(KEY, m)
            params.delete("mode")
            val rest = params.toString() as String
            window.history.replaceState(null, "", window.location.pathname + (if (rest.isEmpty()) "" else "?$rest") + window.location.hash)
        }
    }

    override val isDemo: Boolean = kv.get(KEY) == "demo"

    override fun switchTo(demo: Boolean) {
        kv.put(KEY, if (demo) "demo" else "original")
        window.location.assign("/")
    }

    override fun resetDemo() {
        kv.put(DemoDb.STORAGE_KEY, null)
        kv.put(DemoAuthService.KEY, null)
        window.location.assign("/")
    }

    companion object { const val KEY = "relay.mode" }
}

/** Demo version uploads stay in this browser tab: the file is shown from a local object URL, nothing is sent anywhere. */
class DemoFileStore(private val picker: FileStore) : FileStore {
    override suspend fun pick(): List<PickedFile> = picker.pick()
    override suspend fun upload(path: String, file: PickedFile): String =
        js("URL").createObjectURL(file.handle) as String
}
