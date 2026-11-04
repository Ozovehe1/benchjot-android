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
import app.benchjot.core.RunError
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.SaveRegistry
import app.benchjot.core.Snippet
import app.benchjot.core.Stream
import app.benchjot.core.codeHash
import app.benchjot.core.detectLanguage
import app.benchjot.core.diffHunks
import app.benchjot.core.downloadName
import app.benchjot.core.fromLastRun
import app.benchjot.core.isLanguageId
import app.benchjot.core.languageConfig
import app.benchjot.core.lineDiff
import app.benchjot.core.outputText
import app.benchjot.core.runSummary
import app.benchjot.core.runsOnDevice
import app.benchjot.core.runsOnServer
import app.benchjot.core.toLastRun
import app.benchjot.core.toRunResult
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.WebApi
import app.benchjot.run.DeviceRunner
import app.benchjot.run.OutputBuffer
import app.benchjot.run.RunHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// One code block, as the web's snippet-block.tsx: language, Run (on the device,
// free) / Run on server (Pro), the editor, its output, Fix, Copy, Download, Remove.

private data class CodeValue(val language: String, val content: String)

private data class Fix(val before: String, val code: String, val summary: String)

@Composable
fun SnippetBlock(snippet: Snippet, index: Int, outcome: Outcome?, pro: Boolean, registry: SaveRegistry) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val download = rememberDownloader()
    val initial = if (isLanguageId(snippet.language)) snippet.language else "text"
    var language by remember(snippet.id) { mutableStateOf(initial) }
    val code = remember(snippet.id) { CodeState(snippet.content) }
    // A language counts as chosen once the user picks it, or once the block was saved with code in it.
    var chosen by remember(snippet.id) { mutableStateOf(initial != "text" || snippet.content.isNotBlank()) }
    var copied by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val saved = remember(snippet.id) { snippet.lastRun?.let(::fromLastRun) }
    val output = remember(snippet.id) { mutableStateListOf<Chunk>().apply { saved?.first?.let(::addAll) } }
    var hasOutput by remember(snippet.id) { mutableStateOf(saved != null) }
    var result by remember(snippet.id) { mutableStateOf(saved?.second) }
    var ranHash by remember(snippet.id) { mutableStateOf(snippet.lastRun?.codeHash) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    var fixing by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<RunHandle?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    val saver = remember(snippet.id) {
        Autosaver(Backend.scope, CodeValue(initial, snippet.content), registry) { v -> Backend.updateSnippetCode(snippet.id, v.language, v.content) }
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
            try { Backend.saveLastRun(snippet.id, run) } catch (e: Exception) { saveError = "Output not saved: ${Accounts.message(e)}" }
        }
    }

    fun edited() {
        if (!chosen) language = detectLanguage(code.text) ?: "text"
        saver.update(CodeValue(language, code.text))
    }

    fun clearOutput() {
        output.clear(); hasOutput = false; result = null; fix = null; ranHash = null
        persist(null)
    }

    fun run(where: String) {
        val lang = language
        val onDevice = where == "browser" && runsOnDevice(lang)
        if (!onDevice && !(where == "server" && serverable && pro)) return
        val src = code.text
        output.clear(); hasOutput = true; ranHash = codeHash(src); result = null; fix = null; running = true
        val buffer = OutputBuffer { output += it }
        val h = if (onDevice) {
            DeviceRunner.run(context, scope, lang, src, onStatus = { status = it }, onOutput = { s, txt -> buffer.add(s, txt) })
        } else {
            status = "Running on server…"
            val started = System.currentTimeMillis()
            val job = scope.async(start = CoroutineStart.LAZY) {
                try {
                    val (chunks, r) = toRunResult(WebApi.runOnServer(lang, src))
                    chunks.forEach { buffer.add(it.stream, it.text) }
                    r
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RunResult(RunOutcome.Error, RunError(Accounts.message(e), null), System.currentTimeMillis() - started)
                }
            }
            val stopped = CompletableDeferred<RunResult>()
            val done = scope.async {
                job.start()
                kotlinx.coroutines.selects.select<RunResult> {
                    job.onAwait { it }
                    stopped.onAwait { job.cancel(); it }
                }
            }
            RunHandle(done) { stopped.complete(RunResult(RunOutcome.Stopped, null, System.currentTimeMillis() - started)) }
        }
        handle = h
        scope.launch {
            val r = h.done.await()
            handle = null
            status = ""
            result = r
            running = false
            persist(toLastRun(buffer.chunks, r, if (onDevice) "browser" else "server", src))
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
                Fix(before, before, Accounts.message(e))
            } finally {
                fixing = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().topLine(t.line)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val mono = TextStyle(fontFamily = Type.mono, fontSize = 12.sp)
                if (snippet.filename.isNotEmpty()) Text(snippet.filename, Modifier.weight(1f, fill = false), style = mono, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    TextBtn(languageConfig(language).label, { picking = true }, style = mono)
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, containerColor = t.bg) {
                        LANGUAGES.forEach { l ->
                            DropdownMenuItem(text = { Text(l.label, style = mono, color = if (l.id == language) t.fg else t.muted) }, onClick = {
                                picking = false
                                language = l.id
                                chosen = true
                                saver.update(CodeValue(language, code.text))
                            })
                        }
                    }
                }
            }
            if (running) {
                FilledBtn("Stop", { handle?.stop?.invoke() }, variant = ButtonVariant.Neutral)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (serverable && pro) TextBtn("Run on server", { run("server") }, strong = !runnable)
                    if (serverable && !pro && !runnable) Text("Server runs · Pro", style = Type.xs, color = t.faint)
                    if (runnable) FilledBtn("Run", { run("browser") })
                }
            }
        }

        CodeEditor(code, language, "Code ${index + 1}", errorLine = if (stale) null else result?.error?.line, onChange = ::edited)

        if (hasOutput) RunOutput(output, status, running, result, stale, saveError, onClear = ::clearOutput)

        fix?.let { f ->
            FixDiff(f, onAccept = { code.replaceAll(f.code); fix = null; edited() }, onReject = { fix = null })
        }

        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextBtn(if (copied) "Copied" else "Copy", {
                clipboard.setText(AnnotatedString(code.text)); copied = true
                scope.launch { delay(1500); copied = false }
            })
            TextBtn("Download", { download(downloadName(snippet.filename, language, index), code.text) })
            if (lastError.isNotEmpty() && !running && fix == null) TextBtn(if (fixing) "Fixing…" else "Fix", ::proposeFix, enabled = !fixing)
            Spacer(Modifier.weight(1f))
            TextBtn("Remove", { if (code.text.isNotBlank()) confirmRemove = true else remove(snippet, saver, handle) })
        }
    }
    if (confirmRemove) ConfirmDialog("Remove this code?") { ok -> confirmRemove = false; if (ok) remove(snippet, saver, handle) }
}

private fun remove(snippet: Snippet, saver: Autosaver<*>, handle: RunHandle?) {
    handle?.stop?.invoke()
    Backend.scope.launch {
        saver.flush()
        runCatching { Backend.deleteSnippet(snippet.id) }
    }
}

/** Plain monospace output under a code block: stdout, stderr (muted), the error, then the status line. */
@Composable
private fun RunOutput(chunks: List<Chunk>, status: String, running: Boolean, result: RunResult?, stale: Boolean, note: String?, onClear: () -> Unit) {
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
            Text(
                runSummary(result, running, status) + if (stale) " · Output from an earlier version" else "",
                Modifier.weight(1f), style = TextStyle(fontFamily = Type.mono, fontSize = 12.sp), color = t.faint,
            )
            if (!running) {
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

