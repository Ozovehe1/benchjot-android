package app.benchjot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.Autosaver
import app.benchjot.core.Chunk
import app.benchjot.core.DiffKind
import app.benchjot.core.FixRequest
import app.benchjot.core.LANGUAGES
import app.benchjot.core.LastRun
import app.benchjot.core.Outcome
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.SaveRegistry
import app.benchjot.core.Cell
import app.benchjot.core.Stream
import app.benchjot.core.codeHash
import app.benchjot.core.detectLanguage
import app.benchjot.core.diffHunks
import app.benchjot.core.downloadName
import app.benchjot.core.fixableCount
import app.benchjot.core.fromLastRun
import app.benchjot.core.isLanguageId
import app.benchjot.core.languageChosen
import app.benchjot.core.languageConfig
import app.benchjot.core.lineDiff
import app.benchjot.core.outputText
import app.benchjot.core.runSummary
import app.benchjot.core.runsOnDevice
import app.benchjot.core.runsOnServer
import app.benchjot.core.toLastRun
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.LocalOutputs
import app.benchjot.data.OutputStore
import app.benchjot.data.WebApi
import app.benchjot.core.SavedOutputs
import app.benchjot.run.DeviceRunner
import app.benchjot.run.ServerRunner
import app.benchjot.core.OutputBuffer
import app.benchjot.core.RunHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// One code block, as the web's cell-block.tsx: language, Run (on the device,
// free) / Run on server (every account; Python runs in the background, up to an hour), the editor, its output, Fix, Copy, Download, Remove.

private data class CodeValue(val language: String, val content: String)

private data class Fix(val before: String, val code: String, val summary: String)

/**
 * `before` / `after` are shown directly above the cell and directly below its run output (the cell's
 * notes; the block knows nothing of what they are). `onBeforeRemove` runs before the row is deleted.
 */
