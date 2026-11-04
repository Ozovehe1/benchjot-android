package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Fixtures generated from the web repo by scripts/web-fixtures.mjs. */
object Fixtures {
    fun text(name: String): String = requireNotNull(javaClass.getResource("/web/$name")) { "missing fixture $name" }.readText()
    fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))
}
