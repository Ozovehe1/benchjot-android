package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.benchjot.core.Autosaver
import app.benchjot.core.CodeFile
import app.benchjot.core.helperCells
import app.benchjot.core.helperLanguage
import app.benchjot.core.Entry
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.OUTCOME_CHOICES
import app.benchjot.core.Outcome
import app.benchjot.core.SaveRegistry
import app.benchjot.core.SaveState
import app.benchjot.core.ancestors
import app.benchjot.core.backlinksTo
import app.benchjot.core.childrenOf
import app.benchjot.core.completeLink
import app.benchjot.core.displayTitle
import app.benchjot.core.linkQueryAt
import app.benchjot.core.linkSuggestions
import app.benchjot.core.mapRoute
import app.benchjot.core.noteRoute
import app.benchjot.core.parentCandidates
import app.benchjot.core.searchTerms
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.home
import app.benchjot.leaveDeletedNote
import app.benchjot.project
import kotlinx.coroutines.launch

/** Placeholder and accessible name of the note body (the user's wording). */
private const val NOTE_LABEL = "Experiment note"

private data class NoteText(val title: String, val notes: String)

@Composable
fun NoteScreen(id: String, pro: Boolean, nav: NavHostController) {
    // The note itself loads once (edits live here); lists around it reload after writes.
    val loaded by produceState<Pair<Boolean, Entry?>>(false to null, id) {
        value = true to runCatching { Backend.entry(id) }.getOrNull()
    }
    val projects = rememberLive { Backend.projects() }
    val (done, entry) = loaded
    val project = projects.value?.firstOrNull { it.id == entry?.projectId }
    val back: Pair<String, () -> Unit> =
        if (project != null) project.name to { nav.project(project.id) } else "All notes" to { nav.home() }
    if (!done) {
        PageColumn { TopBar({ nav.home() }, back) }
        return
    }
    if (entry == null) {
        PageColumn {
            TopBar({ nav.home() }, back)
            Muted("This note doesn’t exist.", Modifier.padding(vertical = 40.dp))
        }
        return
    }
    Editor(entry, pro, nav)
}

