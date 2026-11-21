package app.relay.firebase

import app.relay.config.RelayConfig
import app.relay.data.AuthService
import app.relay.data.AuthState
import app.relay.data.AuthUser
import app.relay.data.Backend
import app.relay.data.Db
import app.relay.data.DocSnapshot
import app.relay.data.QuerySpec
import app.relay.data.WriteOp
import app.relay.firebase.js.app.initializeApp
import app.relay.firebase.js.auth.GoogleAuthProvider
import app.relay.firebase.js.auth.connectAuthEmulator
import app.relay.firebase.js.auth.getAuth
import app.relay.firebase.js.auth.onAuthStateChanged
import app.relay.firebase.js.auth.signInWithCredential
import app.relay.firebase.js.auth.signInWithPopup
import app.relay.firebase.js.auth.signOut
import app.relay.firebase.js.firestore.collection
import app.relay.firebase.js.firestore.connectFirestoreEmulator
import app.relay.firebase.js.firestore.doc
import app.relay.firebase.js.firestore.getDoc
import app.relay.firebase.js.firestore.getDocs
import app.relay.firebase.js.firestore.getFirestore
import app.relay.firebase.js.firestore.limit
import app.relay.firebase.js.firestore.onSnapshot
import app.relay.firebase.js.firestore.orderBy
import app.relay.firebase.js.firestore.query
import app.relay.firebase.js.firestore.where
import app.relay.firebase.js.firestore.writeBatch
import app.relay.firebase.js.functions.connectFunctionsEmulator
import app.relay.firebase.js.functions.getFunctions
import app.relay.firebase.js.functions.httpsCallable
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Firebase bootstrap. Config is injected at build time from local.properties (see README). */
object FirebaseBootstrap {
    val app: dynamic by lazy {
        val options = js("({})")
        options.apiKey = RelayConfig.firebaseApiKey
        options.authDomain = RelayConfig.firebaseAuthDomain
        options.projectId = RelayConfig.firebaseProjectId
        options.storageBucket = RelayConfig.firebaseStorageBucket
        options.messagingSenderId = RelayConfig.firebaseMessagingSenderId
        options.appId = RelayConfig.firebaseAppId
        initializeApp(options)
    }
    val auth: dynamic by lazy {
        val a = getAuth(app)
        if (RelayConfig.useEmulators) connectAuthEmulator(a, "http://127.0.0.1:9099", js("({disableWarnings: true})"))
        a
    }
    val firestore: dynamic by lazy {
        val f = getFirestore(app)
        if (RelayConfig.useEmulators) connectFirestoreEmulator(f, "127.0.0.1", 8080)
        f
    }
    val functions: dynamic by lazy {
        val fn = getFunctions(app, "asia-south1")
        if (RelayConfig.useEmulators) connectFunctionsEmulator(fn, "127.0.0.1", 5001)
        fn
    }
}

internal val relayJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal fun JsonElement.toJs(): dynamic = JSON.parse(this.toString())
internal fun jsToJsonObject(value: dynamic): JsonObject =
    if (value == null || value == undefined) JsonObject(emptyMap())
    else relayJson.parseToJsonElement(JSON.stringify(value)).jsonObject

class FirebaseAuthService : AuthService {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state

    init {
        onAuthStateChanged(FirebaseBootstrap.auth) { user ->
            _state.value = if (user == null) AuthState.SignedOut else AuthState.SignedIn(
                AuthUser(
                    uid = user.uid as String,
                    displayName = (user.displayName as String?) ?: (user.email as String?)?.substringBefore('@') ?: "You",
                    email = (user.email as String?) ?: "",
                    photoUrl = user.photoURL as String?,
                )
            )
        }
    }

    override suspend fun signInWithGoogle() {
        val provider = GoogleAuthProvider()
        provider.setCustomParameters(js("({prompt: 'select_account'})"))
        signInWithPopup(FirebaseBootstrap.auth, provider).await()
    }

    override suspend fun signOut() {
        signOut(FirebaseBootstrap.auth).await()
    }

    override val demoMode: Boolean get() = RelayConfig.useEmulators

    /** Emulator only: the Auth emulator accepts an unsigned JSON "Google ID token" for fake accounts. */
    override suspend fun signInAsDemoPersona(name: String, email: String) {
        check(RelayConfig.useEmulators) { "Demo personas are only available against the local emulators." }
        val claims = js("({})")
        claims.sub = "demo-" + email.substringBefore('@')
        claims.email = email
        claims.email_verified = true
        claims.name = name
        val credential = GoogleAuthProvider.credential(JSON.stringify(claims))
        signInWithCredential(FirebaseBootstrap.auth, credential).await()
    }
}

class FirestoreDb : Db {
    private val db get() = FirebaseBootstrap.firestore

    override fun newId(): String = doc(collection(db, "_ids")).id as String

    override suspend fun get(path: String): JsonObject? {
        val snap = getDoc(doc(db, path)).await()
        return if (snap.exists() as Boolean) jsToJsonObject(snap.data()) else null
    }

    override fun observe(path: String): Flow<JsonObject?> = callbackFlow {
        val unsubscribe = onSnapshot(
            doc(db, path),
            { snap -> trySend(if (snap.exists() as Boolean) jsToJsonObject(snap.data()) else null) },
            { err -> close(RuntimeException(err?.message as? String ?: "Firestore listen failed")) },
        )
        awaitClose { unsubscribe() }
    }

    private fun buildQuery(q: QuerySpec): dynamic {
        val constraints = mutableListOf<dynamic>()
        q.where.forEach { constraints.add(where(it.field, it.op, it.value.toJs())) }
        q.orderBy?.let { constraints.add(orderBy(it, if (q.descending) "desc" else "asc")) }
        q.limit?.let { constraints.add(limit(it)) }
        return query(collection(db, q.collection), *constraints.toTypedArray())
    }

    private fun docs(snap: dynamic): List<DocSnapshot> {
        val out = mutableListOf<DocSnapshot>()
        val arr = snap.docs as Array<dynamic>
        arr.forEach { d -> out.add(DocSnapshot(d.id as String, jsToJsonObject(d.data()))) }
        return out
    }

    override suspend fun query(q: QuerySpec): List<DocSnapshot> = docs(getDocs(buildQuery(q)).await())

    override fun observeQuery(q: QuerySpec): Flow<List<DocSnapshot>> = callbackFlow {
        val unsubscribe = onSnapshot(
            buildQuery(q),
            { snap -> trySend(docs(snap)) },
            { err -> close(RuntimeException(err?.message as? String ?: "Firestore listen failed")) },
        )
        awaitClose { unsubscribe() }
    }

    override suspend fun write(ops: List<WriteOp>) {
        val batch = writeBatch(db)
        ops.forEach { op ->
            val ref = doc(db, op.path)
            when (op) {
                is WriteOp.Set -> if (op.merge) batch.set(ref, op.data.toJs(), js("({merge: true})")) else batch.set(ref, op.data.toJs())
                is WriteOp.Update -> batch.update(ref, op.data.toJs())
                is WriteOp.Delete -> batch.delete(ref)
            }
        }
        batch.commit().unsafeCast<kotlin.js.Promise<dynamic>>().await()
    }
}

class CallableBackend : Backend {
    override suspend fun call(name: String, data: JsonObject): JsonElement {
        val fn = httpsCallable(FirebaseBootstrap.functions, name)
        val result = fn(data.toJs()).unsafeCast<kotlin.js.Promise<dynamic>>().await()
        val payload = result.data
        return if (payload == null || payload == undefined) JsonObject(emptyMap()) else relayJson.parseToJsonElement(JSON.stringify(payload))
    }
}
