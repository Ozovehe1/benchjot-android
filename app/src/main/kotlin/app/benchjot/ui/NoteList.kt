package app.benchjot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import app.benchjot.core.plainExcerpt
import app.benchjot.core.searchHit
import app.benchjot.core.liveNotes
import app.benchjot.core.recentlyDeleted
import app.benchjot.core.searchTerms
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
import androidx.compose.ui.unit.sp
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
 * Project page: that project's notes; new notes are filed in it. As the web's note-list.tsx.
 */
@Composable
fun NoteListScreen(
    projectId: String?,
    onOpenNote: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onProjectGone: () -> Unit,
    onMap: () -> Unit,
    /** Opens a note at a cell (a search result found its words there). */
    onOpenNoteAt: (String, String?) -> Unit = { id, _ -> onOpenNote(id) },
    onOpenRecentlyDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val openUri = androidx.compose.ui.platform.LocalUriHandler.current
    // Shared with me: read when home shows and whenever the notes change (online; nothing offline).
    val version by app.benchjot.data.Backend.version.collectAsState()
    var sharedWithMe by remember { mutableStateOf<List<app.benchjot.data.SharedWithMe>>(emptyList()) }
    if (projectId == null) androidx.compose.runtime.LaunchedEffect(version) { runCatching { app.benchjot.data.Shares.sharedWithMe() }.onSuccess { sharedWithMe = it } }
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
    val notes = rememberLive(query, listScope) { Backend.notes(query, listScope) }
    val projects = rememberLive { Backend.projects() }
    val index = rememberLive { Backend.noteIndex() }
    val titleOf = index.value.orEmpty().associate { it.id to displayTitle(it.title) }
    val project: Project? = projectId?.let { id -> projects.value?.firstOrNull { it.id == id } }
    // The phone's copy gives each row its opening words and cell count, and each project its note count.
    val copy by app.benchjot.data.Offline.data.collectAsState()
    // Each note's excerpt is worked out once per version of the note, so a change re-reads one.
    val excerpts = remember { HashMap<String, Pair<app.benchjot.core.Note, String>>() }
    val details = remember(copy) {
        val cells = copy.cells.values.groupingBy { it.noteId }.eachCount()
        copy.notes.mapValues { (id, e) ->
            val kept = excerpts[id]?.takeIf { it.first === e }?.second ?: plainExcerpt(e.notes, 140).also { excerpts[id] = e to it }
            kept to (cells[id] ?: 0)
        }
    }
    val perProject = remember(copy) { copy.liveNotes().groupingBy { it.projectId }.eachCount() }
    val projectName = projects.value.orEmpty().associate { it.id to it.name }

    fun act(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { action() } catch (e: Exception) { error = Accounts.message(e) } finally { busy = false }
        }
    }

    val newNote = { act { onOpenNote(Backend.createNote("", projectId).id) } }
    val upload = rememberFilePicker(onError = { error = it }) { files: List<CodeFile> ->
        act {
            val note = Backend.createNote(files.joinToString(", ") { it.filename }.take(200), projectId)
            Backend.createCells(note.id, 0, files)
            onOpenNote(note.id)
        }
    }
    val showProjects = projectId == null && !searching

    val list = notes.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 64.dp)) {
        item {
            PageColumn {
                TopBar(onHome = onHome, back = if (projectId != null) "All notes" to onHome else null) {
                    TextBtn("Map", onMap, Modifier.widthIn(min = 48.dp), icon = Icons.Map)
                    TextBtn("Settings", onSettings, icon = Icons.Settings)
                }
                if (projectId != null) Eyebrow("Project", Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (projectId != null) project?.name.orEmpty() else "Your notes", Modifier.weight(1f), style = Type.h1, color = t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (projectId != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextBtn("Rename", { prompt = "rename" })
                        TextBtn("Delete", { confirmDelete = true })
                    }
                }
                val count = if (projectId != null) perProject[projectId] ?: 0 else copy.notes.size
                if (copy.notes.isNotEmpty()) Muted(
                    "$count ${if (count == 1) "note" else "notes"}" + if (projectId == null && copy.projects.isNotEmpty()) " · ${copy.projects.size} ${if (copy.projects.size == 1) "project" else "projects"}" else "",
                    Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledBtn("New note", { newNote() }, Modifier.weight(1f), enabled = !busy, icon = Icons.Plus)
                    FilledBtn("Upload", upload, variant = ButtonVariant.Soft, icon = Icons.Upload)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BjIcon(Icons.Search, tint = t.faint)
                    UnderlineField(
                        query, { query = it }, "Search notes and code", Modifier.weight(1f),
                        keyboard = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                }
                Segmented(OutcomeFilter.entries.map { it to it.label }, outcome, { outcome = it })
                ErrorLine(
                    error ?: notes.error,
                    actions = if (error == null && notes.error != null) listOf(NoticeAction("Try again") { Backend.changed() }) else emptyList(),
                    onDismiss = if (error != null) ({ error = null }) else null,
                )
            }
        }
        if (showProjects) {
            item { PageColumn { Eyebrow("Projects", Modifier.padding(top = 20.dp, bottom = 10.dp)) } }
            // Two project cards a row, then a dashed "New project".
            val cards: List<Project?> = projects.value.orEmpty() + listOf(null)
            items(cards.chunked(2), key = { row -> row.joinToString { it?.id ?: "new" } }) { row ->
                PageColumn {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { p ->
                            if (p != null) ProjectCard(p.name, perProject[p.id] ?: 0, Modifier.weight(1f)) { onOpenProject(p.id) }
                            else NewProjectCard(Modifier.weight(1f), enabled = !busy) { prompt = "new" }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            // Notes others shared with you (added by username, or opened through their link signed in): they open at their shared page.
            val invites = sharedWithMe.filter { it.pending }
            if (invites.isNotEmpty()) {
                item { PageColumn { Eyebrow("Invitations", Modifier.padding(top = 18.dp, bottom = 6.dp)) } }
                items(invites, key = { "invite-" + it.noteId }) { n ->
                    PageColumn {
                        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("${n.author?.let { "@$it" } ?: "Someone"} shared “${n.title.ifBlank { "Untitled" }}” with you", Modifier.plainClick { openUri.openUri(app.benchjot.data.Shares.link(n.token)) }, style = Type.sm, color = t.fg)
                            var answering by remember { mutableStateOf<Boolean?>(null) }
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                FilledBtn("Accept", {
                                    answering = true
                                    scope.launch { runCatching { app.benchjot.data.Shares.respond(n.noteId, true) }; runCatching { app.benchjot.data.Shares.sharedWithMe() }.onSuccess { sharedWithMe = it }; answering = null }
                                }, small = true, loading = answering == true, enabled = answering == null)
                                TextBtn(if (answering == false) "Declining…" else "Decline", {
                                    answering = false
                                    scope.launch { runCatching { app.benchjot.data.Shares.respond(n.noteId, false) }; runCatching { app.benchjot.data.Shares.sharedWithMe() }.onSuccess { sharedWithMe = it }; answering = null }
                                }, enabled = answering == null)
                            }
                        }
                    }
                }
            }
            val kept = sharedWithMe.filter { !it.pending }
            if (kept.isNotEmpty()) {
                item { PageColumn { Eyebrow("Shared with me", Modifier.padding(top = 18.dp, bottom = 2.dp)) } }
                items(kept, key = { "shared-" + it.noteId }) { n ->
                    PageColumn {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).plainClick { openUri.openUri(app.benchjot.data.Shares.link(n.token)) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(n.title.ifBlank { "Untitled" }, style = Type.body, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(n.author?.let { "@$it" } ?: "Someone", when (n.outcome) { "success" -> "Successful"; "failed" -> "Failed"; else -> null }).joinToString(" · "), style = Type.xs, color = t.faint, maxLines = 1)
                            }
                            BjIcon(Icons.Chevron, tint = t.faint, size = 14.dp)
                        }
                    }
                }
            }
            if (!list.isNullOrEmpty()) item { PageColumn { Eyebrow("Uncategorized", Modifier.padding(top = 18.dp, bottom = 2.dp)) } }
        }
        if (list != null && list.isEmpty()) {
            item { PageColumn { Muted(if (searching) "Nothing matches." else "No notes yet.", Modifier.padding(vertical = 40.dp)) } }
        }
        val sections = if (searching) listOf("" to list.orEmpty()) else app.benchjot.core.listSections(list.orEmpty())
        sections.forEach { (heading, rows) ->
        if (heading.isNotEmpty()) item(key = "heading-$heading") { PageColumn { Eyebrow(heading, Modifier.padding(top = 22.dp, bottom = 2.dp)) } }
        items(rows, key = { it.id }) { e ->
            PageColumn {
                val (excerpt, cellCount) = details[e.id] ?: ("" to 0)
                val pid = copy.notes[e.id]?.projectId
                // Searching: where this note matched (the line, and the cell it's in).
                val hit = if (query.isBlank()) null else remember(copy, query, e.id) { copy.searchHit(e.id, searchTerms(query)) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).plainClick { onOpenNoteAt(e.id, hit?.cellId) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        val parent = e.parentId?.let(titleOf::get)
                        Text(
                            buildAnnotatedString {
                                if (parent != null) withStyle(SpanStyle(color = t.faint)) { append("$parent › ") }
                                append(displayTitle(e.title))
                            },
                            style = Type.body.copy(fontFamily = Type.display, fontSize = 19.sp, fontStyle = if (e.title.isBlank()) androidx.compose.ui.text.font.FontStyle.Italic else null),
                            color = if (e.title.isBlank()) t.muted else t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (hit != null) HitLine(hit, searchTerms(query))
                        else if (excerpt.isNotEmpty()) Text(excerpt, Modifier.padding(top = 2.dp), style = Type.sm, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (projectId == null && pid != null && projectName[pid] != null) Meta(Icons.Folder, projectName[pid]!!)
                            if (cellCount > 0) Meta(Icons.Code, "$cellCount ${if (cellCount == 1) "cell" else "cells"}")
                            Text(listDate(e.updatedAt), style = Type.xs, color = t.faint, maxLines = 1)
                        }
                    }
                    Outcome.of(e.outcome)?.let { OutcomeChip(it == Outcome.Success, it.label, Modifier.padding(top = 4.dp)) }
                }
                Hairline()
            }
        }
        }
        // Recently Deleted, only when something is in it (as Apple Notes shows its folder).
        val deletedCount = copy.recentlyDeleted().size
        if (projectId == null && !searching && deletedCount > 0) item(key = "recently-deleted") {
            PageColumn { TextBtn("Recently deleted · $deletedCount", onOpenRecentlyDeleted, modifier = Modifier.padding(top = 28.dp)) }
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

/** A small fact under a note's title: an icon and a few words. */
@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        BjIcon(icon, tint = t.faint, size = 13.dp)
        Text(text, style = Type.xs, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The note's outcome as a word (the web's OutcomeBadge): quiet when successful, red when failed. */
@Composable
private fun OutcomeChip(success: Boolean, label: String, modifier: Modifier = Modifier) {
    Text(label, modifier, style = if (success) Type.xs else Type.xs.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (success) t.muted else t.fail)
}

/** A project in an outlined box: a folder, its name, how many notes. */
@Composable
private fun ProjectCard(name: String, notes: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.heightIn(min = 88.dp).border(1.dp, t.line, shape).clip(shape).plainClick(onClick = onClick).padding(14.dp)) {
        BjIcon(Icons.Folder, size = 16.dp, tint = t.muted)
        Text(name, Modifier.padding(top = 10.dp), style = Type.sm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$notes ${if (notes == 1) "note" else "notes"}", style = Type.xs, color = t.faint)
    }
}

/** After the projects: plain words that make a new project. */
@Composable
private fun NewProjectCard(modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 88.dp).plainClick(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BjIcon(Icons.Plus, tint = t.muted)
        Text("New project", Modifier.padding(top = 6.dp), style = Type.sm, color = t.muted)
    }
}

/** Where a search matched (the web's HitLine): which cell and line, then the line with the words marked. */
@Composable
private fun HitLine(hit: app.benchjot.core.SearchHit, terms: List<String>) {
    val words = terms.map { it.lowercase() }.filter { it.isNotEmpty() }
    val where = if (hit.where == "note") "Note, line ${hit.lineNo}" else "Cell ${hit.cell}" + (if (hit.filename.isNotEmpty()) " · ${hit.filename}" else "") + (if (hit.where == "cell note") " · its notes" else "") + ", line ${hit.lineNo}"
    val mark = t.fg.copy(alpha = 0.15f)
    val fg = t.fg
    val text = buildAnnotatedString {
        val low = hit.line.lowercase()
        var i = 0
        while (i < hit.line.length) {
            val next = words.map { it to low.indexOf(it, i) }.filter { it.second >= 0 }.minByOrNull { it.second }
            if (next == null) { append(hit.line.substring(i)); break }
            if (next.second > i) append(hit.line.substring(i, next.second))
            withStyle(SpanStyle(background = mark, color = fg)) { append(hit.line.substring(next.second, next.second + next.first.length)) }
            i = next.second + next.first.length
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(where, style = Type.xs.copy(fontSize = 11.sp), color = t.faint)
        Text(
            text,
            Modifier.padding(top = 2.dp).fillMaxWidth().background(t.raise, RoundedCornerShape(6.dp)).border(1.dp, t.line, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            style = if (hit.where == "code") Type.code.copy(fontSize = 12.sp) else Type.sm.copy(fontSize = 13.sp), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

