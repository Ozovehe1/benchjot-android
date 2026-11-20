package app.benchjot.core

import kotlinx.serialization.json.Json

// Where the agent's run goes, decided by the app from the cell's imports, not by the model (the
// web's serverOnlyImports and runPlace in src/lib/ai/agent.ts; agent.json checks they agree).

/** Python's own module names (the web's src/lib/run/python-stdlib.json, copied by scripts/web-fixtures.mjs). */
private val STDLIB: Set<String> by lazy {
    val text = object {}.javaClass.getResourceAsStream("/python-stdlib.json")?.bufferedReader()?.use { it.readText() } ?: "[]"
    Json.decodeFromString<List<String>>(text).toSet()
}

/** Whether a top-level module is Python's own (the standard library). */
fun isStdlib(module: String): Boolean = module in STDLIB

/** What Python has on every device (the phone's built-in set; the browser has these too) besides its standard library. */
val DEVICE_PYTHON = setOf("numpy", "pandas", "scipy", "sklearn", "matplotlib", "mpl_toolkits", "seaborn", "PIL", "plotly", "altair", "bokeh", "IPython")

/** The top-level modules a cell imports (`import a.b as c, d` → a, d; `from e.f import g` → e), in order; relative imports skipped. */
fun topLevelImports(code: String): List<String> {
    val found = mutableListOf<String>()
    fun add(name: String) {
        val top = name.trim().substringBefore(".")
        if (Regex("^[A-Za-z_]\\w*$").matches(top) && top !in found) found += top
    }
    for (raw in code.split("\n")) {
        val line = raw.replace(Regex("#.*$"), "")
        for (stmt in line.split(";")) {
            Regex("^\\s*import\\s+(.+)$").find(stmt)?.let { m -> m.groupValues[1].split(",").forEach { add(it.trim().split(Regex("\\s+as\\s+"))[0]) } }
            Regex("^\\s*from\\s+([A-Za-z_][\\w.]*)\\s+import\\b").find(stmt)?.let { add(it.groupValues[1]) }
        }
    }
    return found
}

/** The libraries a Python cell imports that only the server has (PyTorch, XGBoost, transformers, …). */
fun serverOnlyImports(code: String): List<String> = topLevelImports(code).filter { it !in STDLIB && it !in DEVICE_PYTHON }

/** A Python cell importing a library only the server has runs there; otherwise where the model asked, or the phone. */
fun runPlace(language: String, code: String, asked: String?): String? =
    if (language == "python" && serverOnlyImports(code).isNotEmpty()) "server" else asked
