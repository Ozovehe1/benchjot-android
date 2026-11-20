package app.benchjot.core.chart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decides one axis's kind from every value that lands on it (any text that isn't a
 * number makes it a category axis, categories in order of first appearance), then maps
 * values to data space: numbers as is, categories to 0, 1, 2, ...
 */
internal class AxisBuilder(private val forceCategory: Boolean = false) {
    private val seen = mutableListOf<JsonElement>()

    fun see(vs: List<JsonElement>) { seen += vs }
    fun see(v: String) { seen += JsonPrimitive(v) }

    val categorical: Boolean by lazy { forceCategory || seen.any { it is JsonPrimitive && it.isString && it.content.toDoubleOrNull() == null } }
    val categories: List<String> by lazy { if (categorical) seen.map { it.text() }.distinct() else emptyList() }
    private val index by lazy { categories.withIndex().associate { (i, c) -> c to i.toDouble() } }

    fun pos(v: JsonElement): Double = if (categorical) index[v.text()] ?: Double.NaN else v.num() ?: Double.NaN
    fun pos(v: String): Double = if (categorical) index[v] ?: Double.NaN else v.toDoubleOrNull() ?: Double.NaN
    fun axis(title: String, reversed: Boolean = false) = Axis(title, if (categorical) AxisKind.Category else AxisKind.Linear, categories, reversed)
    fun label(v: JsonElement) = v.text()
}
