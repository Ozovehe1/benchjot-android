package app.benchjot.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.isImeVisible
import app.benchjot.core.AgentState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.benchjot.core.ASSIST_SUGGESTIONS
import app.benchjot.core.AssistantHistoryLimits
import app.benchjot.core.AssistantLimits
import app.benchjot.core.shown
import app.benchjot.core.VoiceLimits
import app.benchjot.core.AssistantReply
import app.benchjot.core.HistoryTurn
import app.benchjot.core.SavedHistory
import app.benchjot.core.SavedTurn
import app.benchjot.core.assistantHistory
import app.benchjot.core.isLanguageId
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.AssistantCache
import kotlinx.coroutines.launch

// The note's assistant: one bottom sheet, opened from the note's toolbar. It writes code for the note or
// answers from the note, on Claude (through the web's /api/agent).
// The note is read only when you ask, and code goes into the note only when you tap Insert or ask for it.
// Each answered turn is saved with the note (Supabase, synced with the web) and shown again when the
// Assistant reopens; the phone keeps a copy of the last read (AssistantCache). Older turns are condensed by
// the database into the note's summary, shown first. Clear deletes them.
//
// Online, one Send button (or the microphone: the phone's own speech recognition, voice/Speech.kt)
// for everything: the model answers, or, when the request is work to do in the note, hands it to
// the agent, which adds, changes and runs code cells until it is done (Agent.kt). Stop ends it;
// Undo puts the code back.

/** A saved turn as shown ([saved]: its row in assistant_turns). */
private data class ShownTurn(val key: Long, val task: String, val request: String, val reply: AssistantReply, val saved: SavedTurn)

/**
 * The note's assistant history while the sheet is open: the copy on the phone first, then Supabase's.
 * Saving and clearing go to Supabase, then to the copy. Failures are said in [notice], never thrown.
 */
private class HistoryState(private val context: android.content.Context, private val noteId: String, private val scope: kotlinx.coroutines.CoroutineScope) {
    private var nextKey = 1L
    var summary by mutableStateOf("")
    val turns = mutableStateListOf<ShownTurn>()
    var notice by mutableStateOf<String?>(null)

    private fun show(h: SavedHistory) {
        summary = h.summary
        turns.clear()
        turns.addAll(h.turns.map { ShownTurn(nextKey++, it.task, it.request, it.answer, saved = it) })
    }

    private fun cache() = AssistantCache.write(context, noteId, SavedHistory(summary, turns.map { it.saved }))

    fun load() {
        AssistantCache.read(context, noteId)?.let(::show)
        scope.launch {
            runCatching { Backend.assistantHistory(noteId) }
                .onSuccess { show(it); cache() }
                .onFailure { notice = Accounts.message(it, "history_load") }
        }
    }

    /** The answered turns, oldest first, as the online request sends them back (one the user undid says so). */
    fun answered(): List<HistoryTurn> = turns.map {
        val r = it.reply
        HistoryTurn(it.task, it.request, if (r.undone == true) r.copy(text = r.text + "\n\nThe user undid everything this changed.") else r)
    }

    /** The cells the assistant wrote in kept requests and hasn't undone (it's told which cells are its own). */
    val ownCells get() = turns.filter { it.reply.undone != true }.flatMap { it.reply.changes?.added.orEmpty() }.toSet()

    /** Undo states of kept requests, by turn key: "working" or "failed" (undone ones say so in their reply). */
    val undoing = androidx.compose.runtime.mutableStateMapOf<Long, String>()

    /** Undo for a request kept from before (after the app was closed): from what was kept with its turn; then it's kept as undone. */
    fun undoKept(turn: ShownTurn, note: app.benchjot.core.AgentNote) {
        val row = turn.saved
        val changes = turn.reply.changes ?: return
        undoing[turn.key] = "working"
        scope.launch {
            val ok = runCatching { app.benchjot.core.undoAgent(note, app.benchjot.core.undoFromKept(changes)) }.isSuccess
            if (!ok) { undoing[turn.key] = "failed"; return@launch }
            undoing.remove(turn.key)
            val reply = turn.reply.copy(undone = true)
            val i = turns.indexOfFirst { it.key == turn.key }
            if (i >= 0) turns[i] = turn.copy(reply = reply)
            runCatching { Backend.resaveAssistantTurn(noteId, row, reply) }
                .onSuccess { saved -> turns.indexOfFirst { it.key == turn.key }.takeIf { it >= 0 }?.let { turns[it] = turns[it].copy(saved = saved) }; cache() }
                .onFailure { notice = Accounts.message(it, "history_save") }
        }
    }

