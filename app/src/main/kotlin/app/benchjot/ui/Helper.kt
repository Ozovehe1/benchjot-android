package app.benchjot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.benchjot.core.ASSIST_SUGGESTIONS
import app.benchjot.core.AssistAction
import app.benchjot.core.AssistError
import app.benchjot.core.AssistInput
import app.benchjot.core.AssistTask
import app.benchjot.core.HelperAnswer
import app.benchjot.core.HelperCell
import app.benchjot.core.HelperHistoryLimits
import app.benchjot.core.HelperLimits
import app.benchjot.core.HelperPlace
import app.benchjot.core.HelperReply
import app.benchjot.core.FriendlyException
import app.benchjot.core.HistoryTurn
import app.benchjot.core.SavedHistory
import app.benchjot.core.SavedTurn
import app.benchjot.core.checkedCode
import app.benchjot.core.helperHistory
import app.benchjot.core.helperRequest
import app.benchjot.core.isLanguageId
import app.benchjot.core.reply
import app.benchjot.core.taskId
import app.benchjot.core.noteContext
import app.benchjot.core.readHelperReply
import app.benchjot.core.OnDeviceModel
import app.benchjot.core.megabytes
import app.benchjot.core.readCodeAnswer
import app.benchjot.core.timeLeft
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.HelperCache
import app.benchjot.data.WebApi
import app.benchjot.llm.LocalModel
import app.benchjot.llm.LocalResult
import app.benchjot.llm.ModelState
import app.benchjot.llm.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// The note's helper: one bottom sheet, opened from the note's toolbar. It writes code for the note or
// answers from the note, where the user picked: on the phone (the on-device model) or online (the
// web's /api/helper, Groq, sharing Fix's limit). Only the user switches between them. The note is
// read only when you ask, and code goes into the note only when you tap Insert; it is never run for you.
// Each answered turn is saved with the note (Supabase, synced with the web) and shown again when the
// Helper reopens; the phone keeps a copy of the last read (HelperCache). Older turns are condensed by
// the database into the note's summary, shown first. Clear deletes them.

/** The request in flight, or how it failed. Answers become turns ([ShownTurn]). */
private sealed interface Answer {
    data object None : Answer
    data class Working(val task: AssistTask, val phase: String, val text: String) : Answer
    data class Error(val error: AssistError, val partial: String) : Answer
    /** A failure in plain words: the shared error table (online), or a refused answer (wrong language). */
    data class Failed(val message: String) : Answer
}

/** An answered turn as shown. [saved] once in helper_turns; a stopped answer ([save] false) stays on this phone only. */
private data class ShownTurn(val key: Long, val task: String, val request: String, val reply: HelperReply, val cutShort: Boolean = false, val saved: SavedTurn? = null, val save: Boolean = true)

/**
 * The note's helper history while the sheet is open: the copy on the phone first, then Supabase's.
 * Saving and clearing go to Supabase, then to the copy. Failures are said in [notice], never thrown.
 */
private class HistoryState(private val context: android.content.Context, private val entryId: String, private val scope: kotlinx.coroutines.CoroutineScope) {
    private var nextKey = 1L
    var summary by mutableStateOf("")
    val turns = mutableStateListOf<ShownTurn>()
    var notice by mutableStateOf<String?>(null)

    private fun show(h: SavedHistory) {
        summary = h.summary
        val unsaved = turns.filter { it.saved == null }
        turns.clear()
        turns.addAll(h.turns.map { ShownTurn(nextKey++, it.task, it.request, it.answer, saved = it) })
        turns.addAll(unsaved)
    }

    private fun cache() = HelperCache.write(context, entryId, SavedHistory(summary, turns.mapNotNull { it.saved }))

    fun load() {
        HelperCache.read(context, entryId)?.let(::show)
        scope.launch {
            runCatching { Backend.helperHistory(entryId) }
                .onSuccess { show(it); cache() }
                .onFailure { notice = Accounts.message(it, "history_load") }
        }
    }

    /** The answered turns, oldest first, as the online request sends them back. */
    fun answered(): List<HistoryTurn> = turns.map { HistoryTurn(it.task, it.request, it.reply) }

    fun add(task: String, request: String, reply: HelperReply, cutShort: Boolean, save: Boolean) {
        val turn = ShownTurn(nextKey++, task, request, reply, cutShort, save = save)
        turns.add(turn)
        if (!save) return
        scope.launch {
            runCatching { Backend.saveHelperTurn(entryId, task, request, reply) }
                .onSuccess { row -> turns.indexOfFirst { it.key == turn.key }.takeIf { it >= 0 }?.let { turns[it] = turn.copy(saved = row) }; cache() }
                .onFailure { notice = Accounts.message(it, "history_save") }
        }
    }

