package app.relay.platform

import app.relay.config.RelayConfig
import app.relay.data.CheckoutRequest
import app.relay.data.CheckoutResult
import app.relay.data.Platform
import app.relay.data.Router
import app.relay.nav.Route
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLScriptElement
import kotlin.coroutines.resume
import kotlin.js.Date

class BrowserRouter : Router {
    private val _route = MutableStateFlow(current())
    override val route: StateFlow<Route> = _route

    init {
        window.addEventListener("popstate", { _route.value = current() })
    }

    private fun current(): Route = Route.parse(window.location.pathname, window.location.search)

    override fun navigate(route: Route, replace: Boolean) {
        if (replace) window.history.replaceState(null, "", route.path) else window.history.pushState(null, "", route.path)
        _route.value = route
        window.scrollTo(0.0, 0.0)
    }

    override fun back() = window.history.back()
}

class BrowserPlatform : Platform {
    override fun now(): Long = Date.now().toLong()

    override fun formatDateTime(epochMillis: Long): String {
        if (epochMillis <= 0) return "—"
        val opts: dynamic = js("({day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false})")
        return Date(epochMillis.toDouble()).asDynamic().toLocaleString("en-IN", opts) as String
    }

    override fun formatDate(epochMillis: Long): String {
        if (epochMillis <= 0) return "—"
        val opts: dynamic = js("({day: 'numeric', month: 'short', year: 'numeric'})")
        return Date(epochMillis.toDouble()).asDynamic().toLocaleDateString("en-IN", opts) as String
    }

    override fun copyToClipboard(text: String) {
        window.navigator.asDynamic().clipboard?.writeText(text)
    }

    override fun openExternal(url: String) {
        val safe = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        window.open(safe, "_blank", "noopener,noreferrer")
    }

    override fun origin(): String = window.location.origin

    override fun randomToken(bytes: Int): String {
        val arr = Uint8Array(bytes)
        window.asDynamic().crypto.getRandomValues(arr)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val sb = StringBuilder()
        for (i in 0 until bytes) sb.append(alphabet[(arr[i].toInt() and 0xFF) % alphabet.length])
        return sb.toString()
    }

    override val razorpayKeyId: String = RelayConfig.razorpayKeyId

    private suspend fun ensureCheckoutScript(): Boolean = suspendCancellableCoroutine { cont ->
        if (window.asDynamic().Razorpay != undefined) { cont.resume(true); return@suspendCancellableCoroutine }
        val s = document.createElement("script") as HTMLScriptElement
        s.src = "https://checkout.razorpay.com/v1/checkout.js"
        s.onload = { cont.resume(true) }
        s.onerror = { _, _, _, _, _ -> cont.resume(false) }
        document.head?.appendChild(s)
    }

    /** Opens Razorpay Standard Checkout. The returned callback is NEVER trusted as "paid" — the server verifies. */
    override suspend fun openCheckout(request: CheckoutRequest): CheckoutResult {
        if (!ensureCheckoutScript()) return CheckoutResult.Failed("Razorpay checkout could not be loaded.")
        return suspendCancellableCoroutine { cont ->
            var done = false
            // Razorpay keeps its modal open after a failed attempt so the person can retry.
            // Remember the failure and resolve only on success or when the modal closes.
            var lastFailure: String? = null
            fun finish(r: CheckoutResult) { if (!done) { done = true; cont.resume(r) } }
            val options: dynamic = js("({})")
            options.key = request.keyId
            options.order_id = request.orderId
            options.amount = request.amountMinor.toDouble()
            options.currency = request.currency
            options.name = request.name
            options.description = request.description
            options.prefill = js("({})")
            options.prefill.name = request.prefillName
            options.prefill.email = request.prefillEmail
            options.theme = js("({color: '#17191C'})")
            options.handler = { resp: dynamic ->
                finish(CheckoutResult.Completed(resp.razorpay_order_id as String, resp.razorpay_payment_id as String, resp.razorpay_signature as String))
            }
            options.modal = js("({})")
            options.modal.ondismiss = { finish(lastFailure?.let { CheckoutResult.Failed(it) } ?: CheckoutResult.Dismissed) }
            val ctor = window.asDynamic().Razorpay
            val rzp = js("Reflect").construct(ctor, arrayOf(options))
            rzp.on("payment.failed") { resp: dynamic -> lastFailure = (resp?.error?.description as? String) ?: "Payment failed" }
            rzp.open()
        }
    }
}
