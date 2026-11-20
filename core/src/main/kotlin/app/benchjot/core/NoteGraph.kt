package app.benchjot.core

// The note's context graph (the web's noteGraph in src/lib/ai/assistant.ts; agent.json checks they
// agree): a short, accurate card for the note and one for each cell, built from the saved note itself,
// with no AI request. The agent reads it first, so whatever it works on it knows where that sits.

object GraphLimits {
    const val LINE = 240
    const val CELL = 900
    const val GRAPH_MAX = 10_000
}

private fun oneLine(t: String, max: Int = GraphLimits.LINE) = headCut(t.replace(Regex("\\s+"), " ").trim(), max)

private val NOTE_PARTS = listOf(
    "Question" to Regex("^(question|aim|goal|hypothesis|objective)s?$", RegexOption.IGNORE_CASE),
    "Setup" to Regex("^(setup|set-up|method|methods|approach|design|data)$", RegexOption.IGNORE_CASE),
    "Result" to Regex("^(results?|findings?|conclusions?|outcome)$", RegexOption.IGNORE_CASE),
)

/** The note's Question, Setup and Result, from lines headed by those words ("**Question** …", "## Result", "Setup: …"). */
fun noteParts(body: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val paras = body.split(Regex("\\n\\s*\\n"))
    val head = Regex("^(?:#{1,6}\\s*)?(?:\\*\\*|__)?([A-Za-z-]+)(?:\\*\\*|__)?\\s*[:.\\-–]?\\s*([\\s\\S]*)$")
    for (i in paras.indices) {
        val m = head.find(paras[i].trim()) ?: continue
        val found = NOTE_PARTS.firstOrNull { it.second.matches(m.groupValues[1]) } ?: continue
        if (out.any { it.first == found.first }) continue
        val text = m.groupValues[2].trim().ifEmpty { paras.getOrNull(i + 1)?.trim().orEmpty() }
        if (text.isNotEmpty()) out += found.first to oneLine(text)
    }
    return out
}

private fun purposeOf(code: String): String {
    val comment = Regex("^(?:#+|//+|%+|!+|--)\\s*(.+)$")
    val doc = Regex("^(?:\"\"\"|''')\\s*(.+?)\\s*(?:\"\"\"|''')?$")
    for (raw in code.split("\n").take(12)) {
        val line = raw.trim()
        val m = comment.find(line) ?: doc.find(line) ?: continue
        val t = m.groupValues[1]
        if (!Regex("^(pip|gpu|timeout|network)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(t) && t.length > 3) return oneLine(t, 160)
    }
    return ""
}

private fun runOf(c: Cell): String {
    val r = c.lastRun ?: return "not run yet"
    val where = if (r.where == "server") "on the server" else "on the device"
    val time = if (r.ms >= 1000) String.format(java.util.Locale.ROOT, "%.1f s", r.ms / 1000.0) else "${r.ms} ms"
    val how = when (r.outcome) {
        RunOutcome.Ok -> "ran ok $where in $time"
        RunOutcome.Error -> "failed $where: ${oneLine(r.errorLine ?: r.stderr.trim().split("\n").last(), 140)}"
        RunOutcome.Timeout -> "ran out of time $where"
        RunOutcome.Stopped -> "was stopped $where"
    }
    val stale = if (r.codeHash.isNotEmpty() && r.codeHash != codeHash(c.content)) "; CHANGED since that run, so its results are of older code" else ""
    return how + stale
}

private fun foundOf(stdout: String): String {
    val lines = stdout.split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[Benchjot]") }
    val keyed = lines.filter { l -> l.any { it.isDigit() } }
    val pick = (keyed.ifEmpty { lines }).takeLast(2)
    return if (pick.isEmpty()) "" else oneLine(pick.joinToString(" | "), 200)
}

/** The note's context graph; '' for an empty note. [outcome]: "success", "failed" or null. */
fun noteGraph(title: String, raw: String, outcome: String?, cells: List<Cell>): String {
    val head = mutableListOf("Note: ${oneLine(title.ifEmpty { "Untitled" }, 160)} · outcome: ${when (outcome) { "success" -> "Successful"; "failed" -> "Failed"; else -> "not marked yet" }}")
    // [[Title|id]] links read as their titles; the notes linked to are listed once (find_notes reaches them).
    val linked = parseLinks(raw).mapNotNull { (it as? Segment.Link)?.label?.takeIf { l -> l.isNotEmpty() } }.distinct()
    val body = LINK_REGEX.replace(raw) { it.groupValues[1].trim() }
    val parts = noteParts(body)
    if (parts.isNotEmpty()) head += parts.map { "${it.first}: ${it.second}" }
    else if (body.isNotBlank()) head += "About: ${oneLine(body, 200)}"
    if (linked.isNotEmpty()) head += "Links to: ${oneLine(linked.take(8).joinToString("; "), 300)}"
    val cards = cells.mapIndexed { i, c ->
        val bits = mutableListOf("[Cell ${i + 1}] ${c.language}")
        purposeOf(c.content).takeIf { it.isNotEmpty() }?.let { bits += "purpose: $it" }
        if (c.noteBefore.isNotBlank()) bits += "expected: ${oneLine(c.noteBefore, 160)}"
        bits += runOf(c)
        val ok = c.lastRun?.outcome == RunOutcome.Ok
        val shown = if (ok) c.lastRun?.outputs?.items.orEmpty().map { producedLabel(it, 200) } else emptyList()
        if (shown.isNotEmpty()) bits += "shown in the note: ${shown.joinToString("; ")}"
        val found = if (ok) foundOf(c.lastRun?.stdout.orEmpty()) else ""
        if (found.isNotEmpty()) bits += "printed: $found"
        if (c.noteAfter.isNotBlank()) bits += "observed: ${oneLine(c.noteAfter, 160)}"
        if (c.language == "python") {
            val libs = topLevelImports(c.content).filter { !isStdlib(it) }
            if (libs.isNotEmpty()) bits += "uses: ${libs.take(8).joinToString(", ")}"
        }
        headCut(bits.joinToString(" · "), GraphLimits.CELL)
    }
    if (body.isBlank() && cards.isEmpty()) return ""
    val text = (head + if (cards.isNotEmpty()) listOf("Cells:") + cards else listOf("Cells: none yet")).joinToString("\n")
    return "<graph>\n${headCut(text, GraphLimits.GRAPH_MAX)}\n</graph>"
}
