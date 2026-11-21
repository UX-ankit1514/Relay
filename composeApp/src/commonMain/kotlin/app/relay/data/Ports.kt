package app.relay.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Platform "ports". commonMain holds all business logic against these interfaces;
 * jsMain provides the Firebase / browser implementations. Adding another target
 * later (Android, iOS, desktop) means implementing these, not rewriting the app.
 */

data class AuthUser(val uid: String, val displayName: String, val email: String, val photoUrl: String?)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: AuthUser) : AuthState
}

interface AuthService {
    val state: StateFlow<AuthState>
    /** Google is the only sign-in method. */
    suspend fun signInWithGoogle()
    suspend fun signOut()
    /** True only against the local Firebase emulators: fake Google accounts for demo personas. */
    val demoMode: Boolean get() = false
    suspend fun signInAsDemoPersona(name: String, email: String) {}
}

data class Where(val field: String, val op: String, val value: JsonElement)

data class QuerySpec(
    val collection: String,
    val where: List<Where> = emptyList(),
    val orderBy: String? = null,
    val descending: Boolean = false,
    val limit: Int? = null,
)

sealed interface WriteOp {
    val path: String
    data class Set(override val path: String, val data: JsonObject, val merge: Boolean = false) : WriteOp
    data class Update(override val path: String, val data: JsonObject) : WriteOp
    data class Delete(override val path: String) : WriteOp
}

class DocSnapshot(val id: String, val data: JsonObject)

/** Thin, platform-neutral view of Cloud Firestore. */
interface Db {
    fun newId(): String
    suspend fun get(path: String): JsonObject?
    fun observe(path: String): Flow<JsonObject?>
    suspend fun query(q: QuerySpec): List<DocSnapshot>
    fun observeQuery(q: QuerySpec): Flow<List<DocSnapshot>>
    /** Atomic batch write. */
    suspend fun write(ops: List<WriteOp>)
}

/** Callable Cloud Functions (server-only work: payments, AI, privileged transitions). */
interface Backend {
    suspend fun call(name: String, data: JsonObject): JsonElement
}

/** Result of opening Razorpay Standard Checkout. The server, not this callback, decides "paid". */
sealed interface CheckoutResult {
    data class Completed(val orderId: String, val paymentId: String, val signature: String) : CheckoutResult
    data object Dismissed : CheckoutResult
    data class Failed(val reason: String) : CheckoutResult
}

data class CheckoutRequest(
    val keyId: String,
    val orderId: String,
    val amountMinor: Long,
    val currency: String,
    val name: String,
    val description: String,
    val prefillName: String,
    val prefillEmail: String,
)

interface Platform {
    fun now(): Long
    fun formatDateTime(epochMillis: Long): String
    fun formatDate(epochMillis: Long): String
    fun copyToClipboard(text: String)
    fun openExternal(url: String)
    fun origin(): String
    fun randomToken(bytes: Int = 24): String
    val razorpayKeyId: String
    suspend fun openCheckout(request: CheckoutRequest): CheckoutResult
}

interface Router {
    val route: StateFlow<app.relay.nav.Route>
    fun navigate(route: app.relay.nav.Route, replace: Boolean = false)
    fun back()
}
