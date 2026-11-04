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
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import kotlinx.coroutines.launch

/** As the web's components/settings.tsx: username, email, theme, sign out. */
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
