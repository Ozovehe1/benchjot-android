package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.Note
import app.benchjot.core.RECENTLY_DELETED_DAYS
import app.benchjot.core.daysLeftText
import app.benchjot.core.displayTitle
import app.benchjot.core.recentlyDeleted
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.Offline
import kotlinx.coroutines.launch

/**
 * Recently Deleted (the web's /deleted, as Apple Notes has it): deleted notes wait here 30 days,
 * then go for good. Each can be restored or deleted now; Delete all empties it. Only deleting for
 * good asks first.
 */
@Composable
fun RecentlyDeletedScreen(onHome: () -> Unit) {
    val scope = rememberCoroutineScope()
    val copy by Offline.data.collectAsState()
    val notes = remember(copy) { copy.recentlyDeleted() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<List<String>?>(null) }

    fun act(action: String, f: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { f() } catch (e: Exception) { error = Accounts.message(e, action) }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageColumn {
                TopBar(onHome, "All notes" to onHome)
                Text("Recently deleted", Modifier.padding(top = 8.dp), style = Type.h1, color = t.fg)
                Muted("Notes stay here for $RECENTLY_DELETED_DAYS days, then they’re deleted for good.", Modifier.padding(top = 8.dp))
                if (notes.isNotEmpty()) TextBtn("Delete all", { confirm = notes.map { it.id } }, enabled = !busy, color = t.fail, modifier = Modifier.padding(top = 8.dp))
                error?.let { Notice(it, Modifier.padding(top = 12.dp), onDismiss = { error = null }) }
                if (notes.isEmpty()) Muted("Nothing here.", Modifier.padding(vertical = 40.dp))
                else Hairline(Modifier.padding(top = 16.dp))
            }
        }
        items(notes, key = { it.id }) { n -> DeletedRow(n, busy, onRestore = { act("restore") { Backend.restoreNote(n.id) } }, onDelete = { confirm = listOf(n.id) }) }
    }

    confirm?.let { ids ->
        ConfirmDialog(if (ids.size == 1) "Delete this note and its code for good? This can’t be undone." else "Delete all ${ids.size} notes and their code for good? This can’t be undone.") { ok ->
            confirm = null
            if (ok) act("delete_note") { Backend.deleteNotesForever(ids) }
        }
    }
}

@Composable
private fun DeletedRow(n: Note, busy: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    PageColumn {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    displayTitle(n.title), style = Type.body.copy(fontFamily = Type.display, fontSize = 18.sp, fontStyle = if (n.title.isBlank()) FontStyle.Italic else null),
                    color = if (n.title.isBlank()) t.muted else t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(daysLeftText(n.deletedAt!!), style = Type.xs, color = t.faint)
            }
            TextBtn("Restore", onRestore, strong = true, enabled = !busy)
            TextBtn("Delete now", onDelete, enabled = !busy, color = t.fail)
        }
        Hairline()
    }
}