    fun clear(turn: ShownTurn) {
        notice = null
        val row = turn.saved ?: run { turns.removeAll { it.key == turn.key }; return }
        scope.launch {
            runCatching { Backend.deleteHelperTurn(row.id) }
                .onSuccess { turns.removeAll { it.key == turn.key }; cache() }
                .onFailure { notice = Accounts.message(it, "history_clear") }
        }
    }

    fun clearSummary() {
        notice = null
        scope.launch {
            runCatching { Backend.deleteHelperSummary(entryId) }
                .onSuccess { summary = ""; cache() }
                .onFailure { notice = Accounts.message(it, "history_clear") }
        }
    }
}

private const val PLACE_PREFS = "helper"
private const val KEY_PLACE = "place"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelperSheet(
    entryId: String,
    noteTitle: String,
    noteBody: () -> String,
    cells: () -> List<HelperCell>,
    language: String,
    onInsertCode: (code: String, language: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Saves run in the app's scope, so closing the sheet right after an answer still saves it.
    val history = remember(entryId) { HistoryState(context, entryId, Backend.scope) }
    LaunchedEffect(history) { history.load() } // each open reads it again (it may have changed on the web)
    val prefs = remember { context.getSharedPreferences(PLACE_PREFS, android.content.Context.MODE_PRIVATE) }
    var place by remember { mutableStateOf(HelperPlace.of(prefs.getString(KEY_PLACE, null))) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val model by ModelStore.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = t.bg, contentColor = t.fg, scrimColor = t.bg.copy(alpha = 0.6f)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().animateContentSize(tween(200)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Helper", style = Type.xl, color = t.fg)
            Segmented(HelperPlace.entries.map { it to it.label }, place, {
                place = it
                prefs.edit().putString(KEY_PLACE, it.id).apply()
            }, name = { "Run the helper: $it" })
            val ready = place == HelperPlace.Online || model is ModelState.Ready
            AnimatedContent(place to ready, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "helper") { (where, canAsk) ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (where == HelperPlace.Online) Muted("Sends this note to the server when you ask. Shares Fix’s daily limit.")
                    if (canAsk) Ask(where, history, noteTitle, noteBody, cells, language, onInsertCode) else ModelDownload(model)
                }
            }
            Gap(8.dp)
        }
    }
}

