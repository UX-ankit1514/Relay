@file:JsModule("firebase/firestore")

package app.relay.firebase.js.firestore

import kotlin.js.Promise

external fun getFirestore(app: dynamic): dynamic
external fun connectFirestoreEmulator(db: dynamic, host: String, port: Int)
external fun doc(db: dynamic, path: String): dynamic
external fun doc(collectionRef: dynamic): dynamic
external fun collection(db: dynamic, path: String): dynamic
external fun getDoc(ref: dynamic): Promise<dynamic>
external fun getDocs(query: dynamic): Promise<dynamic>
external fun onSnapshot(ref: dynamic, onNext: (dynamic) -> Unit, onError: (dynamic) -> Unit): () -> Unit
external fun query(ref: dynamic, vararg constraints: dynamic): dynamic
external fun where(field: String, op: String, value: dynamic): dynamic
external fun orderBy(field: String, direction: String): dynamic
external fun limit(n: Int): dynamic
external fun writeBatch(db: dynamic): dynamic
