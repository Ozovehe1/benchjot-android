@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.benchjot.ui

import app.benchjot.core.runsOnServer
import app.benchjot.core.runsOnDevice
import app.benchjot.core.endPosition
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import app.benchjot.core.assistantLanguage
import app.benchjot.core.Note
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import app.benchjot.data.OutputStore
import app.benchjot.core.Cell
import app.benchjot.core.AgentNote
import app.benchjot.core.SeeRequest
import app.benchjot.core.OtherNote
import app.benchjot.core.OtherNoteRow
import app.benchjot.core.OtherNotes
import app.benchjot.core.SavedNote
import app.benchjot.core.SearchFrom
import app.benchjot.core.NoteWrite
import app.benchjot.core.placeNoteText
import app.benchjot.core.rankOtherNotes
import app.benchjot.core.OTHER_NOTES_MAX
import app.benchjot.core.toSee
import app.benchjot.core.withSeen
import app.benchjot.data.WebApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Placeholder and accessible name of the note body (the user's wording). */
private const val NOTE_LABEL = "Experiment note"

private data class NoteText(val title: String, val notes: String)

@Composable
fun NoteScreen(id: String, nav: NavHostController, cell: String? = null) {
    // The note itself loads once (edits live here); lists around it reload after writes.
    val loaded by produceState<Pair<Boolean, Note?>>(false to null, id) {
        value = true to runCatching { Backend.note(id) }.getOrNull()
    }
    val projects = rememberLive { Backend.projects() }
    val (done, note) = loaded
    val project = projects.value?.firstOrNull { it.id == note?.projectId }
    val back: Pair<String, () -> Unit> =
        if (project != null) project.name to { nav.project(project.id) } else "All notes" to { nav.home() }
    if (!done) {
        PageColumn { TopBar({ nav.home() }, back) }
        return
    }
    if (note == null) {
        PageColumn {
            TopBar({ nav.home() }, back)
            Muted("This note doesn’t exist.", Modifier.padding(vertical = 40.dp))
        }
        return
    }
    if (note.deletedAt != null) {
        DeletedNote(note, nav, back)
        return
    }
    Editor(note, nav, cell)
}

/** A note in Recently Deleted, opened from a link: not editable; Restore or Delete now (the web's DeletedNote). */
@Composable
private fun DeletedNote(note: Note, nav: NavHostController, back: Pair<String, () -> Unit>) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    fun act(action: String, then: () -> Unit, f: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { f(); then() } catch (e: Exception) { error = Accounts.message(e, action) }
            busy = false
        }
    }
    PageColumn {
        TopBar({ nav.home() }, back)
        Text(displayTitle(note.title), Modifier.padding(top = 16.dp), style = Type.h1, color = if (note.title.isBlank()) t.muted else t.fg)
        Muted("This note is in Recently Deleted (${app.benchjot.core.daysLeftText(note.deletedAt!!)}). Restore it to open and edit it again.", Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledBtn("Restore", { act("restore", { nav.navigate(noteRoute(note.id, null)) { popUpTo("home") } }) { Backend.restoreNote(note.id) } }, loading = busy)
            TextBtn("Delete now", { confirm = true }, enabled = !busy, color = t.fail)
        }
        error?.let { Notice(it, Modifier.padding(top = 12.dp), onDismiss = { error = null }) }
    }
    if (confirm) {
        ConfirmDialog("Delete this note and its code for good? This can’t be undone.") { ok ->
            confirm = false
            if (ok) act("delete_note", { nav.navigate("deleted") { popUpTo("home") } }) { Backend.deleteNotesForever(listOf(note.id)) }
        }
    }
}

