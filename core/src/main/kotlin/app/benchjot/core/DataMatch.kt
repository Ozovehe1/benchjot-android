package app.benchjot.core

// Which of the user's Data library files a cell uses: those whose name its code mentions (in quotes,
// or in a path: 'iris.csv', "data/iris.csv"). They're placed in the run's working folder before it
// starts, so pd.read_csv("iris.csv") just works. Same rule as the web (src/lib/run/datasets-match.ts).

/** The most files placed in one run. */
const val DATA_PER_RUN = 8

/** The private Storage bucket that holds each user's Data library, under a folder named by their id. */
const val DATA_BUCKET = "datasets"

/** The names the code mentions, at most DATA_PER_RUN, longest first (so "a.csv" doesn't shadow "data_a.csv"). */
fun datasetsIn(code: String, names: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (name in names.sortedByDescending { it.length }) {
        if (out.size >= DATA_PER_RUN) break
        val re = Regex("""(^|["'/\\\s(])${Regex.escape(name)}(?=$|["'\s),])""")
        if (re.containsMatchIn(code)) out += name
    }
    return out
}

/** A name a dataset may have: a plain file name (letters, digits, - _ . and spaces), up to 120 characters, with an extension (the web's validDataName). */
fun validDataName(name: String): Boolean = Regex("""^\w[\w .-]{0,118}\.[A-Za-z0-9]{1,10}$""").matches(name) && ".." !in name

/** A file's name made valid: spaces kept, anything else odd becomes "_" (the web's cleanDataName). */
fun cleanDataName(name: String): String {
    val base = name.split('/', '\\').last()
    return base.replace(Regex("""[^\w .-]"""), "_").replace(Regex("""^[^\w]+"""), "").takeLast(120)
}

/** The library's limits (the bucket's rules: 50 MB a file, 250 MB in all). */
const val DATA_FILE_MAX = 50L * 1024 * 1024
const val DATA_QUOTA = 250L * 1024 * 1024

/** How a cell opens a library file, by its extension: the line to copy (the web's openLine). */
fun openLine(name: String): String {
    val q = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return when (name.substringAfterLast('.', "").lowercase()) {
        "csv", "txt" -> "pd.read_csv($q)"
        "tsv" -> "pd.read_csv($q, sep=\"\\t\")"
        "xlsx", "xls" -> "pd.read_excel($q)"
        "parquet" -> "pd.read_parquet($q)"
        "json" -> "pd.read_json($q)"
        "npy", "npz" -> "np.load($q)"
        else -> "open($q, \"rb\")"
    }
}