    fun clear(turn: ShownTurn) {
        notice = null
        val row = turn.saved
        scope.launch {
            runCatching { Backend.deleteAssistantTurn(row.id) }
                .onSuccess { turns.removeAll { it.key == turn.key }; cache() }
                .onFailure { notice = Accounts.message(it, "history_clear") }
        }
    }

    fun clearSummary() {
        notice = null
        scope.launch {
            runCatching { Backend.deleteAssistantSummary(noteId) }
                .onSuccess { summary = ""; cache() }
                .onFailure { notice = Accounts.message(it, "history_clear") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(
    noteId: String,
    language: String,
    onInsertCode: (code: String, language: String) -> Unit,
    onDismiss: () -> Unit,
    /** The note's agent, which carries out every request. */
    agent: AgentSession,
    /** Where it sits: the bottom of the note screen. */
    modifier: Modifier = Modifier,
    /** Its height as it changes, so the note can leave room for it at the end. */
    onHeight: (androidx.compose.ui.unit.Dp) -> Unit = {},
) {
    val context = LocalContext.current
    // Saves run in the app's scope, so closing the sheet right after an answer still saves it.
    val history = remember(noteId) { HistoryState(context, noteId, Backend.scope) }
    LaunchedEffect(history) { history.load() } // each open reads it again (it may have changed on the web)
    // Its height, as a share of the screen: dragged by the handle, it settles at half or full, and
    // closes when dragged low (the web's useSheetSize). The keyboard opening takes it to full.
    // Not a modal: below full height the note above it stays in reach (scroll it, type, run cells).
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { screenDp.dp.toPx() }
    var size by androidx.compose.runtime.saveable.rememberSaveable(noteId) { androidx.compose.runtime.mutableFloatStateOf(SheetSize.HALF) }
    val keyboard = keyboardOpen()
    LaunchedEffect(keyboard) { if (keyboard) size = SheetSize.FULL }
    LaunchedEffect(size) { onHeight((screenDp * size).dp) }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val top = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        modifier.fillMaxWidth().height((screenDp * size).dp)
            .shadow(24.dp, top).clip(top).background(t.raise)
            // Touches on the panel stay on it (they never reach the note underneath).
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        ResizeHandle(
            onDrag = { dy -> size = (size - dy / screenPx).coerceIn(SheetSize.MIN, SheetSize.FULL) },
            onDragEnd = {
                if (size < SheetSize.CLOSE_BELOW) onDismiss()
                else size = if (kotlin.math.abs(size - SheetSize.HALF) < kotlin.math.abs(size - SheetSize.FULL)) SheetSize.HALF else SheetSize.FULL
            },
            onTap = { size = if (size > (SheetSize.HALF + SheetSize.FULL) / 2) SheetSize.HALF else SheetSize.FULL },
        )
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Close at the top left, then the name and what it does.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).plainClick(onClick = onDismiss).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    BjIcon(Icons.X, tint = t.muted)
                }
                Column(Modifier.weight(1f)) {
                    Text("Assistant", style = Type.xl, color = t.fg)
                    Text(
                        "Answers about this note and does the work in it",
                        style = Type.xs, color = t.faint, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Ask(history, language, onInsertCode, agent)
            Gap(8.dp)
        }
    }
}

/** Whether the on-screen keyboard is showing. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun keyboardOpen(): Boolean = androidx.compose.foundation.layout.WindowInsets.isImeVisible

/** The sheet's heights as a share of the screen (the web's HALF, FULL and CLOSE_BELOW). */
private object SheetSize {
    const val HALF = 0.55f
    const val FULL = 0.94f
    const val CLOSE_BELOW = 0.35f
    const val MIN = 0.15f
}

/** The bar at the top of the sheet: drag to resize, tap to switch between half and full height. */
@Composable
private fun ResizeHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onTap: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(32.dp)
            .draggable(androidx.compose.foundation.gestures.rememberDraggableState(onDrag), androidx.compose.foundation.gestures.Orientation.Vertical, onDragStopped = { onDragEnd() })
            .plainClick(onClick = onTap)
            .semantics { contentDescription = "Resize the Assistant: drag, or tap for half or full height" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.faint))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Ask(history: HistoryState, language: String, onInsertCode: (String, String) -> Unit, agent: AgentSession) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var heardLive by remember { mutableStateOf<String?>(null) } // the words being heard, while the microphone is on