@Composable
fun CellBlock(
    cell: Cell,
    index: Int,
    outcome: Outcome?,
    registry: SaveRegistry,
    before: @Composable () -> Unit = {},
    after: @Composable () -> Unit = {},
    onBeforeRemove: suspend () -> Unit = {},
    onOpenOutput: (cellId: String, index: Int) -> Unit = { _, _ -> },
    /** Hands the screen this cell's controls while it is shown (null once it's gone), for the voice agent. */
    onControls: (String, CellControls?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val download = rememberDownloader()
    val initial = if (isLanguageId(cell.language)) cell.language else "text"
    var language by remember(cell.id) { mutableStateOf(initial) }
    val code = remember(cell.id) { CodeState(cell.content) }
    // A language counts as chosen once the user picks it, or once the block was saved with code in it.
    var chosen by remember(cell.id) { mutableStateOf(languageChosen(initial, cell.content)) }
    var copied by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val saved = remember(cell.id) { cell.lastRun?.let(::fromLastRun) }
    val output = remember(cell.id) { mutableStateListOf<Chunk>().apply { saved?.first?.let(::addAll) } }
    var hasOutput by remember(cell.id) { mutableStateOf(saved != null) }
    var result by remember(cell.id) { mutableStateOf(saved?.second) }
    var ranHash by remember(cell.id) { mutableStateOf(cell.lastRun?.codeHash) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Figures, tables and charts: the live run's until it's saved, then the stored run's.
    // A run made offline shows the figures kept on the phone until they are uploaded (PendingOutputs).
    var live by remember(cell.id) { mutableStateOf(cell.lastRun?.takeIf { it.outputs == null }?.let { app.benchjot.data.PendingOutputs.of(cell.id, it.ranAt) }) }
    var savedOutputs by remember(cell.id) { mutableStateOf(cell.lastRun?.outputs) }
    var hideSaved by remember(cell.id) { mutableStateOf(false) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var fixing by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<RunHandle?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // Lint starts once the block is touched, so just reading a note never runs Ruff (as the web).
    var touched by remember(cell.id) { mutableStateOf(false) }
    var fixable by remember(cell.id) { mutableStateOf(0) }
    val ruff = remember { DeviceRunner.ruff(context) }

    val saver = remember(cell.id) {
        Autosaver(Backend.scope, CodeValue(initial, cell.content), registry, describe = { Accounts.message(it, "save_note") }) { v -> Backend.updateCellCode(cell.id, v.language, v.content) }
    }
    DisposableEffect(saver) { onDispose { Backend.scope.launch { saver.flush(); registry.remove(saver) } } }

    val runnable = runsOnDevice(language)
    val serverable = runsOnServer(language)
    val content = code.text
    val lastError = if (result?.outcome == RunOutcome.Error) result?.error?.text.orEmpty() else ""
    val stale = !running && hasOutput && ranHash != null && ranHash != codeHash(content)

    fun persist(run: LastRun?) {
        saveError = null
        Backend.scope.launch {
            try { Backend.saveLastRun(cell.id, run) } catch (e: Exception) { saveError = Accounts.message(e, "save_output") }
        }
    }

    fun edited() {
        touched = true
        if (!chosen) language = detectLanguage(code.text) ?: initial
        saver.update(CodeValue(language, code.text))
    }

    /** Uploads the run's outputs, saves the run, then deletes the run it replaced (the web's persistRun). */
    fun persistRun(chunks: List<Chunk>, r: RunResult, where: String, src: String, local: LocalOutputs?): Job {
        saveError = null
        val previous = savedOutputs
        return Backend.scope.launch {
            var uploaded: SavedOutputs? = null
            // Offline, the figures stay on the phone and upload with the next sync (PendingOutputs).
            var keepHere = false
            if (local != null) {
                try {
                    uploaded = OutputStore.upload(cell.id, local)
                } catch (e: Exception) {
                    if (app.benchjot.core.isTransient(Accounts.facts(e))) keepHere = true else saveError = Accounts.message(e, "save_figures")
                }
            }
            try {
                val run = toLastRun(chunks, r, where, src, outputs = uploaded)
                Backend.saveLastRun(cell.id, run)
                if (keepHere && local != null) {
                    app.benchjot.data.PendingOutputs.keep(cell.id, run, local)
                    savedOutputs = null; hideSaved = false // the live outputs stay on screen, from the kept copy next time
                    return@launch
                }
                savedOutputs = uploaded; live = null; hideSaved = false
                if (previous != null && previous.dir != uploaded?.dir) OutputStore.delete(previous)
                if (uploaded != null) local?.dir?.deleteRecursively() // the cache holds a copy now
                // A background run's stored result is no longer needed once the cell keeps it.
                r.serverRunId?.let { id -> runCatching { Backend.clearServerRunResult(id) } }
            } catch (e: Exception) {
                saveError = Accounts.message(e, "save_output")
                OutputStore.delete(uploaded) // the live outputs stay on screen from their folder
            }
        }
    }

    fun clearOutput() {
        val previous = savedOutputs
        output.clear(); hasOutput = false; result = null; fix = null; ranHash = null
        live = null; savedOutputs = null
        persist(null)
        Backend.scope.launch { OutputStore.delete(previous) }
    }

    /** Shows a finished run and saves it as the cell's last run. */
    fun finish(buffer: OutputBuffer, r: RunResult, onDevice: Boolean, src: String, outDir: java.io.File, serverOutputs: LocalOutputs?): Job {
        handle = null
        status = ""
        result = r
        running = false
        val local = if (onDevice) runCatching { LocalOutputs.read(outDir) }.getOrNull() else serverOutputs
        live = local
        return persistRun(buffer.chunks, r, if (onDevice) "browser" else "server", src, local)
    }

    /** Server events for this cell: its status, output in order, and its figures, tables and charts unpacked into [outDir]. */
    fun serverEvents(buffer: OutputBuffer, outDir: java.io.File, onOutputs: (LocalOutputs?) -> Unit) = ServerRunner.Events(
        onStatus = { status = it },
        onChunk = { buffer.add(it.stream, it.text) },
        onOutputs = { o -> onOutputs(runCatching { LocalOutputs.fromServer(o, outDir) }.getOrElse { e -> saveError = Accounts.message(e, "load_figures"); null }) },
    )

    /** Runs the cell; the returned job ends once the run is saved (null when it can't run there). */
    fun run(asked: String): Job? {
        val lang = language
        val src = code.text
        // A Python cell importing a library only the server has (PyTorch, XGBoost, transformers, …) runs
        // there straight away, for Run, Run all and the Assistant alike (the web's cell-block run()).
        val serverLibs = if (lang == "python" && asked == "browser" && runsOnServer(lang)) app.benchjot.core.serverOnlyImports(src) else emptyList()
        val where = if (serverLibs.isNotEmpty()) "server" else asked
        val onDevice = where == "browser" && runsOnDevice(lang)
        if (!onDevice && !(where == "server" && runsOnServer(lang))) return null
        output.clear(); hasOutput = true; ranHash = codeHash(src); result = null; fix = null; running = true
        live = null; hideSaved = true
        val buffer = OutputBuffer { output += it }
        if (serverLibs.isNotEmpty()) buffer.add(app.benchjot.core.Stream.Stderr, "This code uses ${serverLibs.take(3).joinToString(", ")}, which ${if (serverLibs.size == 1) "runs" else "run"} on the server, so it's running there.\n")
        // bj_capture writes figures, tables and charts here (device runs) / server runs' are unpacked here.
        val outDir = java.io.File(context.cacheDir, "py-out/${java.util.UUID.randomUUID()}")
        var serverOutputs: LocalOutputs? = null
        val h = if (onDevice) {
            DeviceRunner.run(context, scope, lang, src, onStatus = { status = it }, onOutput = { s, txt -> buffer.add(s, txt) }, outDir = outDir)
        } else {
            ServerRunner.run(scope, lang, src, cell.id, serverEvents(buffer, outDir) { serverOutputs = it })
        }
        handle = h
        return scope.launch { finish(buffer, h.done.await(), onDevice, src, outDir, serverOutputs).join() }
    }

    // The agent's way in: the same edit and run the user's taps make, through the latest composition's functions.
    val runLatest = rememberUpdatedState<(String) -> Job?> { w -> run(w) }
    val resultLatest = rememberUpdatedState(result)
    val editLatest = rememberUpdatedState<(String) -> Unit> { c -> code.replaceAll(c); edited() }
    DisposableEffect(cell.id) {
        onControls(cell.id, object : CellControls {
            override suspend fun setCode(code: String) {
                editLatest.value(code)
                saver.flush()
            }
            override suspend fun run(where: String?): app.benchjot.core.RunOutcome {
                check(handle == null) { "it is already running" }
                val lang = language
                check(runsOnDevice(lang) || runsOnServer(lang)) { if (lang == "text") "plain text doesn't run" else "this language doesn't run" }
                val onServer = (where == "server" && runsOnServer(lang)) || !runsOnDevice(lang)
                runLatest.value(if (onServer) "server" else "browser")?.join()
                return resultLatest.value?.outcome ?: app.benchjot.core.RunOutcome.Stopped
            }
            override fun stop() { handle?.stop?.invoke() }
        })
        onDispose { onControls(cell.id, null) }
    }

    // A background run of this cell still going, or finished while the app was closed: follow it / show it.
    LaunchedEffect(cell.id) {
        val pending = runCatching { Backend.pendingServerRun(cell.id) }.getOrNull() ?: return@LaunchedEffect
        if (handle != null) return@LaunchedEffect
        output.clear(); hasOutput = true; ranHash = codeHash(cell.content); result = null; running = true
        live = null; hideSaved = true
        val buffer = OutputBuffer { output += it }
        val outDir = java.io.File(context.cacheDir, "py-out/${java.util.UUID.randomUUID()}")
        var serverOutputs: LocalOutputs? = null
        val h = ServerRunner.follow(scope, pending, serverEvents(buffer, outDir) { serverOutputs = it })
        handle = h
        finish(buffer, h.done.await(), false, cell.content, outDir, serverOutputs)
    }

    // Ruff's count of auto-fixable issues, 700 ms after the last change (the web's lint delay).
    LaunchedEffect(content, language, touched) {
        if (language != "python" || !touched || !ruff.available) { fixable = 0; return@LaunchedEffect }
        delay(700)
        fixable = runCatching { fixableCount(ruff.check(content)) }.getOrDefault(0)
    }

    fun autofix() {
        val before = code.text
        scope.launch {
            val fixed = runCatching { ruff.fixAll(before) }.getOrNull() ?: return@launch
            if (fixed != before && code.text == before) { code.replaceAll(fixed); edited() }
        }
    }

    fun proposeFix() {
        fixing = true
        val before = code.text
        scope.launch {
            fix = try {
                val a = WebApi.fix(FixRequest(language, before, lastError, outcome?.value))
                Fix(before, a.code.replace("\r\n", "\n"), a.summary)
            } catch (e: Exception) {
                Fix(before, before, Accounts.message(e, "fix"))
            } finally {
                fixing = false
            }
        }
    }

    // At a glance, on the cell's edge: running, how its last run ended, or that the code changed since (as on the web).
    val ended = result?.outcome ?: if (hideSaved) null else cell.lastRun?.outcome
    val edge = when {
        running -> CellEdge.Running
        stale -> CellEdge.Stale
        ended == app.benchjot.core.RunOutcome.Ok -> CellEdge.Ok
        ended == app.benchjot.core.RunOutcome.Error || ended == app.benchjot.core.RunOutcome.Timeout -> CellEdge.Failed
        else -> null
    }
    Column(Modifier.cellCard(edge)) {
        before()
        CellHeader(cell.filename, language = {
            Box {
                TextBtn(languageConfig(language).label, { picking = true }, style = headerMono)
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, containerColor = t.bg) {
                    LANGUAGES.forEach { l ->
                        DropdownMenuItem(text = { Text(l.label, style = headerMono, color = if (l.id == language) t.fg else t.muted) }, onClick = {
                            picking = false
                            language = l.id
                            chosen = true
                            saver.update(CodeValue(language, code.text))
                        })
                    }
                }
            }
        }) {
            if (running) {
                FilledBtn("Stop", { handle?.stop?.invoke() }, variant = ButtonVariant.Neutral, icon = Icons.Stop)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Every account runs on the server; the server picks CPU or GPU and counts GPU runs.
                    if (serverable) {
                        if (runnable) TextBtn("Run on server", { run("server") }, icon = Icons.Server) else FilledBtn("Run on server", { run("server") }, icon = Icons.Server)
                    }
                    if (runnable) FilledBtn("Run", { run("browser") }, icon = Icons.Play)
                }
            }
        }

        FoldedCode(code.text) { CodeEditor(code, language, "Code ${index + 1}", errorLine = if (stale) null else result?.error?.line, onChange = ::edited) }

        if (hasOutput) RunOutput(output, status, running, result, stale, saveError, onClear = ::clearOutput)

        val shownSaved = savedOutputs.takeUnless { hideSaved }
        live?.let { l -> RunOutputs(l.manifest.items, l.manifest.notes, remember(l) { OutputFiles.Live(l) }, onOpen = null) }
            ?: shownSaved?.let { o -> RunOutputs(o.items, o.notes, remember(o) { OutputFiles.Saved(o) }, onOpen = { i -> onOpenOutput(cell.id, i) }) }

        after()

        fix?.let { f ->
            FixDiff(f, onAccept = { code.replaceAll(f.code); fix = null; edited() }, onReject = { fix = null })
        }

        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextBtn(if (copied) "Copied" else "Copy", {
                clipboard.setText(AnnotatedString(code.text)); copied = true
                scope.launch { delay(1500); copied = false }
            })
            TextBtn("Download", { download(downloadName(cell.filename, language, index), code.text) })
            if (lastError.isNotEmpty() && !running && fix == null) {
                if (fixing) Working("Fixing…") else TextBtn("Fix", ::proposeFix)
            }
            if (language == "python" && fixable > 0) TextBtn("Autofix", ::autofix)
            Spacer(Modifier.weight(1f))
            TextBtn("Remove", { if (code.text.isNotBlank()) confirmRemove = true else remove(cell, saver, onBeforeRemove, handle, savedOutputs) })
        }
    }
    if (confirmRemove) ConfirmDialog("Remove this code?") { ok -> confirmRemove = false; if (ok) remove(cell, saver, onBeforeRemove, handle, savedOutputs) }
}

