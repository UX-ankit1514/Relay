@file:JsModule("firebase/storage")

package app.relay.firebase.js.storage

external fun getStorage(app: dynamic): dynamic
external fun connectStorageEmulator(storage: dynamic, host: String, port: Int)
external fun ref(storage: dynamic, path: String): dynamic
external fun uploadBytes(ref: dynamic, data: dynamic, metadata: dynamic): dynamic
external fun getDownloadURL(ref: dynamic): dynamic
