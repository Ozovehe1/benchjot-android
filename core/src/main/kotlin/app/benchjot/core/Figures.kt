package app.benchjot.core

// Every visual of every experiment, in one place (the Figures screen; the web's lib/run/figures.ts):
// a still for each figure, image, animation or chart a cell's last run made, newest first, with where
// it came from. Tables aren't pictures and are left out. Pure.

/** "figure" (matplotlib), "image", "animation" or "chart". */
data class Figure(
    val key: String,
    val noteId: String,
    val cellId: String,
    val index: Int,
    val kind: String,
    val title: String,
    /** What the Assistant saw in it, when it looked. */
    val seen: String?,
    val ranAt: String,
    val outputs: SavedOutputs,
    val item: OutputItem,
    /** The stored file shown. */
    val file: String,
)

/** The visuals of these cells' last runs (cell id, note id, last run), newest run first. */
fun figuresOf(cells: List<Triple<String, String, LastRun?>>): List<Figure> {
    val out = mutableListOf<Figure>()
    for ((cellId, noteId, run) in cells) {
        val o = run?.outputs ?: continue
        o.items.forEachIndexed { index, item ->
            if (item.kind == "table") return@forEachIndexed
            val f = item.files
            val file = (if (item.kind == "chart") f["png"] else f["png"] ?: f["jpg"] ?: f["webp"] ?: f["gif"]) ?: return@forEachIndexed
            val kind = when {
                item.kind == "chart" -> "chart"
                f["gif"] != null && f["png"] == null -> "animation"
                item.source == "matplotlib" -> "figure"
                else -> "image"
            }
            out += Figure("$cellId:$index", noteId, cellId, index, kind, item.title, item.seen, run.ranAt, o, item, file)
        }
    }
    return out.sortedByDescending { it.ranAt }
}
