package app.benchjot.ui

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benchjot.data.Accounts
import app.benchjot.data.SharePerson
import app.benchjot.data.ShareRow
import app.benchjot.data.Shares
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// "Share" in a note (the web's share-sheet.tsx): add people by their Benchjot username (it stays in
// their Shared with me until you remove them; no email is sent), a general access (only the people
// added, or anyone with the link, account or not), the link with Copy and the phone's share sheet,
// what readers get, and when sharing ends. New link replaces the link; Stop sharing locks the note.

private fun endsText(expires: String?): String? {
    val at = expires?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val ms = at.toEpochMilli() - System.currentTimeMillis()
    if (ms <= 0) return "Ended"
    val hours = Math.round(ms / 3_600_000.0)
    if (hours < 24) return "Ends in ${maxOf(hours, 1)} hour${if (hours == 1L) "" else "s"}"
    val days = Math.round(hours / 24.0)
    return "Ends in $days day${if (days == 1L) "" else "s"}"
}

@Composable
private fun ShareToggle(label: String, detail: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).plainClick(enabled = enabled, role = Role.Switch) { onChange(!on) }
            .semantics { stateDescription = if (on) "On" else "Off" }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body, color = if (enabled) t.fg else t.faint)
            Text(detail, style = Type.xs, color = t.muted)
        }
        Box(
            Modifier.size(40.dp, 24.dp).background(if (on) t.fg else t.tint, RoundedCornerShape(12.dp)).border(1.dp, if (on) t.fg else t.line, RoundedCornerShape(12.dp)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(18.dp).background(if (on) t.bg else t.muted, CircleShape)) }
    }
}