@Composable
private fun Editor(entry: Entry, pro: Boolean, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val registry = remember(entry.id) { SaveRegistry() }
    val keys = remember { CodeKeysController() }
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    // The note body: one live Markdown block (its own text, selection and undo history).
    val body = remember(entry.id) { MarkdownBlockState(entry.notes) }
    // The one format bar acts on the focused Markdown block; the "+" sheet outlives that focus.
    val formatBar = remember { FormatBarController() }
    var sheetFor by remember { mutableStateOf<MarkdownBlockState?>(null) }
    var helper by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var projectId by remember(entry.id) { mutableStateOf(entry.projectId) }
    var parentId by remember(entry.id) { mutableStateOf(entry.parentId) }
    var outcome by remember(entry.id) { mutableStateOf(Outcome.of(entry.outcome)) }
    var moveError by remember { mutableStateOf<String?>(null) }
    var parentError by remember { mutableStateOf<String?>(null) }
    var outcomeError by remember { mutableStateOf<String?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var choosingProject by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var attempting by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var attemptError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }

    val projects = rememberLive { Backend.projects() }
    val index = rememberLive { Backend.noteIndex() }
    val links = rememberLive { Backend.noteLinks() }
    val snippets = rememberLive(entry.id) { Backend.snippets(entry.id) }
    val notesIndex = index.value.orEmpty()
    // The tree as it is now, with this note's parent as just chosen.
    val tree = notesIndex.map { if (it.id == entry.id) it.copy(parentId = parentId) else it }
    val path = ancestors(entry.id, tree)
    val parent = path.lastOrNull()
    val subNotes = childrenOf(entry.id, tree)
    val linkedFrom = links.value?.let { backlinksTo(entry.id, it, notesIndex) }.orEmpty()
    val origin = notesIndex.firstOrNull { it.id == entry.derivedFrom }
    val attempts = notesIndex.filter { it.derivedFrom == entry.id }
    val project = projects.value?.firstOrNull { it.id == projectId }

    val saver = remember(entry.id) {
        Autosaver(Backend.scope, NoteText(entry.title, entry.notes), registry, describe = { Accounts.message(it, "save_note") }) { v ->
            Backend.updateEntry(entry.id) { title(v.title); notes(v.notes) }
        }
    }
    LaunchedEffect(title, body.text) { saver.update(NoteText(title, body.text)) }
    // Save whatever is pending when the note closes.
    DisposableEffect(entry.id) { onDispose { Backend.scope.launch { registry.flushAll() } } }
    // A brand-new note starts with the cursor in the title.
    LaunchedEffect(entry.id) { if (entry.title.isEmpty() && entry.notes.isEmpty()) runCatching { titleFocus.requestFocus() } }

    fun openNote(id: String) {
        scope.launch { registry.flushAll(); nav.navigate("note/$id") }
    }

    /** new_attempt() copies the note as saved, so pending edits are flushed first. */
    fun startAttempt() {
        attempting = true
        attemptError = null
        scope.launch {
            try {
                registry.flushAll()
                nav.navigate(noteRoute(Backend.newAttempt(entry.id)))
            } catch (e: Exception) {
                attemptError = Accounts.message(e, "new_attempt")
                attempting = false
            }
        }
    }

    fun mark(next: Outcome?) {
        val previous = outcome
        outcome = next
        outcomeError = null
        scope.launch {
            try { Backend.updateEntry(entry.id) { outcome(next?.value) } } catch (e: Exception) { outcome = previous; outcomeError = Accounts.message(e, "outcome") }
        }
    }

    fun reparent(next: String?) {
        val previous = parentId
        picking = false
        parentId = next
        parentError = null
        scope.launch {
            try { Backend.updateEntry(entry.id) { parentId(next) } } catch (e: Exception) { parentId = previous; parentError = Accounts.message(e, "parent") }
        }
    }

    fun move(next: String?) {
        val previous = projectId
        projectId = next
        moveError = null
        scope.launch {
            try { Backend.updateEntry(entry.id) { projectId(next) } } catch (e: Exception) { projectId = previous; moveError = Accounts.message(e, "move") }
        }
    }

    val count = snippets.value?.size ?: 0
    val upload = rememberFilePicker(onError = { uploadError = it }) { files ->
        scope.launch { try { Backend.createSnippets(entry.id, count, files) } catch (e: Exception) { uploadError = Accounts.message(e, "upload") } }
    }

    // [[ link autocomplete, pinned above the keyboard, in the Markdown block last typed in.
    val linkBlock = formatBar.last ?: body
    val caret = if (linkBlock.value.selection.collapsed) linkBlock.value.selection.start else -1
    val typing = if (caret >= 0 && !preview) linkQueryAt(linkBlock.text, caret) else null
    var dismissed by remember { mutableStateOf<Int?>(null) }
    val suggestions = if (typing != null && typing.start != dismissed) linkSuggestions(typing.query, notesIndex, entry.id) else emptyList()

    CompositionLocalProvider(LocalCodeKeys provides keys, LocalFormatBar provides formatBar) {
        Box(Modifier.fillMaxSize()) {
            PageColumn(Modifier.fillMaxSize().verticalScroll(rememberRestoredScrollState(entry.id)).padding(bottom = 96.dp)) {
                TopBar(
                    { scope.launch { registry.flushAll(); nav.home() } },
                    back = project?.let { p -> p.name to { nav.project(p.id) } } ?: ("All notes" to { nav.home() }),
                ) {
                    SaveIndicator(registry)
                    TextBtn("Helper", { helper = true })
                    TextBtn(if (preview) "Edit" else "Preview", { preview = !preview })
                    Box {
                        Box(Modifier.size(48.dp).plainClick { menuOpen = true }.semantics { contentDescription = "More" }, contentAlignment = Alignment.Center) {
                            Text("⋮", Modifier.clearAndSetSemantics {}, style = Type.body, color = t.muted)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = t.bg) {
                            MenuItem("Map") { menuOpen = false; scope.launch { registry.flushAll(); nav.navigate(mapRoute(entry.id, 1)) } }
                            MenuItem("New attempt", enabled = !attempting) { menuOpen = false; startAttempt() }
                            MenuItem("Delete note") { menuOpen = false; confirmDelete = true }
                        }
                    }
                }

                if (path.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        path.forEachIndexed { i, n ->
                            if (i > 0) Text("›", style = Type.xs, color = t.faint)
                            TextBtn(displayTitle(n.title), { openNote(n.id) }, Modifier.widthIn(max = 220.dp), style = Type.xs)
                        }
                    }
                }

                BasicTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", " ").take(200) },
                    textStyle = Type.xl.copy(color = t.fg),
                    cursorBrush = SolidColor(t.fg),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { body.focus.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth().padding(top = if (path.isEmpty()) 24.dp else 8.dp).focusRequester(titleFocus).semantics { contentDescription = "Title" },
                    decorationBox = { inner -> Box { if (title.isEmpty()) Text("Title", style = Type.xl, color = t.faint); inner() } },
                )

                // Project
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Project", style = Type.xs, color = t.muted)
                    Box {
                        TextBtn(project?.name ?: "None", { choosingProject = true }, Modifier.widthIn(max = 240.dp), style = Type.xs)
                        DropdownMenu(expanded = choosingProject, onDismissRequest = { choosingProject = false }, containerColor = t.bg) {
                            DropdownMenuItem(text = { Text("None", style = Type.sm, color = if (projectId == null) t.fg else t.muted) }, onClick = { choosingProject = false; move(null) })
                            projects.value.orEmpty().forEach { p ->
                                DropdownMenuItem(text = { Text(p.name, style = Type.sm, color = if (p.id == projectId) t.fg else t.muted) }, onClick = { choosingProject = false; move(p.id) })
                            }
                        }
                    }
                    moveError?.let { Text(it, style = Type.xs, color = t.fg) }
                }

                // Parent
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Parent", style = Type.xs, color = t.muted)
                    TextBtn(parent?.let { displayTitle(it.title) } ?: "None", { picking = !picking }, Modifier.widthIn(max = 240.dp), style = Type.xs)
                    parentError?.let { Text(it, style = Type.xs, color = t.fg) }
                }
                if (picking) ParentPicker(entry.id, notesIndex, parentId, onPick = ::reparent, onClose = { picking = false })

                // Outcome: only the author sets it, by tapping here.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Outcome", style = Type.xs, color = t.muted)
                    Segmented(OUTCOME_CHOICES.map { it.value to it.label }, outcome, ::mark)
                    outcomeError?.let { Text(it, style = Type.xs, color = t.fg) }
                }

                if (preview) {
                    RenderedBody(body.text, notesIndex) { openNote(it) }
                } else {
                    MarkdownBlockEditor(body, NOTE_LABEL, Modifier.padding(top = 16.dp))
                }

                // Code
                Column(Modifier.padding(top = 40.dp).semantics { contentDescription = "Code" }) {
                    snippets.value.orEmpty().forEachIndexed { i, s ->
                        androidx.compose.runtime.key(s.id) {
                            CodeCell(s, i, outcome, pro, registry, preview, notesIndex, ::openNote) { sid, n -> scope.launch { registry.flushAll(); nav.navigate(outputRoute(entry.id, sid, n)) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth().topLine(t.line), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        TextBtn("Add code", { scope.launch { runCatching { Backend.createEmptySnippet(entry.id, count) }.onFailure { uploadError = Accounts.message(it, "upload") } } })
                        TextBtn("Upload file", upload)
                    }
                    ErrorLine(uploadError)
                }

                NoteList("Sub-notes", subNotes, empty = null, onOpen = ::openNote) {
                    TextBtn("New sub-note", {
                        scope.launch {
                            registry.flushAll()
                            runCatching { Backend.createEntry("", projectId, entry.id) }.onSuccess { nav.navigate("note/${it.id}") }
                        }
                    })
                }
                NoteList("Linked from", linkedFrom, empty = "No notes link here.", onOpen = ::openNote)
                if (origin != null) NoteList("Derived from", listOf(origin), empty = null, onOpen = ::openNote)
                NoteList("Attempts", attempts, empty = null, onOpen = ::openNote) {
                    Column {
                        TextBtn("New attempt", ::startAttempt, enabled = !attempting)
                        ErrorLine(attemptError, Modifier.padding(top = 0.dp))
                    }
                }

            }

            // Pinned above the keyboard: [[ suggestions while typing a link, the body's format bar, or the code symbol row.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (suggestions.isNotEmpty() && typing != null) {
                    Column(Modifier.fillMaxWidth().topLine(t.line).then(Modifier.semantics { contentDescription = "Link suggestions" })) {
                        suggestions.forEach { n ->
                            PageColumn {
                                TextBtn(displayTitle(n.title), {
                                    val next = completeLink(linkBlock.text, typing.start, caret, n)
                                    linkBlock.replace(next.text, next.caret)
                                }, Modifier.fillMaxWidth(), strong = true, style = Type.body)
                            }
                        }
                        PageColumn { TextBtn("Close", { dismissed = typing.start }, style = Type.xs) }
                    }
                } else if (formatBar.target != null && !preview) {
                    formatBar.target?.let { b -> FormatBar(b, onMore = { sheetFor = b }) }
                } else {
                    keys.target?.let { CodeKeysRow(it) }
                }
            }
            sheetFor?.let { b ->
                FormatSheet(
                    onPick = { f -> sheetFor = null; b.apply(f); runCatching { b.focus.requestFocus() } },
                    onDismiss = { sheetFor = null },
                )
            }
            if (helper) {
                val code = snippets.value.orEmpty()
                val lang = helperLanguage(code.map { it.language })
                HelperSheet(
                    noteTitle = displayTitle(title),
                    // Read on the tap that asks, never before: the note's text, its code cells and their last errors.
                    noteBody = { body.text },
                    cells = { helperCells(code) },
                    language = lang,
                    onInsertCode = { c ->
                        scope.launch {
                            runCatching { Backend.createSnippets(entry.id, count, listOf(CodeFile("", lang, c))) }
                                .onFailure { uploadError = Accounts.message(it, "upload") }
                        }
                        helper = false
                    },
                    onDismiss = { helper = false },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog("Delete this note and its code?") { ok ->
            confirmDelete = false
            if (ok) scope.launch {
                registry.flushAll()
                // A note filed in a project (sub-notes carry their own project id) returns to that project.
                runCatching { Backend.deleteEntry(entry.id) }.onSuccess { nav.leaveDeletedNote(projectId) }
            }
        }
    }
}

