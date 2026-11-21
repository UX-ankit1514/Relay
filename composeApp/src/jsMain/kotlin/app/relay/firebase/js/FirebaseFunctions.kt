@file:JsModule("firebase/functions")

package app.relay.firebase.js.functions

external fun getFunctions(app: dynamic, region: String): dynamic
external fun connectFunctionsEmulator(functions: dynamic, host: String, port: Int)
external fun httpsCallable(functions: dynamic, name: String): dynamic
