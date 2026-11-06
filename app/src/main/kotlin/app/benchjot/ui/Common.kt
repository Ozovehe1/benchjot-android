package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads `block` now and again after every write anywhere (the web's SWR revalidation). Keeps the last value while reloading.
 * `action` names what failed in the error text ("Couldn’t load the map. …"), as the web's userMessage(e, action).
 */
@Composable
fun <T> rememberLive(vararg keys: Any?, action: String? = null, block: suspend () -> T): LiveData<T> {
    val version by Backend.version.collectAsState()
    val state = remember(*keys) { LiveData<T>() }
    LaunchedEffect(*keys, version) {
        try {
            state.value = block()
            state.error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = Accounts.message(e, action)
        }
        state.loaded = true
    }
    return state
}

class LiveData<T> {
    var value by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
}

/** The web's window.prompt: a one-field dialog in the app's own look. */
@Composable
fun PromptDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = t.bg,
        title = { Text(title, style = Type.body, color = t.fg) },
        text = { UnderlineField(value, { value = it }, title, Modifier.focusRequester(focus)) },
        confirmButton = { TextBtn("OK", { onDone(value) }, strong = true) },
        dismissButton = { TextBtn("Cancel", { onDone(null) }) },
    )
}

/** The web's window.confirm. */
@Composable
fun ConfirmDialog(message: String, onDone: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = t.bg,
        text = { Text(message, style = Type.body, color = t.fg) },
        confirmButton = { TextBtn("OK", { onDone(true) }, strong = true) },
        dismissButton = { TextBtn("Cancel", { onDone(false) }) },
    )
}

@Composable
fun ErrorLine(text: String?, modifier: Modifier = Modifier) {
    if (text != null) Text(text, modifier.padding(vertical = 12.dp), style = Type.sm, color = t.fg)
}


/**
 * A screen's scroll position that survives leaving it (another screen on top, process death) even
 * when its content loads after it comes back. rememberScrollState alone restores the offset, but the
 * first layout, made before the code blocks and their outputs have loaded, clamps it to the short
 * page's end, so the screen reopened at the top. This keeps the reader's offset separately and,
 * once the page is tall enough (or after [waitMs], or as soon as the reader scrolls), returns to it.
 */
@Composable
fun rememberRestoredScrollState(key: Any, waitMs: Long = 10_000): ScrollState {
    val scroll = rememberScrollState()
    var saved by rememberSaveable(key) { mutableIntStateOf(0) }
    LaunchedEffect(key) {
        val target = saved
        if (target > 0) {
            // maxValue is Int.MAX_VALUE until the first layout; viewportSize says a layout happened.
            withTimeoutOrNull(waitMs) {
                snapshotFlow { scroll.isScrollInProgress || (scroll.viewportSize > 0 && scroll.maxValue >= target) }.first { it }
            }
            if (!scroll.isScrollInProgress) scroll.scrollTo(target) // clamps if the page is still shorter
        }
        // Only now track the reader's offset, so the clamped first layout doesn't overwrite it.
        snapshotFlow { scroll.value }.collect { saved = it }
    }
    return scroll
}
