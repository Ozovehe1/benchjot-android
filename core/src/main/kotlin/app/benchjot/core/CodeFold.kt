package app.benchjot.core

// Long code blocks show folded: the web's src/lib/code-fold.ts (checked by code_fold.json).

const val FOLD_LINES = 12

/** Lines as shown: a final newline doesn't start a line of its own. */
fun lineCount(code: String): Int = code.removeSuffix("\n").count { it == '\n' } + 1

fun folds(code: String): Boolean = lineCount(code) > FOLD_LINES

fun foldLabel(code: String, open: Boolean): String = if (open) "Show less" else "Show all ${lineCount(code)} lines"