    // Everything goes to the agent: it answers, or does the work in the note, with the conversation so far.
    fun send(text: String, voice: Boolean = false) {
        val q = text.trim().take(AssistantLimits.MAX_REQUEST_CHARS)
        if (q.isEmpty() || agent.working) return
        input = ""
        agent.start(q, voice, language, assistantHistory(history.summary, history.answered()), history.ownCells)
    }

    val agentTurns = agent.items.mapNotNull { it.savedId }.toSet()
    // The conversation scrolls in whatever height the sheet has; the newest stays in view.
    val thread = rememberScrollState()
    val latest = agent.items.lastOrNull()?.state
    LaunchedEffect(history.turns.size, agent.items.size, latest?.steps?.size, latest is AgentState.Working) { thread.animateScrollTo(thread.maxValue) }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(thread), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted("The Assistant reads this note, and your other notes when a request needs them.")
        HistoryList(history, language, onInsertCode, onCopy = { clipboard.setText(AnnotatedString(it)) }, hide = agentTurns, agent = agent)
        agent.items.forEach { androidx.compose.runtime.key(it.key) { AgentItemView(it, agent) } }
        if (input.isBlank() && history.turns.isEmpty() && history.summary.isEmpty() && agent.items.isEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ASSIST_SUGGESTIONS.forEach { label -> Chip(label) { input = label; send(label) } }
            }
        }
        Muted("Ask about the note, or ask for work to be done, typed or spoken. It answers, writes into the note when you ask, or adds, changes and runs code cells itself until the task is done. Undo puts things back.", color = t.faint)
        Muted("Turns are saved with this note for ${AssistantHistoryLimits.DAYS} days, then kept as a short summary. Clear deletes them.", color = t.faint)
    }
    // The composer: what to ask on a raised card, with the mic and the actions under it.
    Column(Modifier.fillMaxWidth().card(radius = 16.dp).padding(horizontal = 14.dp).padding(bottom = 10.dp)) {
        val hearing = heardLive
        if (hearing != null) PromptField(hearing, {}, "Listening… ask, or say what you want done", readOnly = true) {}
        else PromptField(input, { input = it.take(AssistantLimits.MAX_REQUEST_CHARS) }, "Ask about this note, or say what to do") { send(input) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Which model answers, on the left (the web's menu under the chat box); the mic sits on the right, beside Send.
            ModelMenu()
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Mic(enabled = !agent.working, onListening = { heardLive = it }, onHeard = { send(it, voice = true) })
            if (heardLive != null) Unit
            else if (agent.working) {
                FilledBtn("Stop", { agent.stop() }, variant = ButtonVariant.Neutral, icon = Icons.Stop, small = true)
            } else {
                FilledBtn("Send", { send(input) }, enabled = input.isNotBlank(), icon = Icons.Send, small = true)
            }
        }
    }
    history.notice?.let { Notice(it, onDismiss = { history.notice = null }) }
}

/**
 * The microphone: tap Speak, and the words appear in the box as the phone hears them; tap Done (or it
 * stops by itself at two minutes, or when you stop talking) and they go to the agent. The phone's own
 * speech recognition does it: nothing to connect. The first tap asks for the microphone.
 */
@Composable
private fun Mic(enabled: Boolean, onListening: (String?) -> Unit, onHeard: (String) -> Unit) {
    val context = LocalContext.current
    var seconds by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val deliver by androidx.compose.runtime.rememberUpdatedState(onHeard)
    val live by androidx.compose.runtime.rememberUpdatedState(onListening)
    val speech = remember {
        app.benchjot.voice.Speech(context, onHeard = { live(it) }) { text, why ->
            seconds = null
            live(null)
            if (why != null) problem = why else deliver(text)
        }
    }
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    LaunchedEffect(seconds != null) {
        while (seconds != null) {
            kotlinx.coroutines.delay(1000)
            val s = seconds ?: break
            if (s + 1 >= VoiceLimits.MAX_SECONDS) { speech.finish(); break }
            seconds = s + 1
        }
    }
    fun begin() {
        problem = speech.start()
        if (problem == null) { seconds = 0; live("") }
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else problem = app.benchjot.voice.Speech.MIC_DENIED
    }
    val s = seconds
    if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TextBtn("Cancel", { speech.cancel(); seconds = null; live(null) })
        FilledBtn("Done · ${s / 60}:${(s % 60).toString().padStart(2, '0')}", { speech.finish() }, small = true)
    } else FilledBtn("Speak", {
        val has = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) begin() else ask.launch(android.Manifest.permission.RECORD_AUDIO)
    }, variant = ButtonVariant.Soft, enabled = enabled, icon = Icons.Mic, small = true, modifier = Modifier.semantics { contentDescription = "Speak a request: the agent does it in the note" })
    problem?.let { p -> Notice(p, onDismiss = { problem = null }) }
}

