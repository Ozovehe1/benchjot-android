package app.benchjot.core

// The helper's token budget: prompt plus answer must fit the model's context, always.
// Counted in real tokens (the app passes llama.cpp's own tokenizer as a [TokenCounter]),
// never guessed from characters. When the answer would get too little room, the input is
// trimmed first (the error's front, then the code to a window around the error), the trim is
// said in the prompt, and if even the smallest input can't fit, the plan is TooLarge.
//
// Analogy: packing a suitcase with a fixed size. Weigh what goes in, keep room for what comes
// back, take the least needed things out first, and say no rather than burst the zip.

/** Counts tokens exactly as the model will tokenize the text. The app's counter is llama.cpp's. */
fun interface TokenCounter { fun count(text: String): Int }

/** What the helper was asked, before it is a prompt. The prompt is built when its size is known. */
sealed interface AssistInput {
    val task: AssistTask

    /** If the code must be cut, the error's line stays in view; [cursorLine] (1-based) when the error names none in the code. */
    data class Fix(val language: String, val code: String, val error: String, val cursorLine: Int? = null) : AssistInput {
        override val task get() = AssistTask.Fix
    }
    data class Write(val language: String, val request: String) : AssistInput {
        override val task get() = AssistTask.Write
    }
    data class Ask(val noteTitle: String, val noteText: String, val question: String) : AssistInput {
        override val task get() = AssistTask.Ask
    }
    /** A prompt already in the model's chat format; it fits or is TooLarge, never trimmed. */
    data class Raw(override val task: AssistTask, val prompt: String) : AssistInput
}

/**
 * Lines [first]..[last] (0-based, inclusive) of a [totalLines]-line program: the part a trimmed
 * Fix showed the model, whose answer replaces exactly those lines.
 */
data class CodeWindow(val first: Int, val last: Int, val totalLines: Int) {
    init { require(first in 0..last && last < totalLines) { "bad window $first..$last of $totalLines" } }

    /** [code] with the window's lines replaced by [replacement]; null if [code] isn't the program it was cut from. */
    fun splice(code: String, replacement: String): String? {
        val lines = code.split("\n")
        if (lines.size != totalLines) return null
        return (lines.subList(0, first) + replacement.split("\n") + lines.subList(last + 1, totalLines)).joinToString("\n")
    }
}

sealed interface AssistPlan {
    /** [maxNewTokens] never exceeds the context left after [promptTokens] and the safety margin. */
    data class Ready(val prompt: String, val promptTokens: Int, val maxNewTokens: Int, val trimmed: Boolean, val window: CodeWindow? = null) : AssistPlan
    /** Even the smallest version of the input leaves too little room to answer. */
    data class TooLarge(val promptTokens: Int) : AssistPlan
}

object AssistBudget {
    /** Tokens never used: slack for the end-of-turn token and any off-by-one in the engine. */
    const val SAFETY_TOKENS = 32
    /** Below this much room for the answer, the input is trimmed rather than the answer starved. */
    const val MIN_REPLY_TOKENS = 512
    /** What a fix says besides the code it rewrites: the fences and one sentence. */
    const val FIX_SUMMARY_TOKENS = 96
    /** The error's last lines (the exception and where it happened) are never trimmed away. */
    const val MIN_ERROR_LINES = 3

    fun plan(input: AssistInput, counter: TokenCounter, contextTokens: Int = OnDeviceModel.CONTEXT_TOKENS): AssistPlan {
        require(contextTokens > SAFETY_TOKENS + MIN_REPLY_TOKENS) { "context of $contextTokens tokens is too small" }
        val b = Fitter(input.task, counter, contextTokens)
        return when (input) {
            is AssistInput.Raw -> b.fit(input.prompt, b.minReply()) ?: b.tooLarge()
            is AssistInput.Write -> b.fit(AssistPrompts.write(input.language, input.request), b.minReply()) ?: b.tooLarge()
            is AssistInput.Ask -> planAsk(input, b)
            is AssistInput.Fix -> planFix(input, b)
        }
    }

    private class Fitter(val task: AssistTask, val counter: TokenCounter, val contextTokens: Int) {
        var lastPromptTokens = 0

        fun minReply(): Int = minOf(MIN_REPLY_TOKENS, task.maxNewTokens)

        /** A Ready plan if [prompt] leaves at least [need] tokens to answer, else null. */
        fun fit(prompt: String, need: Int, trimmed: Boolean = false, window: CodeWindow? = null): AssistPlan.Ready? {
            val p = counter.count(prompt)
            check(p >= 0) { "token count failed" }
            lastPromptTokens = p
            val room = contextTokens - p - SAFETY_TOKENS
            if (room < need || room < 1) return null
            return AssistPlan.Ready(prompt, p, minOf(task.maxNewTokens, room), trimmed, window)
        }

