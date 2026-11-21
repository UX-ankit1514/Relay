package app.relay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/** A live Firestore value with an explicit "loaded" flag and a readable error. */
class Live<T>(val value: T, val loaded: Boolean, val error: String?)

@Composable
fun <T> rememberLive(key: Any?, initial: T, source: () -> Flow<T>): Live<T> {
    var value by remember(key) { mutableStateOf(initial) }
    var loaded by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        source().catch { error = friendlyError(it); loaded = true }.collect { value = it; loaded = true; error = null }
    }
    return Live(value, loaded, error)
}