/** The summary and the answered turns, oldest first, newest in view; each with its own Insert, Copy and Clear. */
@Composable
private fun HistoryList(history: HistoryState, language: String, onInsertCode: (String, String) -> Unit, onCopy: (String) -> Unit, hide: Set<String>, agent: AgentSession) {
    if (history.summary.isEmpty() && history.turns.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (history.summary.isNotEmpty()) {
            Text("Earlier, condensed after ${AssistantHistoryLimits.DAYS} days", style = Type.xs, color = t.faint)
            Muted(history.summary)
            TextBtn("Clear", { history.clearSummary() })
        }
        history.turns.filter { it.saved.id !in hide }.forEach { turn ->
            Text(
                turn.request,
                Modifier.align(Alignment.End).background(t.tint, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                style = Type.sm, color = t.fg,
            )
            val r = turn.reply
            val log = r.log
            if (log != null) {
                // A request the agent carried out, kept with the note: shown as it was when it finished, Undo included.
                Finished(
                    secs = log.secs.toLong(), narration = log.narration, actions = log.actions.map { it.shown() },
                    plan = log.plan?.let { app.benchjot.core.AgentPlan(it.steps, it.done) }, answer = r.answer.orEmpty(),
                    canUndo = r.changes?.isEmpty == false && r.undone != true,
                    undone = if (r.undone == true) "done" else history.undoing[turn.key],
                    onUndo = { history.undoKept(turn, agent.note) }, onCopy = onCopy, onDelete = { history.clear(turn) },
                )
                return@forEach
            }
            if (r.kind == "code") {
                val lang = r.language.takeIf { isLanguageId(it) } ?: language
                CodeCard(r.code, lang)
                if (r.summary.isNotEmpty()) Muted(r.summary)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledBtn("Insert into note", { onInsertCode(r.code, lang) })
                    TextBtn("Copy", { onCopy(r.code) })
                    TextBtn("Clear", { history.clear(turn) })
                }
            } else {
                AnswerText(r.text, mono = false)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextBtn("Copy", { onCopy(r.text) })
                    TextBtn("Clear", { history.clear(turn) })
                }
            }
            // What it wrote into the note, and where; Undo takes it out again.
            r.write?.let { w ->
                Row(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.width(2.dp).heightIn(min = 24.dp).background(t.line))
                    Column(Modifier.padding(start = 12.dp)) { AnswerText(w.text, mono = false) }
                }
            }
            if (r.write != null) Text("Written into the note.", style = Type.sm, color = t.muted)
        }
    }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, placeholder: String, readOnly: Boolean = false, onSend: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        readOnly = readOnly,
        textStyle = Type.body.copy(color = t.fg),
        cursorBrush = SolidColor(t.fg),
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 14.dp).semantics { contentDescription = placeholder },
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, style = Type.body, color = t.faint); inner() },
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).background(t.bg, RoundedCornerShape(22.dp)).border(1.dp, t.line, RoundedCornerShape(22.dp)).plainClick(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = Type.sm, color = t.fg)
        BjIcon(Icons.Chevron, tint = t.faint, size = 14.dp)
    }
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
        StaticCode(code, language, "Code the Assistant wrote")
    }
}


/** Which AI model answers (Sonnet or Haiku), chosen right here; the same choice as Settings → AI. */
@Composable
private fun ModelMenu() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val model by app.benchjot.data.AiModel.current.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(model.label, style = Type.sm, color = t.muted)
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            BjIcon(Icons.Chevron, tint = t.faint, size = 12.dp, modifier = Modifier.rotate(if (open) -90f else 90f))
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = t.bg,
            modifier = Modifier.border(1.dp, t.line, RoundedCornerShape(10.dp)),
        ) {
            for (c in app.benchjot.data.AiModel.Choice.entries) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.label, style = Type.sm, color = t.fg)
                            Text(c.what, style = Type.xs, color = t.muted)
                        }
                    },
                    leadingIcon = { BjIcon(Icons.Check, tint = if (c == model) t.fg else androidx.compose.ui.graphics.Color.Transparent, size = 14.dp) },
                    onClick = { app.benchjot.data.AiModel.set(context, c); open = false },
                )
            }
        }
    }
}
