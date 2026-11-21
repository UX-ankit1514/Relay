@file:JsModule("firebase/auth")

package app.relay.firebase.js.auth

import kotlin.js.Promise

external fun getAuth(app: dynamic): dynamic
external fun connectAuthEmulator(auth: dynamic, url: String, options: dynamic)
external fun signInWithPopup(auth: dynamic, provider: dynamic): Promise<dynamic>
external fun signOut(auth: dynamic): Promise<dynamic>
external fun onAuthStateChanged(auth: dynamic, next: (dynamic) -> Unit): () -> Unit
external fun signInWithCredential(auth: dynamic, credential: dynamic): Promise<dynamic>

external class GoogleAuthProvider {
    fun setCustomParameters(params: dynamic): GoogleAuthProvider
    companion object {
        fun credential(idToken: String?, accessToken: String? = definedExternally): dynamic
    }
}