/** Saves the cell's pending code (and, by `onBeforeRemove`, its notes) first, then deletes the row. */
private fun remove(cell: Cell, saver: Autosaver<*>, onBeforeRemove: suspend () -> Unit, handle: RunHandle?, outputs: SavedOutputs?) {
    handle?.stop?.invoke()
    Backend.scope.launch {
        saver.flush()
        onBeforeRemove()
        if (runCatching { Backend.deleteCell(cell.id) }.isSuccess) OutputStore.delete(outputs)
    }
}

private val headerMono = TextStyle(fontFamily = Type.mono, fontSize = 12.sp)

/**
 * A code block's header row: file name and language on the left, actions (Run, Stop, ...) on the right.
 * Shared by the editable block and the read-only preview (NotePreview.kt), as the web's cell-header.tsx.
 */
@Composable
fun CellHeader(filename: String, language: @Composable () -> Unit, actions: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filename.isNotEmpty()) Text(filename, Modifier.weight(1f, fill = false), style = headerMono, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            language()
        }
        actions?.invoke()
    }
}

/** The language as plain text in the header's type (read-only blocks). */
@Composable
fun LanguageLabel(language: String) = Text(languageConfig(language).label, style = headerMono, color = t.muted)

/** Plain monospace output under a code block: stdout, stderr (muted), the error, then the status line. Without `onClear` it is read-only (no Copy or Clear). */
@Composable
fun RunOutput(chunks: List<Chunk>, status: String, running: Boolean, result: RunResult?, stale: Boolean = false, note: String? = null, onClear: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val error = result?.error?.text
    val text = outputText(chunks, error)
    val maxH = (LocalConfiguration.current.screenHeightDp / 2).dp
    Column(Modifier.fillMaxWidth().topLine(t.line).padding(top = 8.dp)) {
        if (chunks.isNotEmpty() || error != null) {
            Text(
                buildAnnotatedString {
                    chunks.forEach { c -> if (c.stream == Stream.Stderr) withStyle(SpanStyle(color = t.muted)) { append(c.text) } else append(c.text) }
                    error?.let { append(it) }
                },
                Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState()), style = Type.output, color = t.fg,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (running) Spinner(size = 14.dp)
            Text(
                runSummary(result, running, status) + if (stale) " · Output from an earlier version" else "",
                Modifier.weight(1f), style = TextStyle(fontFamily = Type.mono, fontSize = 12.sp), color = t.faint,
            )
            if (!running && onClear != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (text.isNotEmpty()) TextBtn(if (copied) "Copied" else "Copy", {
                        clipboard.setText(AnnotatedString(text)); copied = true
                        scope.launch { delay(1500); copied = false }
                    })
                    TextBtn("Clear", onClear)
                }
            }
        }
        note?.let { Text(it, Modifier.padding(bottom = 4.dp), style = TextStyle(fontFamily = Type.mono, fontSize = 12.sp), color = t.muted) }
    }
}