/** One row of the note's ⋮ menu: 48dp tall, named for TalkBack by its text. */
@Composable
private fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Type.sm, color = t.fg) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = text },
    )
}

/** Quiet status: "Saved" after a save, "Not saved" on failure, nothing otherwise. */
/**
 * The note body as rendered: the editor's Preview and the read-only landing preview (web: note-parts.tsx).
 * `reserve` keeps the editor's tap-to-type height; read-only views turn it off.
 */
@Composable
fun RenderedBody(text: String, notes: List<NoteIndexItem> = emptyList(), reserve: Boolean = true, onOpenNote: (String) -> Unit = {}) {
    Box(Modifier.padding(top = 16.dp).then(if (reserve) Modifier.heightIn(min = 96.dp) else Modifier)) {
        if (text.isNotBlank()) NoteMarkdown(text, notes, onOpenNote)
        else Text("Nothing written.", style = Type.body, color = t.faint)
    }
}

@Composable
private fun SaveIndicator(registry: SaveRegistry) {
    val s by registry.summary.collectAsState()
    val text = when (s.state) { SaveState.Saved -> "Saved"; SaveState.Error -> "Not saved"; else -> "" }
    Text(text, Modifier.semantics { contentDescription = "Save status" + (s.error?.let { ": $it" } ?: "") }, style = Type.xs, color = if (s.state == SaveState.Error) t.fg else t.faint)
}