        fun tooLarge() = AssistPlan.TooLarge(lastPromptTokens)
    }

    // Ask: keep the note's beginning, cut whole lines from its end.
    private fun planAsk(input: AssistInput.Ask, b: Fitter): AssistPlan {
        val need = b.minReply()
        b.fit(AssistPrompts.ask(input.noteTitle, input.noteText, input.question), need)?.let { return it }
        val lines = input.noteText.split("\n")
        fun attempt(keep: Int) = b.fit(AssistPrompts.ask(input.noteTitle, lines.take(keep).joinToString("\n") + "\n" + NOTE_CUT, input.question), need, trimmed = true)
        return largestFitting(lines.size - 1, ::attempt) ?: b.tooLarge()
    }

    // Fix: the answer rewrites the code shown, so it needs room for that code too.
    private fun planFix(input: AssistInput.Fix, b: Fitter): AssistPlan {
        fun need(code: String) = minOf(b.task.maxNewTokens, maxOf(MIN_REPLY_TOKENS, b.counter.count(code) + FIX_SUMMARY_TOKENS))
        val (errorLines, errorCut) = errorTail(input.error)
        val fullNeed = need(input.code)

        // 1. As it is.
        b.fit(AssistPrompts.fix(input.language, input.code, errorLines, errorCut, null), fullNeed, trimmed = errorCut)?.let { return it }

        // 2. The error from its front, halving, down to its last few lines.
        var error = errorLines
        while (error.size > MIN_ERROR_LINES) {
            error = error.takeLast(maxOf(MIN_ERROR_LINES, error.size / 2))
            b.fit(AssistPrompts.fix(input.language, input.code, error, true, null), fullNeed, trimmed = true)?.let { return it }
        }
        val shortError = error
        val shortCut = errorCut || error.size < errorLines.size

        // 3. The code to a window of whole lines around the focus line.
        val code = input.code.split("\n")
        val errorLine = lastLineNumber(input.error)?.takeIf { it in 1..code.size }
        val focus = ((errorLine ?: input.cursorLine ?: 1) - 1).coerceIn(0, code.size - 1)
        fun attempt(size: Int): AssistPlan.Ready? {
            val w = windowAround(focus, size, code.size)
            val shown = code.subList(w.first, w.last + 1).joinToString("\n")
            return b.fit(AssistPrompts.fix(input.language, shown, shortError, shortCut, w), need(shown), trimmed = true, window = w)
        }
        return largestFitting(code.size - 1, ::attempt, min = 1) ?: b.tooLarge()
    }

    /**
     * The largest n in [min]..[max] whose attempt fits, by binary search (prompt size grows with n).
     * Only an attempt that was actually counted and fit is ever returned.
     */
    private fun largestFitting(max: Int, attempt: (Int) -> AssistPlan.Ready?, min: Int = 0): AssistPlan.Ready? {
        if (max < min) return null
        var best: AssistPlan.Ready? = null
        var lo = min
        var hi = max
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = attempt(mid)
            if (r != null) { best = r; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** [size] lines containing [focus], centred on it where the program's ends allow. */
    internal fun windowAround(focus: Int, size: Int, total: Int): CodeWindow {
        require(size in 1..total && focus in 0 until total)
        val first = (focus - (size - 1) / 2).coerceIn(0, total - size)
        return CodeWindow(first, first + size - 1, total)
    }

    /**
     * The error's last lines, at most [max] chars (the phone's [ASSIST_ERROR_TAIL_CHARS] unless said), cut at a line start. A last
     * line longer than that alone keeps its end. The flag says whether anything was cut.
     */
    internal fun errorTail(error: String, max: Int = ASSIST_ERROR_TAIL_CHARS): Pair<List<String>, Boolean> {
        val lines = error.trimEnd().split("\n")
        val kept = ArrayDeque<String>()
        var chars = 0
        for (line in lines.asReversed()) {
            if (kept.isNotEmpty() && chars + line.length + 1 > max) break
            kept.addFirst(if (kept.isEmpty()) line.takeLast(max) else line)
            chars += kept.first().length + 1
        }
        return kept.toList() to (kept.size < lines.size || kept.first().length < lines[lines.size - kept.size].length)
    }

    private val LINE_NUMBER = Regex("""\bline (\d+)""")

    /** The last "line N" an error mentions (Python tracebacks end with the failing line). */
    internal fun lastLineNumber(error: String): Int? = LINE_NUMBER.findAll(error).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** The 1-based line [offset] (a cursor position) falls on in [text]. */
    fun lineAt(text: String, offset: Int): Int {
        val end = offset.coerceIn(0, text.length)
        return 1 + (0 until end).count { text[it] == '\n' }
    }

    const val NOTE_CUT = "[The rest of the note was cut to fit.]"
}
