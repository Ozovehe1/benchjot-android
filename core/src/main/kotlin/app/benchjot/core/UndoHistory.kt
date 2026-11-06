package app.benchjot.core

// Undo / redo for one Markdown text block (the spec: per block; the body's history never touches a
// code cell's and vice versa). Pure: the editor reports every change with the time it happened.
// Typing coalesces: consecutive single-step typing edits within `windowMillis` of each other undo as
// one step, and a new line or a pause starts a new step. Format commands, list continuation,
// checkbox taps and pasted text are always their own step.

/** Text and selection of a block at one moment. */
data class Snapshot(val text: String, val start: Int, val end: Int) {
    constructor(e: FormatEdit) : this(e.text, e.start, e.end)
}

enum class EditKind { Typing, Command }

class UndoHistory(initial: Snapshot, private val windowMillis: Long = 1000, private val limit: Int = 200) {
    init {
        require(windowMillis >= 0) { "windowMillis must be >= 0" }
        require(limit > 0) { "limit must be > 0" }
    }

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var lastTyping: Long? = null

    var current: Snapshot = initial
        private set

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Records the block's new state. A selection-only change moves the cursor without a step. */
    fun record(next: Snapshot, kind: EditKind, nowMillis: Long) {
        if (next.text == current.text) { current = next; return }
        val typing = kind == EditKind.Typing && isTypingStep(current.text, next.text)
        val joins = typing && lastTyping?.let { nowMillis - it in 0..windowMillis } == true && !startsNewStep(current.text, next.text)
        if (!joins) {
            undo.addLast(current)
            if (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        current = next
        lastTyping = if (typing) nowMillis else null
    }

    fun undo(): Snapshot? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = prev
        lastTyping = null
        return prev
    }

    fun redo(): Snapshot? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        current = next
        lastTyping = null
        return next
    }

    /** Starts over at `s` (a block loaded with other text). */
    fun reset(s: Snapshot) {
        undo.clear(); redo.clear(); current = s; lastTyping = null
    }
}

/** One key's worth of typing: a single character (or surrogate pair) inserted or deleted. */
internal fun isTypingStep(before: String, after: String): Boolean {
    val d = after.length - before.length
    if (d == 0 || d !in -2..2) return false
    val (long, short) = if (d > 0) after to before else before to after
    val p = long.commonPrefixWith(short).length
    val s = long.commonSuffixWith(short).length.coerceAtMost(short.length - p)
    val changed = long.substring(p, long.length - s)
    return changed.length == 1 || (changed.length == 2 && changed[0].isHighSurrogate())
}

/** A typed new line starts a new undo step. */
private fun startsNewStep(before: String, after: String): Boolean =
    after.length > before.length && after.count { it == '\n' } > before.count { it == '\n' }
