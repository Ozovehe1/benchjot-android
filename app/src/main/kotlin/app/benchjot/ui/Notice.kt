package app.benchjot.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// How the app says something went wrong, in place (the web's <Notice>): a soft panel with a mark,
// the message in plain words (core UserMessage), and the next step as text buttons (Try again,
// Dismiss). Errors are announced at once, information politely. Also: whether the phone is
// online, and the bar that says so when it isn't.

/** A next step under a notice. */
data class NoticeAction(val label: String, val onClick: () -> Unit)

@Composable
fun Notice(text: String, modifier: Modifier = Modifier, actions: List<NoticeAction> = emptyList(), onDismiss: (() -> Unit)? = null, error: Boolean = true) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth()
            .background(if (error) t.fail.copy(alpha = 0.08f) else t.tint.copy(alpha = 0.6f), shape)
            .border(1.dp, if (error) t.fail.copy(alpha = 0.35f) else t.line, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (actions.isEmpty() && onDismiss == null) 12.dp else 2.dp)
            .semantics(mergeDescendants = false) { liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NoticeMark(error, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = Type.sm, color = t.fg)
            if (actions.isNotEmpty() || onDismiss != null) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                actions.forEach { TextBtn(it.label, it.onClick, strong = true) }
                if (onDismiss != null) TextBtn("Dismiss", onDismiss)
            }
        }
    }
}

/** A circle with "!" (error) or "i" (information), drawn so it needs no icon font. */
@Composable
private fun NoticeMark(error: Boolean, modifier: Modifier = Modifier) {
    val c = if (error) t.fail else t.muted
    Canvas(modifier.size(18.dp)) {
        val w = 1.7.dp.toPx()
        drawCircle(c, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        val x = size.width / 2
        if (error) {
            drawLine(c, Offset(x, size.height * 0.30f), Offset(x, size.height * 0.56f), w, StrokeCap.Round)
            drawCircle(c, radius = w * 0.65f, center = Offset(x, size.height * 0.71f))
        } else {
            drawLine(c, Offset(x, size.height * 0.46f), Offset(x, size.height * 0.72f), w, StrokeCap.Round)
            drawCircle(c, radius = w * 0.65f, center = Offset(x, size.height * 0.31f))
        }
    }
}

/** Whether the phone has a working connection now, kept current. [onBack] runs each time it comes back. */
@Composable
fun rememberOnline(onBack: () -> Unit = {}): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isOnline(context)) }
    val back by androidx.compose.runtime.rememberUpdatedState(onBack)
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { if (!online) { online = true; back() } } }
            override fun onLost(network: Network) { main.post { online = isOnline(context) } }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** A slim bar while offline: what still works and what waits. */
@Composable
fun OfflineBar(online: Boolean, modifier: Modifier = Modifier) {
    if (online) return
    Row(
        modifier.fillMaxWidth().background(t.tint).heightIn(min = 36.dp).padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(t.fail, CircleShape))
        Text("You’re offline. Python and the on-phone model still work; edits save when you’re back.", style = Type.xs, color = t.fg)
    }
}
