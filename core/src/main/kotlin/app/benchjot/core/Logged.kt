package app.benchjot.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.MathContext

// Experiment tracking, pure (the web's lib/run/logged.ts): what a run's code logged with log(),
// how the values read, and how a note's runs become a table. No I/O.

typealias Logged = Map<String, JsonPrimitive>

/** The most values one run may log (bj_capture.py MAX_LOGGED). */
const val MAX_LOGGED = 50

/** Logged values as kept: names 1–60 characters, numbers (finite), text up to 200, true/false; at most MAX_LOGGED; null when none. */
fun cleanLogged(logged: Logged?): Logged? {
    if (logged.isNullOrEmpty()) return null
    val out = LinkedHashMap<String, JsonPrimitive>()
    for ((k, v) in logged) {
        if (out.size >= MAX_LOGGED) break
        if (k.isEmpty() || k.length > 60) continue
        val ok = when {
            v.isString -> v.content.length <= 200
            v.booleanOrNull != null -> true
            else -> v.doubleOrNull?.isFinite() == true
        }
        if (ok) out[k] = v
    }
    return out.ifEmpty { null }
}

/** The value as a number, or null for text and true/false. */
fun loggedNumber(v: JsonPrimitive?): Double? = if (v == null || v.isString || v.booleanOrNull != null) null else v.doubleOrNull

/** A logged value as shown: numbers to 4 significant digits (whole numbers as they are), true/false, text. */
fun formatLogged(v: JsonPrimitive?): String {
    if (v == null) return ""
    if (v.isString) return v.content
    v.booleanOrNull?.let { return if (it) "true" else "false" }
    val d = v.doubleOrNull ?: return v.content
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return "%,d".format(java.util.Locale.US, d.toLong())
    val a = kotlin.math.abs(d)
    if (a != 0.0 && (a < 1e-4 || a >= 1e6)) {
        val s = "%.3e".format(java.util.Locale.US, d) // 1.234e-05
        return s.replace(Regex("e([+-])0*(\\d)"), "e$1$2").replace("e+", "e+")
    }
    return BigDecimal(d).round(MathContext(4)).stripTrailingZeros().toPlainString()
}

/** The table's columns: every name the runs logged, newest run's order first, then older names as they appear. */
fun loggedColumns(runs: List<Logged>): List<String> {
    val seen = LinkedHashSet<String>()
    for (r in runs) seen.addAll(r.keys)
    return seen.toList()
}

/** True for a column every run that has it logged as a number: it lines up right and can be charted. */
fun numericColumn(runs: List<Logged>, column: String): Boolean {
    val vs = runs.mapNotNull { it[column] }
    return vs.isNotEmpty() && vs.all { loggedNumber(it) != null }
}

/** What changed between two runs: the names whose values differ (or that only one has). */
fun differences(a: Logged, b: Logged): List<String> = loggedColumns(listOf(a, b)).filter { a[it] != b[it] }

private val RESULT_NAME = Regex("acc|loss|f1|auc|score|error|err\\b|rmse|mse|mae|r2|precision|recall|perplexity|bleu|reward|metric|val", RegexOption.IGNORE_CASE)

/** The column a chart opens on: a result (accuracy, loss, f1…) rather than a setting (lr, epochs); else the first. */
fun chartDefault(numericColumns: List<String>): String? = numericColumns.firstOrNull { RESULT_NAME.containsMatchIn(it) } ?: numericColumns.firstOrNull()
