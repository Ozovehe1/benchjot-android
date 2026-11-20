package app.benchjot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benchjot.core.AgentFailure
import app.benchjot.core.AgentNote
import app.benchjot.core.AgentState
import app.benchjot.core.AgentUndo
import app.benchjot.core.AssistantLimits
import app.benchjot.core.runAgent
import app.benchjot.core.undoAgent
import app.benchjot.core.kept
import app.benchjot.core.shown
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.OfferException
import app.benchjot.data.WebApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The agent on the phone (the Assistant's Send or microphone, when the request is work to do): the request is carried out
// in the note itself, step by step, through the same cell controls a tap uses (core Agent.kt). It
// lives with the note screen, so it keeps going with the Assistant closed (a bar says what it's doing),
// and stops when the note is left. Stop ends it and any run it started; Undo puts the code back.

/** What the agent can do to a code cell, as the user could: change its code, run it, stop it. */
interface CellControls {
    /** Replaces the code (as typing would) and saves it. */
    suspend fun setCode(code: String)
    /** Runs it on the phone (moving to the server by itself when it must), or on the server when [where] is "server"; returns how it ended once the run is saved. Throws when it can't run. */
    suspend fun run(where: String? = null): app.benchjot.core.RunOutcome
    fun stop()
}

/** One request to the agent, as the Assistant shows it. */
class AgentItem(val key: Long, val request: String, val voice: Boolean) {
    var state by mutableStateOf<AgentState>(AgentState.Working(emptyList(), "Reading the note…"))
    val startedAt = System.currentTimeMillis()
    var endedAt by mutableStateOf<Long?>(null)
    /** Connect AI is offered with a failure for want of a connection. */
    var offer by mutableStateOf<String?>(null)
    val undo = AgentUndo()
    /** null, "working", "done" or "failed". */
    var undone by mutableStateOf<String?>(null)
    /** The Assistant turn it was saved as, so the history doesn't show it twice (and Undo can mark it undone). */
    var saved by mutableStateOf<app.benchjot.core.SavedTurn?>(null)
    val savedId get() = saved?.id
}

/** The note's agent requests while the note is open; one at a time. */
class AgentSession(private val scope: CoroutineScope, private val noteId: String, val note: AgentNote, private val context: android.content.Context, private val stopCells: () -> Unit) {
    /** The language new code is in, as of the last request (for Try again). */
    var language = "python"
        private set
    val items = mutableStateListOf<AgentItem>()
    private var job: Job? = null
    /** Stop was tapped: the request ends here. Otherwise a cancelled request (the note was left) is kept to go on. */
    private var stopping = false
    private var nextKey = 1L
    val working get() = items.any { it.state is AgentState.Working }
    val doing get() = (items.lastOrNull { it.state is AgentState.Working }?.state as? AgentState.Working)?.doing

    /** The cells it wrote in this session and hasn't undone. */
    val ownCells get() = items.filter { it.undone != "done" }.flatMap { it.undo.added }.toSet()

    fun start(text: String, voice: Boolean, language: String, history: app.benchjot.core.AssistantHistory = app.benchjot.core.AssistantHistory(), mine: Set<String> = emptySet()) {
        val request = text.trim().take(AssistantLimits.MAX_REQUEST_CHARS)
        if (request.isEmpty() || working) return
        this.language = language
        val item = AgentItem(nextKey++, request, voice)
        items += item
        drive(item, history, mine + ownCells)
    }

    /**
     * A request left mid-way (the note was closed, the app killed): picked up again from its last step,
     * once the note's cells are on the screen.
     */
    fun resumeIfKept() {
        if (working) return
        val kept = app.benchjot.data.KeptRuns.kept(context, noteId) ?: return
        language = kept.language
        val checkpoint = kept.checkpoint.shown()
        val item = AgentItem(nextKey++, kept.request, kept.voice)
        app.benchjot.core.undoFromKept(kept.undo).let { u -> item.undo.added += u.added; item.undo.changed += u.changed; item.undo.note += u.note }
        item.state = AgentState.Working(checkpoint.shown, "Picking up where it left off…", checkpoint.sent.size + 1, checkpoint.plan)
        items += item
        drive(item, kept.history, kept.mine.toSet(), checkpoint)
    }

    private fun drive(item: AgentItem, history: app.benchjot.core.AssistantHistory, mine: Set<String>, resume: app.benchjot.core.AgentCheckpoint? = null) {
        val request = item.request
        val language = language
        stopping = false
        job = scope.launch {
            val end = try {
                runAgent(request, note, { WebApi.agentStep(it) }, { item.state = it }, item.undo, history, mine, resume = resume, onCheckpoint = { c ->
                    app.benchjot.data.KeptRuns.keep(context, noteId, app.benchjot.core.KeptRun(request, item.voice, language, app.benchjot.core.keptChanges(item.undo), history, mine.toList(), c.kept(), System.currentTimeMillis()))
                })
            } catch (e: CancellationException) {
                // The note was left (not Stop): kept on the phone to go on when it is opened again.
                if (!stopping) throw e
                app.benchjot.data.KeptRuns.drop(context, noteId)
                // Stopped (Stop, or the note was left): what it did is kept all the same, so its Undo isn't lost.
                val stopped = item.state as? AgentState.Stopped ?: AgentState.Stopped((item.state as? AgentState.Working)?.steps ?: emptyList())
                item.state = stopped
                item.endedAt = System.currentTimeMillis()
                keep(item, stopped, request, language)
                throw e
            } catch (e: AgentFailure) {
                item.offer = (e.cause as? OfferException)?.offer
                AgentState.Failed(e.steps, Accounts.message(e.cause, "agent"), e.plan)
            }
            app.benchjot.data.KeptRuns.drop(context, noteId)
            item.state = end
            item.endedAt = System.currentTimeMillis()
            keep(item, end, request, language)
        }
    }

    /**
     * Kept with the note: what was asked, what it did (so the thread reads the same after the app
     * is closed), its answer, and what Undo needs (so Undo still works then). Stopped or failed
     * requests that changed something are kept too, so their Undo isn't lost. Saved on the app's
     * scope, so leaving the note doesn't cancel the save.
     */
    private fun keep(item: AgentItem, end: AgentState, request: String, language: String) {
        if (end !is AgentState.Done && item.undo.isEmpty) return
        val reply = app.benchjot.core.keptReply(end, item.undo, (item.endedAt!! - item.startedAt) / 1000, language)
        Backend.scope.launch {
            runCatching { Backend.saveAssistantTurn(noteId, "ask", request, reply) }.onSuccess { row ->
                item.saved = row
                // Undone before it finished saving: the kept turn says so too, or reopening the note
                // would offer Undo again and put back code over what the user has written since.
                if (item.undone == "done") runCatching { Backend.resaveAssistantTurn(noteId, row, row.answer.copy(undone = true)) }.onSuccess { item.saved = it }
            }
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
        stopCells()
    }

    fun undo(item: AgentItem) {
        item.undone = "working"
        scope.launch {
            item.undone = if (runCatching { undoAgent(note, item.undo) }.isSuccess) "done" else "failed"
            // The kept turn says it was undone, so it reads so after the app is closed too.
            val row = item.saved
            if (item.undone == "done" && row != null) Backend.scope.launch {
                runCatching { Backend.resaveAssistantTurn(noteId, row, row.answer.copy(undone = true)) }.onSuccess { item.saved = it }
            }
        }
    }

    fun clear(item: AgentItem) { items.remove(item) }
}

/** The bar at the bottom of the note while the assistant works with the sheet closed. */
@Composable
fun AgentBar(session: AgentSession, onOpen: () -> Unit) {
    val doing = session.doing ?: return
    Row(
        Modifier.fillMaxWidth().background(t.bg).topLine(t.line).navigationBarsPadding().heightIn(min = 56.dp).padding(horizontal = 20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Working(doing, Modifier.weight(1f))
        TextBtn("Open", onOpen)
        FilledBtn("Stop", { session.stop() }, variant = ButtonVariant.Neutral, small = true)
    }
}

/**
 * One request to the assistant (the web's assistant-panel.tsx): your message on the right; while it
 * works, what it says it's doing, each thing done in the note (✓ ran, ✗ failed) and a live line with
 * the time; when it's done, the steps fold into one line ("Worked 41 s · ran 2 cells") above the
 * answer, with Copy, Undo and Delete.
 */
@Composable
fun AgentItemView(item: AgentItem, session: AgentSession) {
    val s = item.state
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val actions = s.steps.flatMap { it.actions }
    val said = s.steps.map { it.message.trim() }.filter { it.isNotEmpty() }
    val answer = if (s is AgentState.Working) "" else said.lastOrNull().orEmpty()
    val narration = if (s is AgentState.Working) said else said.dropLast(1)
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(s is AgentState.Working) {
        while (item.state is AgentState.Working) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
        now = System.currentTimeMillis()
    }
    val secs = ((if (s is AgentState.Working) now else item.endedAt ?: now) - item.startedAt).coerceAtLeast(0) / 1000
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Assistant request" }) {
        Text(
            (if (item.voice) "🎙 " else "") + item.request,
            Modifier.align(Alignment.End).fillMaxWidth(0.85f).wrapContentWidth(Alignment.End).background(t.tint, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
            style = Type.body, color = t.fg,
        )
        if (s is AgentState.Working) {
            s.plan?.let { PlanList(it, working = true) }
            narration.lastOrNull()?.let { Text(it, style = Type.sm, color = t.muted) }
            Activity(actions)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Working(s.doing, Modifier.weight(1f))
                Text(duration(secs), style = Type.code, color = t.faint)
            }
        } else {
            Finished(
                secs = secs, narration = narration, actions = actions, plan = s.plan, answer = answer,
                canUndo = !item.undo.isEmpty || item.undone != null, undone = item.undone,
                onUndo = { session.undo(item) }, onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }, onDelete = { session.clear(item) },
            ) {
                when (s) {
                    is AgentState.Failed -> Notice(
                        s.message,
                        actions = if (!session.working && item.offer != "own_key") listOf(NoticeAction("Try again") { session.start(item.request, item.voice, session.language) }) else emptyList(),
                    )
                    is AgentState.Stopped -> Muted("Stopped.")
                    else -> {}
                }
            }
        }
    }
}

/**
 * A finished request (live, or kept with the note and read back after the app was closed): the steps
 * folded into one line ("Worked 41 s · ran 2 cells"), opened to the plan, what it said and each thing
 * done; the answer; then Copy, Undo and Delete.
 */
@Composable
fun Finished(
    secs: Long,
    narration: List<String>,
    actions: List<app.benchjot.core.AgentAction>,
    plan: app.benchjot.core.AgentPlan?,
    answer: String,
    canUndo: Boolean,
    undone: String?,
    onUndo: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (actions.isNotEmpty() || narration.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).plainClick { open = !open }.semantics { contentDescription = if (open) "Hide the steps" else "Show the steps" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BjIcon(Icons.Chevron, tint = t.muted, size = 14.dp, modifier = Modifier.rotate(if (open) 90f else 0f))
                Text(workedLine(secs, actions), style = Type.sm, color = t.muted)
            }
            if (open) Column(Modifier.padding(start = 6.dp).leftLine(t.line).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan?.let { PlanList(it, working = false) }
                narration.forEach { Text(it, style = Type.sm, color = t.muted) }
                Activity(actions)
            }
        }
        if (answer.isNotEmpty()) Text(answer, style = Type.body, color = t.fg)
        extra()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (answer.isNotEmpty()) IconTap(Icons.Copy, "Copy") { onCopy(answer) }
            when {
                undone == "done" -> Text("Undone", style = Type.xs, color = t.faint, modifier = Modifier.padding(horizontal = 8.dp))
                canUndo -> IconTap(Icons.Undo, if (undone == "failed") "Undo didn’t finish. Try again" else "Undo everything it changed", enabled = undone != "working") { onUndo() }
            }
            IconTap(Icons.Trash, "Delete") { onDelete() }
        }
    }
}

