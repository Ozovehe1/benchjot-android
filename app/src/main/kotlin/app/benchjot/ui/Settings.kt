package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.BuildConfig
import app.benchjot.data.Accounts
import app.benchjot.data.AiStatus
import app.benchjot.data.Backend
import app.benchjot.data.Connections
import app.benchjot.data.HfStatus
import app.benchjot.data.Usage
import app.benchjot.data.WebApi
import kotlinx.coroutines.launch

// As the web's components/account/settings.tsx: grouped like a platform settings page. Account
// (username, email), Connections (AI on the user's own OpenRouter, Hugging Face: each a row with its
// status and one action, the steps under "How it works"), Usage, On this phone (the notes
// folder, Android only), Appearance (theme), then one "How your keys are kept" link and Sign out.

@Composable
fun SettingsScreen(username: String, onHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    PageColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(onHome = onHome, back = "All notes" to onHome)
        Text("Settings", Modifier.padding(top = 8.dp), style = Type.h1, color = t.fg)

        Section("Account") {
            // Who is signed in: an initial in a ring, the username, the email.
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(t.bg, CircleShape).border(1.dp, t.line, CircleShape), contentAlignment = Alignment.Center) {
                    Text(username.take(1).uppercase(), style = Type.xl.copy(fontSize = 20.sp), color = t.fg)
                }
                Column(Modifier.weight(1f)) {
                    Text("@$username", style = Type.body.copy(fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Backend.email(), style = Type.sm, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Section("Connections", hint = "Your own free accounts. Benchjot never pays for them and never asks for a card.") { ConnectionsBlock() }
        Section("Usage") { UsageBlock() }
        Section("On this phone") {
            NotesFolderRow()
        }
        Section("Appearance") {
            SettingRow("Theme") {
                ThemeChoice.entries.forEach { choice ->
                    val on = ThemePref.current.value == choice
                    TextBtn(choice.label, { ThemePref.set(context, choice) }, strong = on, underline = on)
                }
            }
        }

        Row(Modifier.padding(top = 32.dp, bottom = 48.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextBtn("How your keys are kept", { uri.openUri("${BuildConfig.WEB_URL}/security") })
            Box(
                Modifier.heightIn(min = 44.dp).plainClick { scope.launch { runCatching { Accounts.signOut() } } }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Sign out", style = Type.sm, color = t.fail) }
        }
    }
}

/**
 * A labelled group of settings (the web's Section): a small mono heading, a line on what it is for,
 * then its rows on a raised card. The last row's rule is hidden under the card's edge.
 */
@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 32.dp).fillMaxWidth()) {
        Eyebrow(title, Modifier.semantics { heading() })
        if (hint != null) Text(hint, Modifier.padding(top = 6.dp), style = Type.sm, color = t.muted)
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().card().clipToBounds().padding(horizontal = 16.dp)
                .layout { m, c -> val p = m.measure(c); layout(p.width, (p.height - 1.dp.roundToPx()).coerceAtLeast(0)) { p.place(0, 0) } },
        ) { content() }
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

/**
 * One connection as a settings row (the web's ConnectionRow): its name and status on the left, the one
 * action on the right, a line on what it's for, and the details under "How it works".
 */
@Composable
private fun ConnectionRow(name: String, status: String, description: String, action: @Composable () -> Unit, details: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().bottomLine(t.line).padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = t.fg)
                Text(status, style = Type.sm, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action()
        }
        Text(description, Modifier.padding(top = 8.dp), style = Type.sm, color = t.faint)
        TextBtn(if (open) "⌄ How it works" else "› How it works", { open = !open }, style = Type.sm)
        if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { details() }
    }
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { i, s ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${i + 1}", style = Type.xs.copy(fontFamily = Type.mono), color = t.faint)
            Text(s, style = Type.sm, color = t.muted)
        }
    }
}

private fun day(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.UK).format(java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()))
}.getOrDefault("")

