package app.benchjot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import app.benchjot.core.OnDeviceModel
import app.benchjot.core.megabytes
import app.benchjot.core.readCodeAnswer
import app.benchjot.core.timeLeft
import app.benchjot.llm.LocalModel
import app.benchjot.llm.LocalRequest
import app.benchjot.llm.LocalResult
import app.benchjot.llm.ModelState
import app.benchjot.llm.ModelStore
import kotlinx.coroutines.launch

// The on-device helper: one bottom sheet, opened from the note's toolbar. It writes code into
// the note or answers from the note, on the phone. The note is read only when you ask, and code
// it writes goes into the note only when you tap Insert; it is never run for you.

private sealed interface Answer {
    data object None : Answer
    data class Working(val task: AssistTask, val phase: String, val text: String) : Answer
    data class Code(val code: String, val summary: String, val cutShort: Boolean) : Answer
    data class Prose(val text: String, val cutShort: Boolean) : Answer
    data class Error(val error: AssistError, val partial: String) : Answer
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelperSheet(
    noteTitle: String,
    noteText: () -> String,
    language: String,
    onInsertCode: (code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val model by ModelStore.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = t.bg, contentColor = t.fg, scrimColor = t.bg.copy(alpha = 0.6f)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().animateContentSize(tween(200)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Helper", Modifier.weight(1f), style = Type.xl, color = t.fg)
                Text("On your phone", style = Type.xs, color = t.faint)
            }
            AnimatedContent(model is ModelState.Ready, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "helper") { ready ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ready) Ask(noteTitle, noteText, language, onInsertCode) else ModelDownload(model)
                }
            }
            Gap(8.dp)
        }
    }
}

@Composable
private fun Ask(noteTitle: String, noteText: () -> String, language: String, onInsertCode: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var input by remember { mutableStateOf("") }
    var task by remember { mutableStateOf(AssistTask.Write) }
    var answer by remember { mutableStateOf<Answer>(Answer.None) }
    var request by remember { mutableStateOf<LocalRequest?>(null) }
    DisposableEffect(Unit) { onDispose { request?.stop?.invoke() } } // closing the sheet stops the model

    fun send(text: String, kind: AssistTask) {
        val q = text.trim().take(kind.maxInputChars)
        if (q.isEmpty() || answer is Answer.Working) return
        task = kind
        val input = when (kind) {
            AssistTask.Ask -> AssistInput.Ask(noteTitle, noteText().take(AssistTask.Ask.maxInputChars), q) // read now, on this tap only
            else -> AssistInput.Write(language, q)
        }
        answer = Answer.Working(kind, "Thinking…", "")
        val r = LocalModel.generate(context, scope, input,
            onPhase = { p -> (answer as? Answer.Working)?.let { answer = it.copy(phase = p) } },
            onText = { s -> (answer as? Answer.Working)?.let { answer = it.copy(phase = "", text = s) } },
        )
        request = r
        scope.launch {
            answer = when (val res = r.result.await()) {
                is LocalResult.Failed -> Answer.Error(res.error, res.text)
                is LocalResult.Stopped -> if (res.text.isBlank()) Answer.None else Answer.Prose(res.text, cutShort = true)
                is LocalResult.Done -> if (kind == AssistTask.Ask) Answer.Prose(res.text.trim(), res.cutShort) else {
                    val read = readCodeAnswer(res.text)
                    if (read == null) Answer.Error(AssistError.NoCode, res.text) else Answer.Code(read.first.code, read.first.summary, res.cutShort || !read.second)
                }
            }
            if (answer !is Answer.Error) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            request = null
        }
    }

    Segmented(listOf(AssistTask.Write to "Write code", AssistTask.Ask to "Ask this note"), task, { task = it })
    PromptField(input, { input = it }, if (task == AssistTask.Ask) "Ask about this note" else "Describe the code to write") { send(input, task) }
    if (input.isBlank() && answer is Answer.None) {
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
            FilledBtn("Stop", { request?.stop?.invoke() }, variant = ButtonVariant.Neutral)
        }
        is Answer.Code -> {
            CodeCard(a.code, language)
            if (a.summary.isNotEmpty()) Muted(a.summary)
            if (a.cutShort) Muted("The answer was cut short. Check the end before you run it.", color = t.faint)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledBtn("Insert into note", { onInsertCode(a.code); answer = Answer.None; input = "" })
                TextBtn("Copy", { clipboard.setText(AnnotatedString(a.code)) })
                TextBtn("Clear", { answer = Answer.None })
            }
        }
        is Answer.Prose -> {
            AnswerText(a.text, mono = false)
            if (a.cutShort) Muted("Stopped before the end.", color = t.faint)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextBtn("Copy", { clipboard.setText(AnnotatedString(a.text)) })
                TextBtn("Clear", { answer = Answer.None })
            }
        }
        is Answer.Error -> ErrorRow(a.error, onRetry = { answer = Answer.None; send(input, task) })
    }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, placeholder: String, onSend: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(AssistTask.Ask.maxInputChars)) },
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
