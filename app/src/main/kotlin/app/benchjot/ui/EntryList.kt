package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.benchjot.core.CodeFile
import app.benchjot.core.Outcome
import app.benchjot.core.OutcomeFilter
import app.benchjot.core.Project
import app.benchjot.core.displayTitle
import app.benchjot.core.listDate
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import kotlinx.coroutines.launch

/**
 * Home (no project): project rows, then unfiled notes; search covers every note.
 * Project page: that project's notes; new notes are filed in it. As the web's entry-list.tsx.
 */
@Composable
fun EntryListScreen(
    projectId: String?,
    onOpenNote: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onProjectGone: () -> Unit,
    onMap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var outcome by rememberSaveable { mutableStateOf(OutcomeFilter.All) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<String?>(null) } // "new" | "rename"
    var confirmDelete by remember { mutableStateOf(false) }

    // Searching or filtering on Home looks across every note, not just the unfiled ones.
    val searching = query.isNotBlank() || outcome != OutcomeFilter.All
    val listScope = when {
        projectId != null -> Backend.Scope(projectId = projectId, outcome = outcome)
        searching -> Backend.Scope(outcome = outcome)
        else -> Backend.Scope(unfiled = true, outcome = outcome)
    }
    val entries = rememberLive(query, listScope) { Backend.entries(query, listScope) }
    val projects = rememberLive { Backend.projects() }
    val index = rememberLive { Backend.noteIndex() }
    val titleOf = index.value.orEmpty().associate { it.id to displayTitle(it.title) }
    val project: Project? = projectId?.let { id -> projects.value?.firstOrNull { it.id == id } }

    fun act(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { action() } catch (e: Exception) { error = Accounts.message(e) } finally { busy = false }
        }
    }

    val newEntry = { act { onOpenNote(Backend.createEntry("", projectId).id) } }
    val upload = rememberFilePicker(onError = { error = it }) { files: List<CodeFile> ->
        act {
            val entry = Backend.createEntry(files.joinToString(", ") { it.filename }.take(200), projectId)
            Backend.createSnippets(entry.id, 0, files)
            onOpenNote(entry.id)
        }
    }
    val showProjects = projectId == null && !searching

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 64.dp)) {
        item {
            PageColumn {
                TopBar(onHome = onHome, back = if (projectId != null) "All notes" to onHome else null) {
                    TextBtn("Map", onMap, Modifier.widthIn(min = 48.dp), center = true)
                    TextBtn("Settings", onSettings)
                }
                if (projectId != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(project?.name.orEmpty(), Modifier.weight(1f), style = Type.xl, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            TextBtn("Rename", { prompt = "rename" })
                            TextBtn("Delete", { confirmDelete = true })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FilledBtn("New note", { newEntry() }, Modifier.weight(1f), enabled = !busy)
                    TextBtn("Upload file", upload)
                }
                UnderlineField(
                    query, { query = it }, "Search", Modifier.padding(top = 16.dp),
                    keyboard = KeyboardOptions(imeAction = ImeAction.Search),
                )
                Segmented(OutcomeFilter.entries.map { it to it.label }, outcome, { outcome = it })
                ErrorLine(error ?: entries.error?.let { "Could not load notes." })
            }
        }
        if (showProjects) {
            items(projects.value.orEmpty(), key = { "p" + it.id }) { p ->
                PageColumn {
                    Row(
                        Modifier.fillMaxWidth().plainClick { onOpenProject(p.id) }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text(p.name, Modifier.weight(1f), style = Type.body, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Project", style = Type.xs, color = t.faint)
                    }
                    Hairline()
                }
            }
            item {
                PageColumn {
                    TextBtn("New project", { prompt = "new" }, enabled = !busy)
                    Hairline()
                }
            }
        }
        val list = entries.value
        if (list != null && list.isEmpty()) {
            item { PageColumn { Muted(if (searching) "Nothing matches." else "No notes yet.", Modifier.padding(vertical = 40.dp)) } }
        }
        items(list.orEmpty(), key = { it.id }) { e ->
            PageColumn {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).plainClick { onOpenNote(e.id) }.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    val parent = e.parentId?.let(titleOf::get)
                    Text(
                        buildAnnotatedString {
                            if (parent != null) withStyle(SpanStyle(color = t.faint)) { append("$parent › ") }
                            append(displayTitle(e.title))
                        },
                        Modifier.weight(1f), style = Type.body, color = if (e.title.isBlank()) t.muted else t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Outcome.of(e.outcome)?.let { OutcomeDot(it == Outcome.Success, it.label) }
                        Text(listDate(e.updatedAt), style = Type.xs, color = t.faint)
                    }
                }
                Hairline()
            }
        }
    }

    when (prompt) {
        "new" -> PromptDialog("Project name", "") { name ->
            prompt = null
            if (!name.isNullOrBlank()) act { Backend.createProject(name) }
        }
        "rename" -> PromptDialog("Rename project", project?.name.orEmpty()) { name ->
            prompt = null
            if (project != null && !name.isNullOrBlank() && name.trim() != project.name) act { Backend.renameProject(project.id, name) }
        }
    }
    if (confirmDelete && project != null) {
        ConfirmDialog("Delete “${project.name}”? Its notes stay, unfiled.") { ok ->
            confirmDelete = false
            if (ok) act { Backend.deleteProject(project.id); onProjectGone() }
        }
    }
}
