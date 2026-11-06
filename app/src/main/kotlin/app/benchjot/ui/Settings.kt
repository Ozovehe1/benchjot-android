package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.benchjot.core.OnDeviceModel
import app.benchjot.core.megabytes
import app.benchjot.llm.ModelState
import app.benchjot.llm.ModelStore
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import kotlinx.coroutines.launch

/** As the web's components/settings.tsx: username, email, theme, sign out; plus the on-device model (Android only). */
@Composable
fun SettingsScreen(username: String, onHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PageColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(onHome = onHome, back = "All notes" to onHome)
        Text("Settings", Modifier.padding(top = 8.dp, bottom = 16.dp), style = Type.xl, color = t.fg)
        Hairline()
        SettingRow("Username") { Text("@$username", style = Type.body, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        SettingRow("Email") { Text(Backend.email(), style = Type.body, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        SettingRow("Theme") {
            ThemeChoice.entries.forEach { choice ->
                val on = ThemePref.current.value == choice
                TextBtn(choice.label, { ThemePref.set(context, choice) }, strong = on, underline = on)
            }
        }
        OnDeviceModelRow()
        TextBtn("Sign out", { scope.launch { runCatching { Accounts.signOut() } } }, Modifier.padding(top = 24.dp))
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).bottomLine(t.line),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(label, style = Type.body, color = t.muted)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** The on-device model: its state, Download or Delete, and its licence. */
@Composable
private fun OnDeviceModelRow() {
    val model by ModelStore.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    SettingRow("On-device model") {
        when (val m = model) {
            is ModelState.Ready -> {
                Text(megabytes(m.file.length()), style = Type.body, color = t.fg)
                TextBtn("Delete", { confirm = true })
            }
            ModelState.Missing, is ModelState.Failed -> TextBtn("Download", { ModelStore.download(allowMobileData = ModelStore.onUnmeteredNetwork()) })
            is ModelState.Downloading -> Text("${(m.done * 100 / m.total.coerceAtLeast(1))}%", style = Type.body, color = t.fg)
            is ModelState.Waiting -> Text(if (m.forWifi) "Waiting for Wi-Fi" else "Waiting", style = Type.body, color = t.muted)
            ModelState.Verifying -> Text("Checking…", style = Type.body, color = t.muted)
        }
    }
    if (confirm) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Delete the model? Fix will use the server.", Modifier.weight(1f), style = Type.sm, color = t.fg)
        FilledBtn("Delete", { ModelStore.delete(); confirm = false }, variant = ButtonVariant.Neutral)
        TextBtn("Keep", { confirm = false })
    }
    Text("${OnDeviceModel.NAME} by the Qwen team, Apache License 2.0. Runs with llama.cpp (MIT).", Modifier.padding(top = 8.dp), style = Type.xs, color = t.faint)
}
