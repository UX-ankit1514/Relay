package app.relay.firebase

import app.relay.config.RelayConfig
import app.relay.data.DocLimits
import app.relay.data.FileStore
import app.relay.data.PickedFile
import app.relay.firebase.js.storage.connectStorageEmulator
import app.relay.firebase.js.storage.getDownloadURL
import app.relay.firebase.js.storage.getStorage
import app.relay.firebase.js.storage.ref
import app.relay.firebase.js.storage.uploadBytes
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLInputElement
import kotlin.coroutines.resume

class FirebaseFileStore : FileStore {
    private val storage: dynamic by lazy {
        val st = getStorage(FirebaseBootstrap.app)
        if (RelayConfig.useEmulators) connectStorageEmulator(st, "127.0.0.1", 9199)
        st
    }

    override suspend fun pick(): List<PickedFile> = suspendCancellableCoroutine { cont ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.multiple = true
        input.accept = DocLimits.ACCEPT
        var done = false
        fun finish(list: List<PickedFile>) { if (!done) { done = true; cont.resume(list) } }
        input.onchange = {
            val files = input.files
            val out = mutableListOf<PickedFile>()
            if (files != null) for (i in 0 until files.length) {
                val f = files.item(i)!!
                out += PickedFile(f.name, f.size.toLong(), f.type, f)
            }
            finish(out)
        }
        input.addEventListener("cancel", { finish(emptyList()) })
        input.click()
    }

    override suspend fun upload(path: String, file: PickedFile): String {
        val metadata: dynamic = js("({})")
        metadata.contentType = file.mimeType.ifBlank { "application/octet-stream" }
        val r = ref(storage, path)
        uploadBytes(r, file.handle, metadata).unsafeCast<kotlin.js.Promise<dynamic>>().await()
        return getDownloadURL(r).unsafeCast<kotlin.js.Promise<dynamic>>().await() as String
    }
}