@Composable
private fun PersonRow(name: String, role: String, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).border(1.dp, t.line, CircleShape).background(t.tint, CircleShape), contentAlignment = Alignment.Center) {
            Text(name.removePrefix("@").take(1).uppercase(), style = Type.sm, color = t.fg)
        }
        Text(name, Modifier.weight(1f), style = Type.body, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(role, style = Type.xs, color = t.faint)
        if (onRemove != null) Box(Modifier.size(40.dp).plainClick(onClick = onRemove).semantics { contentDescription = "Remove $name" }, contentAlignment = Alignment.Center) {
            BjIcon(Icons.X, tint = t.faint, size = 14.dp)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // the calendar (DatePicker) is stable in use, marked experimental in Material 3
@Composable
fun ShareDialog(noteId: String, title: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var loading by remember { mutableStateOf(true) }
    var row by remember { mutableStateOf<ShareRow?>(null) }
    var people by remember { mutableStateOf<List<SharePerson>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        row = Shares.get(noteId)
        people = if (row != null) Shares.people(noteId) else emptyList()
    }
    LaunchedEffect(noteId) {
        try { reload() } catch (e: Exception) { error = Accounts.message(e, "share") } finally { loading = false }
    }
    fun act(what: String, block: suspend () -> Unit, done: () -> Unit = {}) {
        busy = what
        error = null
        scope.launch {
            try { block(); done() } catch (e: Exception) { error = Shares.friendly(e) ?: Accounts.message(e, "share") } finally { busy = null }
        }
    }
    fun set(patch: JsonObject) = act("options", { row = Shares.update(noteId, patch) })
    fun invite() {
        val who = name.trim()
        if (who.isEmpty()) return
        act("invite", { if (row == null) row = Shares.start(noteId); Shares.invite(noteId, who); people = Shares.people(noteId) }) { name = "" }
    }

    var settings by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    fun copyLink() = act("copy", {
        val current = row ?: Shares.start(noteId).also { row = it }
        clipboard.setText(AnnotatedString(Shares.link(current.token)))
        copied = true
    })

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        containerColor = t.bg,
        title = { Text("Share “${title}”", style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (loading) { Working("Loading…"); return@Column }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UnderlineField(
                        name, { name = it; added = null }, "Add people by username", Modifier.weight(1f),
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        actions = KeyboardActions(onDone = { invite(); added = name.trim().removePrefix("@") }),
                    )
                    FilledBtn("Invite", { added = name.trim().removePrefix("@"); invite() }, enabled = name.isNotBlank() && busy == null, small = true, loading = busy == "invite")
                }
                Text(if (added != null && name.isEmpty()) "Invited @$added. They’ll see it on their home to accept." else "They get an invitation in Benchjot to accept.", Modifier.padding(top = 6.dp), style = Type.xs, color = t.faint)

                Text("People with access", Modifier.padding(top = 18.dp, bottom = 4.dp), style = Type.xs, color = t.muted)
                PersonRow("You", "Owner", null)
                val r = row
                if (r != null) people.forEach { p ->
                    PersonRow("@${p.username ?: "someone"}", if (p.accepted) "Can read" else "Invited") {
                        act("remove-${p.userId}", { Shares.remove(noteId, p.userId, r.token); people = people.filter { it.userId != p.userId } })
                    }
                }

                Text("General access", Modifier.padding(top = 16.dp, bottom = 4.dp), style = Type.xs, color = t.muted)
                val access = r?.access ?: "people"
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).background(if (access == "link") t.fg else t.tint, CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (access == "link") "◎" else "•", style = Type.sm, color = if (access == "link") t.bg else t.muted)
                    }
                    Column(Modifier.weight(1f)) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            TextBtn(if (access == "link") "Anyone with the link ▾" else "Only people added ▾", { open = true }, strong = true, enabled = busy == null)
                            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = t.bg) {
                                listOf("people" to "Only people added", "link" to "Anyone with the link").forEach { (v, label) ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(label, style = Type.sm, color = if (v == access) t.fg else t.muted) }, onClick = {
                                        open = false
                                        if (v != access) act("options", { row = if (r == null) Shares.start(noteId, v) else Shares.update(noteId, buildJsonObject { put("access", v) }) })
                                    })
                                }
                            }
                        }
                        Text(if (access == "link") "Anyone with the link can read it. No account needed." else "Only the people above can open the link.", style = Type.xs, color = t.faint)
                    }
                }

                if (r != null) {
                    Hairline(Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).plainClick { settings = !settings }, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (settings) "▾  Settings" else "▸  Settings", Modifier.weight(1f), style = Type.sm, color = t.muted)
                        Text(listOfNotNull("${r.views} view${if (r.views == 1) "" else "s"}", endsText(r.expiresAt)).joinToString(" · "), style = Type.xs, color = t.faint)
                    }
                    if (settings) Column(Modifier.padding(start = 12.dp)) {
                        ShareToggle("Show the code", if (r.showCode) "" else "Readers see the text and results only.", r.showCode, busy == null) { set(buildJsonObject { put("show_code", it) }) }
                        ShareToggle("Readers can copy it", if (r.showCode) "" else "Needs the code shown.", r.allowCopy && r.showCode, busy == null && r.showCode) { set(buildJsonObject { put("allow_copy", it) }) }
                        if (r.access == "link") ShareToggle("Search engines can list it", "", r.indexable, busy == null) { set(buildJsonObject { put("indexable", it) }) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ends", Modifier.weight(1f), style = Type.body, color = t.fg)
                            var picking by remember { mutableStateOf(false) }
                            val endsOn = r.expiresAt?.let { runCatching { java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull() }
                            TextBtn(endsOn?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "No end date", { picking = true }, enabled = busy == null)
                            if (endsOn != null) TextBtn("Clear", { set(JsonObject(mapOf("expires_at" to kotlinx.serialization.json.JsonNull))) }, enabled = busy == null, style = Type.xs)
                            if (picking) {
                                val tomorrow = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                                val state = androidx.compose.material3.rememberDatePickerState(
                                    initialSelectedDateMillis = endsOn?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
                                    selectableDates = object : androidx.compose.material3.SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= tomorrow },
                                )
                                androidx.compose.material3.DatePickerDialog(
                                    onDismissRequest = { picking = false },
                                    confirmButton = { TextBtn("Set", { picking = false; state.selectedDateMillis?.let { set(JsonObject(mapOf("expires_at" to JsonPrimitive(Shares.endOfDay(it))))) } }, strong = true) },
                                    dismissButton = { TextBtn("Cancel", { picking = false }) },
                                ) { androidx.compose.material3.DatePicker(state = state, title = null, showModeToggle = false) }
                            }
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            TextBtn(if (busy == "reset") "Making…" else "New link", { confirm = "reset" }, enabled = busy == null, style = Type.sm)
                            TextBtn(if (busy == "stop") "Stopping…" else "Stop sharing", { confirm = "stop" }, enabled = busy == null, style = Type.sm, color = t.fail)
                        }
                    }
                }
                error?.let { Notice(it, Modifier.padding(top = 12.dp)) }
            }
        },
        dismissButton = {
            val r = row
            if (r != null) TextBtn("Send…", {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, "$title\n${Shares.link(r.token)}")
                context.startActivity(Intent.createChooser(send, "Send link"))
            })
        },
        confirmButton = { FilledBtn(if (copied) "Copied" else "Copy link", { copyLink() }, variant = ButtonVariant.Soft, small = true, loading = busy == "copy", enabled = busy == null) },
    )

    val r = row
    if (confirm != null && r != null) {
        val reset = confirm == "reset"
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = t.bg,
            title = { Text(if (reset) "Make a new link?" else "Stop sharing?", style = Type.xl, color = t.fg) },
            text = { Text(if (reset) "The current link stops working now; the people you added keep access." else "The link and everyone’s access end now.", style = Type.sm, color = t.muted) },
            confirmButton = {
                TextBtn(if (reset) "New link" else "Stop sharing", {
                    confirm = null
                    if (reset) act("reset", { row = Shares.reset(noteId, r.token) })
                    else act("stop", { Shares.stop(noteId, r.token); row = null; people = emptyList() })
                }, strong = true)
            },
            dismissButton = { TextBtn("Cancel", { confirm = null }) },
        )
    }
}