/** Each thing done in the note: ✓ ran cleanly, ✗ failed, • the rest; each opens to show the code, the change, what the run gave, or the text. */
@Composable
private fun Activity(actions: List<app.benchjot.core.AgentAction>) {
    if (actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.semantics { contentDescription = "Done in the note" }) {
        actions.forEach { a -> ActivityRow(a) }
    }
}

@Composable
private fun ActivityRow(a: app.benchjot.core.AgentAction) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(6.dp)).plainClick(enabled = a.detail != null && a.detail?.kind != "proposal") { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (a.tone) {
                "ok" -> BjIcon(Icons.Check, tint = t.ok, size = 14.dp)
                "fail" -> BjIcon(Icons.X, tint = t.fail, size = 14.dp)
                else -> Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(t.faint, androidx.compose.foundation.shape.CircleShape)) }
            }
            Text(a.text, style = Type.code, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (a.detail != null && a.detail?.kind != "proposal") BjIcon(Icons.Chevron, tint = t.faint, size = 12.dp, modifier = Modifier.rotate(if (open) 90f else 0f))
        }
        val d = a.detail
        if (d?.kind == "proposal") ProposalCard(app.benchjot.core.NoteProposal(d.noteId, d.title, d.after, d.text))
        else if (open && d != null) Box(
            Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp).fillMaxWidth().heightIn(max = 280.dp)
                .background(t.raise, RoundedCornerShape(10.dp)).border(1.dp, t.line, RoundedCornerShape(10.dp))
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(10.dp),
        ) {
            when (d.kind) {
                "diff" -> Column {
                    app.benchjot.core.diffHunks(app.benchjot.core.lineDiff(d.before, d.text)).forEach { h ->
                        if (h == null) Text("⋯", style = Type.code, color = t.faint)
                        else {
                            val add = h.kind == app.benchjot.core.DiffKind.Add
                            val del = h.kind == app.benchjot.core.DiffKind.Del
                            Text(
                                (if (add) "+ " else if (del) "− " else "  ") + h.text,
                                style = Type.code.copy(textDecoration = if (del) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                                color = if (add) t.fg else if (del) t.muted else t.faint,
                                modifier = if (add) Modifier.fillMaxWidth().background(t.tint) else Modifier,
                            )
                        }
                    }
                }
                "text" -> Text(d.text, style = Type.sm, color = t.fg)
                else -> Text(d.text, style = Type.code, color = t.fg)
            }
        }
    }
}