@Composable
private fun ConnectionsBlock() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val version by Connections.version.collectAsState()
    val message by Connections.message.collectAsState()
    var ai by remember { mutableStateOf<AiStatus?>(null) }
    var hf by remember { mutableStateOf<HfStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        ai = Backend.aiStatus()
        hf = runCatching { WebApi.hfStatus() }.getOrNull()
        if (hf?.hfRemoved == true) note = app.benchjot.data.HF_REMOVED
    }
    LaunchedEffect(message) { message?.let { note = it; Connections.consumeMessage() } }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { note = Accounts.message(e, "load") } finally { busy = false }
        }
    }

    val aiNow = ai
    ConnectionRow(
        name = "AI · OpenRouter",
        status = if (aiNow != null) "Connected ${day(aiNow.connectedAt)}${aiNow.usedAt?.let { " · last used ${day(it)}" } ?: ""}" else "Not connected",
        description = "Runs Fix and the Assistant on your own free OpenRouter account. No card.",
        action = {
            if (aiNow != null) TextBtn("Disconnect", { act { Connections.disconnect(Connections.Provider.Ai); note = "Disconnected. Fix and the Assistant are off until you connect again." } }, enabled = !busy)
            else FilledBtn("Connect", { act { Connections.start(context, Connections.Provider.Ai) } }, enabled = !busy, small = true)
        },
        details = {
            Steps(
                "OpenRouter opens in your browser. Sign in with Google or email (free, no card).",
                "Tap Authorize. Leave the credit limit as it is: free models need none.",
                "You come back here, connected.",
            )
            Text("Without it, there’s no Fix and no Assistant. Notes, runs and export work the same.", style = Type.sm, color = t.muted)
            if (aiNow != null) TextBtn("Manage or revoke on OpenRouter", { uri.openUri("https://openrouter.ai/keys/${aiNow.keyHash}") }, underline = true)
        },
    )

    val hfNow = hf
    val hfOn = hfNow?.hf == true
    ConnectionRow(
        name = "Hugging Face",
        status = when {
            !hfOn -> "Not connected"
            hfNow.hfUser != null -> "Connected as @${hfNow.hfUser}"
            hfNow.hfBy == "token" -> "Connected with a token"
            else -> "Connected"
        },
        description = "For gated and private models and datasets in server runs (Llama, Gemma and others). Public ones need nothing.",
        action = {
            if (hfOn) TextBtn("Disconnect", { act { Connections.disconnect(Connections.Provider.Hf); note = "Hugging Face disconnected." } }, enabled = !busy)
            else FilledBtn("Connect", { act { Connections.start(context, Connections.Provider.Hf) } }, enabled = !busy, small = true)
        },
        details = {
            Steps(
                "Hugging Face opens in your browser. Sign in (free).",
                "Tap Allow. Benchjot gets read-only access: it can’t change anything on your account.",
                "You come back here, connected. Server runs get it as HF_TOKEN.",
            )
            Text("For a gated model, also accept its terms once on its Hugging Face page.", style = Type.sm, color = t.muted)
            if (hfOn && hfNow.hfBy != "token") TextBtn("Manage on Hugging Face", { uri.openUri("https://huggingface.co/settings/connected-applications") }, underline = true)
        },
    )
    note?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.sm, color = t.fg) }
}

@Composable
private fun UsageBlock() {
    var usage by remember { mutableStateOf<Usage?>(null) }
    var failed by remember { mutableStateOf(false) }
    val version by Connections.version.collectAsState()
    LaunchedEffect(version) {
        runCatching { WebApi.usage() }.onSuccess { usage = it; failed = false }.onFailure { failed = true }
    }
    Column(Modifier.fillMaxWidth().bottomLine(t.line).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("The last 24 hours. Benchjot counts requests and runs, never what was in them.", style = Type.sm, color = t.faint)
        val u = usage
        when {
            failed && u == null -> Text("Couldn’t load your usage. Open Settings again to retry.", style = Type.sm, color = t.fg)
            u == null -> Text("Loading…", style = Type.sm, color = t.faint)
            else -> {
                if (u.aiConnected) Meter("AI requests on your OpenRouter connection", "${u.aiOwn24h} today · ${u.aiOwn30d} in 30 days", null)
                else Text("AI isn’t connected yet.", style = Type.sm, color = t.muted)
                Meter("GPU runs on the server", "${u.gpuRuns24h}${u.gpuRunsPerDay?.let { " of $it" } ?: ""}", u.gpuRunsPerDay?.let { u.gpuRuns24h.toFloat() / it.coerceAtLeast(1) })
            }
        }
    }
}

/** A count, with a bar when it has a limit (the web's Meter). */
@Composable
private fun Meter(label: String, value: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f), style = Type.sm, color = t.fg)
            Text(value, style = Type.xs.copy(fontFamily = Type.mono), color = t.muted)
        }
        if (fraction != null) {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).background(t.tint, shape)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(t.fg.copy(alpha = 0.7f), shape))
            }
        }
    }
}

/** Every note kept as a Markdown file in the phone's Documents (NoteFolder); on by default. */
@Composable
private fun NotesFolderRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(app.benchjot.data.NoteFolder.isOn(context)) }
    SettingRow("Notes folder") {
        TextBtn(if (on) "On" else "Off", { on = !on; app.benchjot.data.NoteFolder.setOn(context, on) }, strong = on)
    }
    Muted(
        if (on) "Every note is also a Markdown file in ${app.benchjot.data.NoteFolder.location}, one folder per project, kept up to date by itself, even offline."
        else "Turn on to keep every note as a Markdown file in ${app.benchjot.data.NoteFolder.location}.",
        color = t.faint,
    )
}