@Composable
private fun Ask(place: HelperPlace, history: HistoryState, noteTitle: String, noteBody: () -> String, cells: () -> List<HelperCell>, language: String, onInsertCode: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var input by remember { mutableStateOf("") }
    var task by remember { mutableStateOf(AssistTask.Write) }
    var answer by remember { mutableStateOf<Answer>(Answer.None) }
    var stop by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { stop?.invoke() } } // closing the sheet stops the request
    val maxInput = if (place == HelperPlace.Online) HelperLimits.MAX_REQUEST_CHARS else AssistTask.Ask.maxInputChars

    fun settle(a: Answer) {
        answer = a
        stop = null
    }

    /** An answer: shown as a turn, and saved unless it was stopped part-way. */
    fun answered(kind: AssistTask, q: String, a: HelperAnswer, cutShort: Boolean = false, save: Boolean = true) {
        history.add(taskId(kind), q, a.reply(language), cutShort, save)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        settle(Answer.None)
    }

    /** Code from the phone's model, checked like the route's (a Python cell never gets JavaScript). */
    fun phoneCode(kind: AssistTask, q: String, text: String, cutShort: Boolean) {
        val read = readCodeAnswer(text) ?: return settle(Answer.Error(AssistError.NoCode, text))
        try {
            answered(kind, q, checkedCode(language, read.first.code, read.first.summary), cutShort || !read.second)
        } catch (e: FriendlyException) {
            settle(Answer.Failed(e.message.orEmpty()))
        }
    }

    fun onPhone(q: String, kind: AssistTask) {
        val input = when (kind) {
            // Read now, on this tap only. The phone gets the note's summary, not its turns (its context is small).
            AssistTask.Ask -> AssistInput.Ask(noteTitle, noteContext(noteBody(), cells(), AssistTask.Ask.maxInputChars).first, q, language, history.summary)
            else -> AssistInput.Write(language, q)
        }
        val r = LocalModel.generate(context, scope, input,
            onPhase = { p -> (answer as? Answer.Working)?.let { answer = it.copy(phase = p) } },
            onText = { s -> (answer as? Answer.Working)?.let { answer = it.copy(phase = "", text = s) } },
        )
        stop = r.stop
        scope.launch {
            when (val res = r.result.await()) {
                is LocalResult.Failed -> settle(Answer.Error(res.error, res.text))
                // A stopped answer is shown, marked, and kept off the saved history.
                is LocalResult.Stopped -> if (res.text.isBlank()) settle(Answer.None) else answered(kind, q, HelperAnswer.Prose(res.text.trim()), cutShort = true, save = false)
                is LocalResult.Done -> when {
                    // Ask answers in words, or with a fenced program when asked for a change.
                    kind == AssistTask.Ask && readCodeAnswer(res.text) == null ->
                        if (res.text.isBlank()) settle(Answer.Error(AssistError.NoCode, res.text)) else answered(kind, q, HelperAnswer.Prose(res.text.trim()), res.cutShort)
                    else -> phoneCode(kind, q, res.text, res.cutShort)
                }
            }
        }
    }

    fun online(q: String, kind: AssistTask) {
        // Read now, on this tap only; the note's summary and last turns go with it.
        val request = helperRequest(kind, q, language, noteTitle, noteBody(), cells(), helperHistory(history.summary, history.answered()))
        val job = scope.launch {
            try {
                answered(kind, q, readHelperReply(kind, language, WebApi.helper(request)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settle(Answer.Failed(Accounts.message(e, "helper")))
            }
        }
        stop = { job.cancel(); answer = Answer.None; stop = null } // a stopped request's late answer is dropped
    }

    fun send(text: String, kind: AssistTask) {
        val q = text.trim().take(if (place == HelperPlace.Online) HelperLimits.MAX_REQUEST_CHARS else kind.maxInputChars)
        if (q.isEmpty() || answer is Answer.Working) return
        task = kind
        answer = Answer.Working(kind, "Thinking…", "")
        if (place == HelperPlace.Online) online(q, kind) else onPhone(q, kind)
    }

    Segmented(listOf(AssistTask.Write to "Write code", AssistTask.Ask to "Ask this note"), task, { task = it })
    HistoryList(history, language, onInsertCode, onCopy = { clipboard.setText(AnnotatedString(it)) })
    PromptField(input, { input = it.take(maxInput) }, if (task == AssistTask.Ask) "Ask about this note" else "Describe the code to write") { send(input, task) }
    if (input.isBlank() && answer is Answer.None && history.turns.isEmpty() && history.summary.isEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ASSIST_SUGGESTIONS.forEach { (label, kind) -> Chip(label) { input = label; send(label, kind) } }
        }
    }

    when (val a = answer) {
        Answer.None -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { FilledBtn("Send", { send(input, task) }, enabled = input.isNotBlank()) }
        is Answer.Working -> {
            if (a.text.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.muted, trackColor = t.line)
                Muted(a.phase.ifEmpty { "Thinking…" }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                AnswerText(a.text, mono = a.task != AssistTask.Ask)
            }
            FilledBtn("Stop", { stop?.invoke() }, variant = ButtonVariant.Neutral)
        }
        is Answer.Error -> ErrorRow(a.error, onRetry = { answer = Answer.None; send(input, task) })
        is Answer.Failed -> {
            Muted(a.message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = t.fg)
            FilledBtn(AssistAction.Retry.label, { answer = Answer.None; send(input, task) })
        }
    }
    history.notice?.let { Muted(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = t.fg) }
    Muted("Turns are saved with this note for ${HelperHistoryLimits.DAYS} days, then kept as a short summary. Clear deletes them.", color = t.faint)
}

/** The summary and the answered turns, oldest first, newest in view; each with its own Insert, Copy and Clear. */
@Composable
private fun HistoryList(history: HistoryState, language: String, onInsertCode: (String, String) -> Unit, onCopy: (String) -> Unit) {
    if (history.summary.isEmpty() && history.turns.isEmpty()) return
    val scroll = rememberScrollState()
    LaunchedEffect(history.turns.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (history.summary.isNotEmpty()) {
            Text("Earlier, condensed after ${HelperHistoryLimits.DAYS} days", style = Type.xs, color = t.faint)
            Muted(history.summary)
            TextBtn("Clear", { history.clearSummary() })
        }
        history.turns.forEach { turn ->
            Text(
                turn.request,
                Modifier.align(Alignment.End).background(t.tint, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                style = Type.sm, color = t.fg,
            )
            val r = turn.reply
            if (r.kind == "code") {
                val lang = r.language.takeIf { isLanguageId(it) } ?: language
                CodeCard(r.code, lang)
                if (r.summary.isNotEmpty()) Muted(r.summary)
                if (turn.cutShort) Muted("The answer was cut short. Check the end before you run it.", color = t.faint)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledBtn("Insert into note", { onInsertCode(r.code, lang) })
                    TextBtn("Copy", { onCopy(r.code) })
                    TextBtn("Clear", { history.clear(turn) })
                }
            } else {
                AnswerText(r.text, mono = false)
                if (turn.cutShort) Muted("Stopped before the end.", color = t.faint)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextBtn("Copy", { onCopy(r.text) })
                    TextBtn("Clear", { history.clear(turn) })
                }
            }
        }
    }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, placeholder: String, onSend: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = Type.body.copy(color = t.fg),
        cursorBrush = SolidColor(t.fg),
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).bottomLine(t.line).padding(vertical = 12.dp).semantics { contentDescription = placeholder },
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, style = Type.body, color = t.faint); inner() },
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.heightIn(min = 48.dp).border(1.dp, t.line, RoundedCornerShape(24.dp)).plainClick(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        style = Type.sm, color = t.fg,
    )
}