/** A titled list of notes (sub-notes, backlinks), each row a 48dp link. */
@Composable
private fun NoteList(title: String, notes: List<NoteIndexItem>, empty: String?, onOpen: (String) -> Unit, footer: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(top = 40.dp)) {
        Text(title, style = Type.xs, color = t.muted)
        notes.forEachIndexed { i, n ->
            if (i > 0) Hairline()
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).plainClick { onOpen(n.id) }, contentAlignment = Alignment.CenterStart) {
                Text(displayTitle(n.title), style = Type.body, color = if (n.title.isBlank()) t.muted else t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (notes.isEmpty() && empty != null) Text(empty, Modifier.padding(vertical = 12.dp), style = Type.sm, color = t.faint)
        if (footer != null) Box(Modifier.fillMaxWidth().topLine(t.line)) { footer() }
    }
}

/** Pick a note's parent: search, or choose None. Only notes that keep the tree a tree are offered. */
@Composable
private fun ParentPicker(noteId: String, notes: List<NoteIndexItem>, current: String?, onPick: (String?) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val terms = searchTerms(query)
    val matches = parentCandidates(noteId, notes).filter { n -> n.id != current && terms.all { n.title.lowercase().contains(it) } }.take(8)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).topLine(t.line).bottomLine(t.line)) {
        UnderlineField(query, { query = it }, "Search notes", Modifier.focusRequester(focus))
        if (current != null) { TextBtn("None", { onPick(null) }, Modifier.fillMaxWidth()); Hairline() }
        matches.forEach { n -> TextBtn(displayTitle(n.title), { onPick(n.id) }, Modifier.fillMaxWidth(), strong = true); Hairline() }
        if (matches.isEmpty()) Text("No notes match.", Modifier.padding(vertical = 12.dp), style = Type.sm, color = t.muted)
        TextBtn("Cancel", onClose)
    }
}