/** How a change proposed to another note is applied on the user's tap: "applied", or "already" when that note holds it. Provided by the note screen. */
val LocalApplyProposal = androidx.compose.runtime.staticCompositionLocalOf<(suspend (app.benchjot.core.NoteProposal) -> String)?> { null }

/**
 * A change proposed to another note (the web's ProposalCard): what would be added and where, with
 * Apply. Nothing changes there until the user taps it; a second tap never adds it twice.
 */
@Composable
private fun ProposalCard(p: app.benchjot.core.NoteProposal) {
    val apply = LocalApplyProposal.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by androidx.compose.runtime.remember { mutableStateOf("idle") }
    val done = state == "applied" || state == "already"
    Column(
        Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp).fillMaxWidth()
            .background(t.raise, RoundedCornerShape(10.dp)).border(1.dp, t.line, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Proposed for ${p.title}, ${if (p.after.isNotEmpty()) "under “${p.after}”" else "at the end"}:", style = Type.xs, color = t.muted)
        Box(Modifier.heightIn(max = 200.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { Text(p.text, style = Type.sm, color = t.fg) }
        if (apply != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, t.line, RoundedCornerShape(6.dp))
                    .plainClick(enabled = state != "working" && !done) {
                        state = "working"
                        scope.launch { state = try { apply(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "failed" } }
                    }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (done) BjIcon(Icons.Check, tint = t.ok, size = 14.dp)
                Text(if (done) (if (state == "already") "Already in that note" else "Applied") else if (state == "working") "Applying…" else "Apply to ${p.title}", style = Type.sm, color = t.fg)
            }
            if (state == "failed") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BjIcon(Icons.X, tint = t.fail, size = 14.dp)
                Text("It couldn’t be applied. Try again.", style = Type.xs, color = t.fg)
            }
        }
    }
}

