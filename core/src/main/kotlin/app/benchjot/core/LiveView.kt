package app.benchjot.core

// What the Android editor actually paints for liveSpans(): the display text (hidden markers gone,
// list and quote markers replaced by a fixed-width placeholder the UI draws a bullet, a checkbox or a
// quote bar over, and $math$ off the active line replaced by one placeholder the UI widens to the
// rendered formula and draws it over), the styled ranges in display offsets, and the offset mapping both ways. Pure so
// the mapping (the part that crashes a text field when wrong) is unit-tested on the JVM. The stored
// note text is never changed by any of this.

/** The placeholder a drawn glyph sits on: an em space (room for a 20dp glyph) and a space. */
const val GLYPH_PLACEHOLDER = "\u2003 "

/** The one-character placeholder a rendered formula sits on (a hair space the UI widens to the formula). */
const val MATH_PLACEHOLDER = "\u200A"

/** A styled range of the display text. */
data class DisplaySpan(val from: Int, val to: Int, val kind: LiveKind)

/**
 * A glyph the UI draws at display offset `at`: over GLYPH_PLACEHOLDER a bullet, an open or done
 * checkbox (tappable; `sourceLine` is the source offset of its line, for toggleCheck) or a quote bar;
 * over MATH_PLACEHOLDER the formula `tex`.
 */
data class LiveGlyph(val at: Int, val kind: LiveKind, val sourceLine: Int, val tex: String? = null)

class LiveView internal constructor(
    val text: String,
    val spans: List<DisplaySpan>,
    val glyphs: List<LiveGlyph>,
    private val toDisplay: IntArray,
    private val toSource: IntArray,
) {
    /** Source offset (0..source length) -> display offset. Offsets inside a hidden range collapse onto it. */
    fun displayOffset(source: Int): Int = toDisplay[source.coerceIn(0, toDisplay.size - 1)]

    /** Display offset (0..display length) -> source offset. Inside a placeholder: the start of what it replaced. */
    fun sourceOffset(display: Int): Int = toSource[display.coerceIn(0, toSource.size - 1)]
}

private data class Replace(val from: Int, val to: Int, val with: String, val glyph: LiveKind?, val tex: String? = null)

private val GLYPH_KINDS = setOf(LiveKind.Bullet, LiveKind.CheckOpen, LiveKind.CheckDone)

/** The TeX of every formula liveSpans shows rendered: math whose `$` markers are hidden (off the active line). */
fun renderedMath(source: String, spans: List<LiveSpan>): List<String> = hiddenMath(spans).map { source.substring(it.from, it.to) }

private fun hiddenMath(spans: List<LiveSpan>): List<LiveSpan> {
    val hidden = spans.filter { it.kind == LiveKind.Hide }
    val ends = hidden.mapTo(HashSet()) { it.to }
    val starts = hidden.mapTo(HashSet()) { it.from }
    return spans.filter { it.kind == LiveKind.Math && it.from in ends && it.to in starts }
}

/**
 * Builds the display for `source` and its liveSpans (any active-line choice). Formulas off the active
 * line for which `renders(tex)` is true become one MATH_PLACEHOLDER (the UI draws them); the rest stay styled source.
 */
fun liveView(source: String, spans: List<LiveSpan>, renders: (String) -> Boolean = { false }): LiveView {
    val sorted = spans.sortedBy { it.from }
    val replaces = ArrayList<Replace>()
    val styles = ArrayList<LiveSpan>()
    val quoteStarts = sorted.filter { it.kind == LiveKind.Quote }.mapTo(HashSet()) { it.from }
    // Each rendered formula replaces its `$`, its TeX and its `$` (one marker character each side).
    val formulas = hiddenMath(sorted).filter { renders(source.substring(it.from, it.to)) }
    val formulaMarkers = formulas.flatMapTo(HashSet()) { listOf(it.from - 1, it.to) }
    for (f in formulas) replaces += Replace(f.from - 1, f.to + 1, MATH_PLACEHOLDER, LiveKind.Math, source.substring(f.from, f.to))
    val formulaSpans = formulas.toHashSet()
    for (s in sorted) {
        when {
            s in formulaSpans -> Unit
            s.kind == LiveKind.Hide && s.from in formulaMarkers -> Unit
            s.kind in GLYPH_KINDS -> replaces += Replace(s.from, s.to, GLYPH_PLACEHOLDER, s.kind)
            s.kind == LiveKind.Hide -> {
                // A hidden "> " quote marker at a line start keeps room for the drawn quote bar.
                val quote = (s.from == 0 || source[s.from - 1] == '\n') && s.to in quoteStarts
                replaces += if (quote) Replace(s.from, s.to, GLYPH_PLACEHOLDER, LiveKind.Quote) else Replace(s.from, s.to, "", null)
            }
            else -> styles += s
        }
    }
    // Spans never overlap a replaced range by construction; keep the first if they ever do.
    val edits = ArrayList<Replace>()
    for (r in replaces.sortedBy { it.from }) if (edits.isEmpty() || r.from >= edits.last().to) edits += r

    val out = StringBuilder()
    val toDisplay = IntArray(source.length + 1)
    val toSource = ArrayList<Int>(source.length + 1)
    val glyphs = ArrayList<LiveGlyph>()
    var i = 0
    var e = 0
    while (i < source.length) {
        val r = edits.getOrNull(e)
        if (r != null && r.from == i) {
            val d = out.length
            for (k in r.from until r.to) toDisplay[k] = d
            out.append(r.with)
            repeat(r.with.length) { toSource += r.from }
            if (r.glyph != null) glyphs += LiveGlyph(d, r.glyph, if (r.from == 0) 0 else source.lastIndexOf('\n', r.from - 1) + 1, r.tex)
            i = r.to
            e++
            continue
        }
        toDisplay[i] = out.length
        toSource += i
        out.append(source[i])
        i++
    }
    toDisplay[source.length] = out.length
    toSource += source.length
    val display = styles.mapNotNull { s ->
        val a = toDisplay[s.from]
        val b = toDisplay[s.to]
        if (b > a) DisplaySpan(a, b, s.kind) else null
    }
    return LiveView(out.toString(), display, glyphs, toDisplay, toSource.toIntArray())
}