/** The proposed fix as a minimal line diff. Accept replaces the code; Reject drops it. */
@Composable
private fun FixDiff(fix: Fix, onAccept: () -> Unit, onReject: () -> Unit) {
    val hunks = remember(fix) { diffHunks(lineDiff(fix.before, fix.code)) }
    val mono = TextStyle(fontFamily = Type.mono, fontSize = 12.5.sp, lineHeight = 20.sp)
    Column(Modifier.fillMaxWidth().topLine(t.line).padding(top = 8.dp)) {
        if (fix.summary.isNotEmpty()) Text(fix.summary, style = Type.sm, color = t.muted)
        if (hunks.isEmpty()) {
            Text("No change suggested.", Modifier.padding(vertical = 8.dp), style = Type.sm, color = t.muted)
        } else {
            Column(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                hunks.forEach { h ->
                    if (h == null) Text("⋯", style = mono, color = t.faint)
                    else Row(Modifier.then(if (h.kind == DiffKind.Add) Modifier.background(t.tint) else Modifier)) {
                        Text("${h.line}", Modifier.width(36.dp).padding(end = 8.dp), style = mono.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), color = t.faint)
                        Text(when (h.kind) { DiffKind.Same -> " "; DiffKind.Del -> "−"; DiffKind.Add -> "+" }, Modifier.width(16.dp), style = mono, color = t.faint)
                        Text(
                            h.text.ifEmpty { " " },
                            style = if (h.kind == DiffKind.Del) mono.copy(textDecoration = TextDecoration.LineThrough) else mono,
                            color = when (h.kind) { DiffKind.Add -> t.fg; DiffKind.Del -> t.muted; DiffKind.Same -> t.faint },
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (hunks.isNotEmpty()) FilledBtn("Accept fix", onAccept)
            TextBtn(if (hunks.isNotEmpty()) "Reject" else "Close", onReject)
        }
    }
}