/** The plan as a checklist: finished steps ticked, the one it's on turning (while it works), the rest waiting. */
@Composable
private fun PlanList(plan: app.benchjot.core.AgentPlan, working: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "Plan" }) {
        plan.steps.forEachIndexed { i, step ->
            val done = i < plan.done
            val now = working && i == plan.done
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        done -> BjIcon(Icons.Check, tint = t.ok, size = 14.dp)
                        now -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = t.muted, strokeWidth = 1.5.dp)
                        else -> Box(Modifier.size(11.dp).border(1.dp, t.faint, androidx.compose.foundation.shape.CircleShape))
                    }
                }
                Text(step, style = Type.sm, color = if (now) t.fg else t.muted)
            }
        }
    }
}

@Composable
private fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).plainClick(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { BjIcon(icon, tint = if (enabled) t.muted else t.faint, size = 18.dp) }
}

private fun duration(s: Long) = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"

/** "Worked 41 s · ran 2 cells · wrote in the note" (the web's workedLine). */
private fun workedLine(secs: Long, actions: List<app.benchjot.core.AgentAction>): String {
    val ran = actions.count { it.text.startsWith("Ran cell") }
    val failed = actions.count { it.tone == "fail" }
    val wrote = actions.any { it.text.startsWith("Wrote in the note") || it.text.contains("note’s text") || it.text.startsWith("Removed text") }
    return buildList {
        add("Worked ${duration(secs)}")
        if (ran > 0) add("ran $ran cell${if (ran == 1) "" else "s"}")
        if (wrote) add("wrote in the note")
        if (failed > 0) add("$failed failed on the way")
    }.joinToString(" · ")
}