@Composable
private fun AnswerText(text: String, mono: Boolean) {
    Text(
        text,
        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState(), reverseScrolling = true).semantics { liveRegion = LiveRegionMode.Polite },
        style = if (mono) Type.code else Type.body, color = t.fg,
    )
}

@Composable
private fun CodeCard(code: String, language: String) {
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).border(1.dp, t.line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
        StaticCode(code, language, "Code the helper wrote")
    }
}

@Composable
private fun ErrorRow(error: AssistError, onRetry: () -> Unit) {
    val context = LocalContext.current
    Muted(error.text, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = t.fg)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        when (error.action) {
            // Writing and asking have no server version, so "Use server" here is a retry; Fix falls back by itself.
            AssistAction.Retry, AssistAction.UseServer -> FilledBtn(AssistAction.Retry.label, onRetry)
            AssistAction.Download -> FilledBtn(error.action.label, { ModelStore.download(allowMobileData = ModelStore.onUnmeteredNetwork()) })
            AssistAction.FreeSpace -> FilledBtn(error.action.label, {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            })
        }
    }
}

/** Getting the model: why, how big, and where the download is. Never blocks the app. */
@Composable
fun ModelDownload(state: ModelState) {
    val wifi = remember { ModelStore.onUnmeteredNetwork() }
    when (state) {
        ModelState.Missing -> {
            Text("Download once (${megabytes(OnDeviceModel.SIZE_BYTES)}), and Fix works offline and unlimited, forever.", style = Type.body, color = t.fg)
            Muted("The helper runs ${OnDeviceModel.NAME} on your phone. Your notes and code stay on it.")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledBtn("Download now", { ModelStore.download(allowMobileData = true) })
                if (!wifi) TextBtn("Wait for Wi-Fi", { ModelStore.download(allowMobileData = false) })
            }
            if (!wifi) Muted("You’re on mobile data.", color = t.faint)
        }
        is ModelState.Waiting -> {
            Progress(state.done, OnDeviceModel.SIZE_BYTES, if (state.forWifi) "Waiting for Wi-Fi" else "Waiting for a connection")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (state.forWifi) TextBtn("Use mobile data", { ModelStore.download(allowMobileData = true) })
                TextBtn("Cancel", { ModelStore.delete() })
            }
        }
        is ModelState.Downloading -> {
            Progress(state.done, state.total, timeLeft(state.total - state.done, state.bytesPerSecond) ?: "Starting…")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { TextBtn("Cancel", { ModelStore.delete() }) }
            Muted("You can keep working. Fix uses the server until this finishes.", color = t.faint)
        }
        ModelState.Verifying -> {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.muted, trackColor = t.line)
            Muted("Checking the file…")
        }
        is ModelState.Failed -> ErrorRow(state.error, onRetry = { ModelStore.download(allowMobileData = wifi) })
        is ModelState.Ready -> Muted("Ready.")
    }
}

@Composable
private fun Progress(done: Long, total: Long, note: String) {
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = t.fg, trackColor = t.line)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text("${megabytes(done)} of ${megabytes(total)}", Modifier.weight(1f), style = Type.xs, color = t.muted)
        Text(note, style = Type.xs, color = t.muted)
    }
}

/**
 * The first time a signed-in user opens the app: on Wi-Fi the model download starts by itself
 * (Android's download notification shows it); on mobile data, one sheet asks first. Asked once,
 * either way. Never on an emulator, so CI runs don't pull 429 MB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFirstRun() {
    var ask by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (ModelStore.asked || ModelStore.state.value !is ModelState.Missing || isEmulator()) return@LaunchedEffect
        if (ModelStore.onUnmeteredNetwork()) ModelStore.download(allowMobileData = false) else ask = true
    }
    if (!ask) return
    val model by ModelStore.state.collectAsState()
    ModalBottomSheet(onDismissRequest = { ModelStore.asked = true; ask = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.bg, contentColor = t.fg, scrimColor = t.bg.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fix on your phone", style = Type.xl, color = t.fg)
            ModelDownload(model)
            if (model !is ModelState.Missing) TextBtn("Done", { ModelStore.asked = true; ask = false })
            Gap(8.dp)
        }
    }
}

private fun isEmulator(): Boolean = android.os.Build.HARDWARE.let { it == "ranchu" || it == "goldfish" } || android.os.Build.FINGERPRINT.contains("generic")