@Composable
private fun Editor(note: Note, nav: NavHostController, openAt: String? = null) {
    val scope = rememberCoroutineScope()
    val registry = remember(note.id) { SaveRegistry() }
    // Back online: save what's waiting now, not at the next retry.
    val online = rememberOnline { scope.launch { registry.flushAll() } }
    val keys = remember { CodeKeysController() }
    var title by remember(note.id) { mutableStateOf(note.title) }
    // The note body: one live Markdown block (its own text, selection and undo history).
    val body = remember(note.id) { MarkdownBlockState(note.notes) }
    // The one format bar acts on the focused Markdown block; the "+" sheet outlives that focus.
    val formatBar = remember { FormatBarController() }
    var sheetFor by remember { mutableStateOf<MarkdownBlockState?>(null) }
    var assistant by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var projectId by remember(note.id) { mutableStateOf(note.projectId) }
    var parentId by remember(note.id) { mutableStateOf(note.parentId) }
    var outcome by remember(note.id) { mutableStateOf(Outcome.of(note.outcome)) }
    var moveError by remember { mutableStateOf<String?>(null) }
    var parentError by remember { mutableStateOf<String?>(null) }
    var outcomeError by remember { mutableStateOf<String?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var choosingProject by remember { mutableStateOf(false) }
    var attempting by remember { mutableStateOf(false) }
    var pinned by remember(note.id) { mutableStateOf(note.pinnedAt != null) }
    var menuOpen by remember { mutableStateOf(false) }
    var attemptError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }

    val projects = rememberLive { Backend.projects() }
    val index = rememberLive { Backend.noteIndex() }
    val links = rememberLive { Backend.noteLinks() }
    val cells = rememberLive(note.id) { Backend.cells(note.id) }
    var exporting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val notesIndex = index.value.orEmpty()
    // The tree as it is now, with this note's parent as just chosen.
    val tree = notesIndex.map { if (it.id == note.id) it.copy(parentId = parentId) else it }
    val path = ancestors(note.id, tree)
    val parent = path.lastOrNull()
    val subNotes = childrenOf(note.id, tree)
    val linkedFrom = links.value?.let { backlinksTo(note.id, it, notesIndex) }.orEmpty()
    val origin = notesIndex.firstOrNull { it.id == note.derivedFrom }
    val attempts = notesIndex.filter { it.derivedFrom == note.id }
    val project = projects.value?.firstOrNull { it.id == projectId }

    val saver = remember(note.id) {
        Autosaver(Backend.scope, NoteText(note.title, note.notes), registry, describe = { Accounts.message(it, "save_note") }) { v ->
            Backend.updateNote(note.id) { title(v.title); notes(v.notes) }
        }
    }
    LaunchedEffect(title, body.text) { saver.update(NoteText(title, body.text)) }
    // Save whatever is pending when the note closes.
    DisposableEffect(note.id) { onDispose { Backend.scope.launch { registry.flushAll() } } }
    // A brand-new note starts with the cursor in the title.
    LaunchedEffect(note.id) { if (note.title.isEmpty() && note.notes.isEmpty()) runCatching { titleFocus.requestFocus() } }

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
                nav.navigate(noteRoute(Backend.newAttempt(note.id)))
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
            try { Backend.updateNote(note.id) { outcome(next?.value) } } catch (e: Exception) { outcome = previous; outcomeError = Accounts.message(e, "outcome") }
        }
    }

    fun reparent(next: String?) {
        val previous = parentId
        picking = false
        parentId = next
        parentError = null
        scope.launch {
            try { Backend.updateNote(note.id) { parentId(next) } } catch (e: Exception) { parentId = previous; parentError = Accounts.message(e, "parent") }
        }
    }

    fun move(next: String?) {
        val previous = projectId
        projectId = next
        moveError = null
        scope.launch {
            try { Backend.updateNote(note.id) { projectId(next) } } catch (e: Exception) { projectId = previous; moveError = Accounts.message(e, "move") }
        }
    }

    val count = endPosition(cells.value.orEmpty()) // where a new cell goes: after the last one

    // The voice agent works on the cells through the same controls the user's taps use (CellControls,
    // handed over by each shown cell); a cell it adds is ready once the screen shows it.
    val controls = remember(note.id) { mutableMapOf<String, CellControls>() }
    // Run all (as on the web): every cell that can run, in order, stopping at the first that fails;
    // Stop ends the whole queue; then it says where it stopped. Each cell can be jumped to.
    var runAll by remember(note.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var runAllFailed by remember(note.id) { mutableStateOf<Pair<String, Int>?>(null) }
    var runAllJob by remember(note.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val jumpTo = remember(note.id) { HashMap<String, androidx.compose.foundation.relocation.BringIntoViewRequester>() }
    fun goToCell(id: String) { scope.launch { jumpTo[id]?.bringIntoView() } }
    // Opened from a search result at a cell: go to it once the cells are shown.
    var openedAt by remember(note.id) { mutableStateOf(false) }
    val onControls: (String, CellControls?) -> Unit = remember(note.id) { { sid, c -> if (c == null) controls.remove(sid) else controls[sid] = c } }
    fun control(sid: String) = controls[sid] ?: throw IllegalStateException("that cell isn't on the screen")
    val agentNote = remember(note.id) {
        object : AgentNote {
            override suspend fun outcome(): String? = outcome?.value
            override suspend fun read(): Triple<String, String, List<Cell>> {
                registry.flushAll()
                return Triple(displayTitle(title), body.text, Backend.cells(note.id))
            }
            override suspend fun add(language: String, code: String): String {
                val at = endPosition(Backend.cells(note.id))
                val sid = Backend.createCells(note.id, at, listOf(CodeFile("", language, code))).firstOrNull() ?: throw IllegalStateException("the cell wasn't added")
                withTimeoutOrNull(15_000) { while (controls[sid] == null) delay(100) } ?: throw IllegalStateException("the new cell didn't appear")
                return sid
            }
            override suspend fun setCode(id: String, code: String) = control(id).setCode(code)
            override suspend fun run(id: String, where: String?) { control(id).run(where) }
            override suspend fun setBody(text: String) { body.replace(text, text.length) }
            override val others = object : OtherNotes {
                override suspend fun find(query: String, from: SearchFrom?): List<OtherNote> = coroutineScope {
                    // Around the note asked (a question passed on), else this one.
                    val at = from ?: SearchFrom(note.id, note.parentId, body.text)
                    val matched = searchTerms(query).isNotEmpty()
                    // Words from the phone's copy (works offline); meaning from the server, when it answers.
                    val similar = async { WebApi.closeInMeaning(if (matched) query else "", at.id) }
                    val all = Backend.notes("", Backend.Scope()).map { OtherNoteRow(it.id, it.title, it.outcome, it.updatedAt, it.parentId) }
                    val close = similar.await()
                    if (!matched) return@coroutineScope rankOtherNotes(at.id, at.parentId, at.body, all, false, similar = close)
                    val words = Backend.notes(query, Backend.Scope()).take(OTHER_NOTES_MAX + 1).map { OtherNoteRow(it.id, it.title, it.outcome, it.updatedAt, it.parentId) }
                    val extra = all.filter { r -> r.id in close && words.none { it.id == r.id } }.map { it.copy(matched = false) }
                    rankOtherNotes(at.id, at.parentId, at.body, words + extra, true, similar = close)
                }
                override suspend fun open(id: String): SavedNote? {
                    val other = Backend.note(id)?.takeIf { it.deletedAt == null } ?: return null
                    return SavedNote(other.title, other.notes, Backend.cells(id), other.outcome, other.parentId)
                }
            }
            override val sees = true
            override suspend fun see(id: String) {
                val cell = Backend.cells(note.id).firstOrNull { it.id == id } ?: return
                val run = cell.lastRun ?: return
                val outputs = run.outputs ?: return
                val items = toSee(outputs)
                if (items.isEmpty()) return
                val reply = try {
                    WebApi.see(SeeRequest(outputs.dir, items, displayTitle(title).take(300), cell.content.take(3000), run.stdout.takeLast(1500)))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return
                }
                if (reply.seen.isEmpty()) return
                // Kept with that run only: a run made meanwhile has pictures the seer didn't look at.
                val now = Backend.cells(note.id).firstOrNull { it.id == id }?.lastRun ?: return
                val nowOutputs = now.outputs ?: return
                if (now.ranAt != run.ranAt) return
                try { Backend.saveLastRun(id, now.copy(outputs = withSeen(nowOutputs, reply.seen))) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            override suspend fun remove(id: String) {
                val cell = Backend.cells(note.id).firstOrNull { it.id == id } ?: return
                controls[id]?.stop()
                Backend.deleteCell(id)
                OutputStore.delete(cell.lastRun?.outputs)
            }
        }
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val agent = remember(note.id) { AgentSession(scope, note.id, agentNote, appContext) { controls.values.forEach { it.stop() } } }
    // A request left mid-way on this note goes on once its cells are on the screen (they register as they appear).
    val cellsReady = cells.value != null
    androidx.compose.runtime.LaunchedEffect(note.id, cellsReady) {
        if (!cellsReady) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        agent.resumeIfKept()
    }
    val upload = rememberFilePicker(onError = { uploadError = it }) { files ->
        scope.launch { try { Backend.createCells(note.id, count, files) } catch (e: Exception) { uploadError = Accounts.message(e, "upload") } }
    }

    // [[ link autocomplete, pinned above the keyboard, in the Markdown block last typed in.
    val linkBlock = formatBar.last ?: body
    val caret = if (linkBlock.value.selection.collapsed) linkBlock.value.selection.start else -1
    val typing = if (caret >= 0 && !preview) linkQueryAt(linkBlock.text, caret) else null
    var dismissed by remember { mutableStateOf<Int?>(null) }
    val suggestions = if (typing != null && typing.start != dismissed) linkSuggestions(typing.query, notesIndex, note.id) else emptyList()

    CompositionLocalProvider(LocalCodeKeys provides keys, LocalFormatBar provides formatBar) {
        var assistantHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.fillMaxSize()) {
            // Room at the end for the Assistant's panel while it's open, so nothing stays hidden under it.
            PageColumn(Modifier.fillMaxSize().verticalScroll(rememberRestoredScrollState(note.id)).padding(bottom = 96.dp + if (assistant) assistantHeight else 0.dp)) {
                TopBar(
                    { scope.launch { registry.flushAll(); nav.home() } },
                    back = project?.let { p -> p.name to { nav.project(p.id) } } ?: ("All notes" to { nav.home() }),
                ) {
                    SaveIndicator(registry, online) { scope.launch { registry.flushAll() } }
                    FilledBtn("Assistant", { assistant = true }, variant = ButtonVariant.Soft, small = true, icon = Icons.Chat)
                    Box(Modifier.size(44.dp).plainClick { preview = !preview }.semantics { contentDescription = if (preview) "Edit" else "Preview" }, contentAlignment = Alignment.Center) {
                        BjIcon(if (preview) Icons.Pen else Icons.Eye, tint = t.muted)
                    }
                    Box {
                        Box(Modifier.size(48.dp).plainClick { menuOpen = true }.semantics { contentDescription = "More" }, contentAlignment = Alignment.Center) {
                            Text("⋮", Modifier.clearAndSetSemantics {}, style = Type.body, color = t.muted)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = t.bg) {
                            MenuItem("Share") { menuOpen = false; scope.launch { registry.flushAll(); sharing = true } }
                            MenuItem("Map") { menuOpen = false; scope.launch { registry.flushAll(); nav.navigate(mapRoute(note.id, 1)) } }
                            MenuItem("New attempt", enabled = !attempting) { menuOpen = false; startAttempt() }
                            MenuItem("Export note") { menuOpen = false; scope.launch { registry.flushAll(); exporting = true } }
                            MenuItem(if (pinned) "Unpin note" else "Pin note") {
                                menuOpen = false
                                val next = !pinned
                                pinned = next
                                scope.launch { try { Backend.setPinned(note.id, next) } catch (e: Exception) { pinned = !next; uploadError = Accounts.message(e, "pin") } }
                            }
                            // Deleting asks nothing: the note waits 30 days in Recently Deleted and can be restored there.
                            MenuItem("Delete note") {
                                menuOpen = false
                                scope.launch {
                                    registry.flushAll()
                                    // A note filed in a project (sub-notes carry their own project id) returns to that project.
                                    try { Backend.deleteNote(note.id); nav.leaveDeletedNote(projectId) } catch (e: Exception) { uploadError = Accounts.message(e, "delete_note") }
                                }
                            }
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
                    textStyle = Type.title.copy(color = t.fg),
                    cursorBrush = SolidColor(t.fg),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { body.focus.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth().padding(top = if (path.isEmpty()) 24.dp else 8.dp).focusRequester(titleFocus).semantics { contentDescription = "Title" },
                    decorationBox = { inner -> Box { if (title.isEmpty()) Text("Title", style = Type.title.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = t.faint); inner() } },
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
                if (picking) ParentPicker(note.id, notesIndex, parentId, onPick = ::reparent, onClose = { picking = false })

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
                    val shown = cells.value.orEmpty()
                    if (openAt != null && !openedAt && shown.any { it.id == openAt }) LaunchedEffect(openAt) {
                        kotlinx.coroutines.delay(150) // the cells are laid out
                        openedAt = true
                        jumpTo[openAt]?.bringIntoView()
                    }
                    if (shown.isNotEmpty()) CellsBar(
                        shown, runAll, runAllFailed,
                        onGo = ::goToCell,
                        onRunAll = {
                            runAllJob = scope.launch {
                                runAllFailed = null
                                val order = shown.withIndex().filter { (_, c) -> runsOnDevice(c.language) || runsOnServer(c.language) }
                                try {
                                    for ((k, iv) in order.withIndex()) {
                                        runAll = (k + 1) to order.size
                                        val c = controls[iv.value.id] ?: continue
                                        val ended = runCatching { c.run() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; app.benchjot.core.RunOutcome.Error }
                                        if (ended == app.benchjot.core.RunOutcome.Error || ended == app.benchjot.core.RunOutcome.Timeout) { runAllFailed = iv.value.id to (iv.index + 1); break }
                                        if (ended == app.benchjot.core.RunOutcome.Stopped) break
                                    }
                                } finally {
                                    runAll = null
                                }
                            }
                        },
                        onStop = { runAllJob?.cancel(); controls.values.forEach { it.stop() } },
                        onDismissFailed = { runAllFailed = null },
                    )
                    shown.forEachIndexed { i, s ->
                        androidx.compose.runtime.key(s.id) {
                            Box(Modifier.bringIntoViewRequester(jumpTo.getOrPut(s.id) { androidx.compose.foundation.relocation.BringIntoViewRequester() }))
                            CodeCell(s, i, outcome, registry, preview, notesIndex, ::openNote, { sid, n -> scope.launch { registry.flushAll(); nav.navigate(outputRoute(note.id, sid, n)) } }, onControls)
                        }
                    }
                    Row(Modifier.fillMaxWidth().topLine(t.line), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        TextBtn("Add code", { scope.launch { runCatching { Backend.createEmptyCell(note.id, count) }.onFailure { uploadError = Accounts.message(it, "upload") } } })
                        TextBtn("Upload file", upload)
                    }
                    ErrorLine(uploadError, onDismiss = { uploadError = null })
                }

                NoteList("Sub-notes", subNotes, empty = null, onOpen = ::openNote) {
                    TextBtn("New sub-note", {
                        scope.launch {
                            registry.flushAll()
                            runCatching { Backend.createNote("", projectId, note.id) }
                                .onSuccess { nav.navigate("note/${it.id}") }
                                .onFailure { attemptError = Accounts.message(it) }
                        }
                    })
                }
                NoteList("Linked from", linkedFrom, empty = "No notes link here.", onOpen = ::openNote)
                if (origin != null) NoteList("Derived from", listOf(origin), empty = null, onOpen = ::openNote)
                NoteList("Attempts", attempts, empty = null, onOpen = ::openNote) {
                    Column {
                        TextBtn("New attempt", ::startAttempt, enabled = !attempting)
                        ErrorLine(attemptError, Modifier.padding(top = 0.dp), onDismiss = { attemptError = null })
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
                } else if (keys.target != null) {
                    keys.target?.let { CodeKeysRow(it) }
                } else if (!assistant) {
                    AgentBar(agent, onOpen = { assistant = true })
                }
            }
            sheetFor?.let { b ->
                FormatSheet(
                    onPick = { f -> sheetFor = null; b.apply(f); runCatching { b.focus.requestFocus() } },
                    onDismiss = { sheetFor = null },
                )
            }
            if (assistant) {
                val code = cells.value.orEmpty()
                val lang = assistantLanguage(code.map { it.language })
                CompositionLocalProvider(LocalApplyProposal provides { p ->
                    // A change the Assistant proposed to another note, applied on the user's tap (never twice).
                    val other = Backend.note(p.noteId)?.takeIf { it.deletedAt == null } ?: throw IllegalStateException("That note is gone.")
                    if (other.notes.contains(p.text.trim())) "already"
                    else {
                        Backend.updateNote(p.noteId) { notes(placeNoteText(other.notes, NoteWrite(p.text, p.after)).first) }
                        "applied"
                    }
                }) {
                AssistantSheet(
                    noteId = note.id,
                    language = lang,
                    onInsertCode = { c, codeLanguage ->
                        scope.launch {
                            runCatching { Backend.createCells(note.id, count, listOf(CodeFile("", codeLanguage, c))) }
                                .onFailure { uploadError = Accounts.message(it, "upload") }
                        }
                        assistant = false
                    },
                    onDismiss = { assistant = false },
                    agent = agent,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onHeight = { assistantHeight = it },
                )
                }
            }
        }
    }

    if (exporting) ExportDialog(note, cells.value.orEmpty(), onDismiss = { exporting = false })
    if (sharing) ShareDialog(note.id, note.title.ifBlank { "Untitled" }, onDismiss = { sharing = false })
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

/**
 * The note's save status, quiet when all is well ("Saving…", "Saved"). A failed save says so with
 * Save now (it is retried by itself too, and as soon as the network is back); offline, it says the
 * edits wait.
 */
@Composable
private fun SaveIndicator(registry: SaveRegistry, online: Boolean, onSaveNow: () -> Unit) {
    val s by registry.summary.collectAsState()
    val waiting by app.benchjot.data.Offline.waiting.collectAsState()
    val unsaved = s.state == SaveState.Error || s.state == SaveState.Pending || s.state == SaveState.Saving
    val describe = Modifier.semantics(mergeDescendants = true) { contentDescription = "Save status: " + when {
        waiting > 0 && !online -> "saved on this phone, syncs when online"
        s.state == SaveState.Error -> "not saved" + (s.error?.let { ", $it" } ?: "")
        s.state == SaveState.Saving -> "saving"
        s.state == SaveState.Saved -> "saved"
        else -> "nothing to save"
    } }
    Row(describe, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            // Saved on the phone first: offline, the changes wait there and sync when the network is back.
            waiting > 0 && !online && !unsaved -> {
                Box(Modifier.size(6.dp).background(t.muted, CircleShape))
                Text("Saved on this phone · syncs when online", style = Type.xs, color = t.muted)
            }
            waiting > 0 && !unsaved -> { Spinner(size = 12.dp); Text("Syncing…", style = Type.xs, color = t.faint) }
            s.state == SaveState.Error -> {
                Box(Modifier.size(6.dp).background(t.fail, CircleShape))
                Text("Not saved", style = Type.xs, color = t.fg)
                TextBtn("Save now", onSaveNow, strong = true, style = Type.xs)
            }
            s.state == SaveState.Saving -> { Spinner(size = 12.dp); Text("Saving…", style = Type.xs, color = t.faint) }
            s.state == SaveState.Saved -> Text("Saved", style = Type.xs, color = t.faint)
        }
    }
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

/**
 * Above the cells (the web's CellsBar): each cell's state at a glance as a numbered chip that jumps
 * to it (how its last run ended, or that the code changed since), Run all with its progress and
 * Stop, and where Run all stopped.
 */
@Composable
private fun CellsBar(
    cells: List<app.benchjot.core.Cell>,
    running: Pair<Int, Int>?,
    failed: Pair<String, Int>?,
    onGo: (String) -> Unit,
    onRunAll: () -> Unit,
    onStop: () -> Unit,
    onDismissFailed: () -> Unit,
) {
    val firstBad = app.benchjot.core.firstFailed(cells.map { app.benchjot.core.cellState(it.content, it.lastRun) })
    val runnable = cells.any { runsOnDevice(it.language) || runsOnServer(it.language) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (firstBad != null && running == null && failed == null) TextBtn("Cell $firstBad failed. Go to it", { onGo(cells[firstBad - 1].id) }, style = Type.sm)
            Spacer(Modifier.weight(1f))
            if (runnable) {
                if (running != null) FilledBtn("Stop", onStop, variant = ButtonVariant.Neutral, small = true, icon = Icons.Stop)
                else FilledBtn("Run all", onRunAll, variant = ButtonVariant.Soft, small = true, icon = Icons.Play)
            }
        }
        if (running != null) Working("Running cell ${running.first} of ${running.second}")
        if (failed != null && running == null) Notice(
            "Cell ${failed.second} failed, so the cells after it didn’t run.",
            actions = listOf(NoticeAction("Go to cell ${failed.second}") { onGo(failed.first) }),
            onDismiss = onDismissFailed,
        )
    }
}

