package app.benchjot.core

import kotlinx.serialization.Serializable

// AI Fix, the pure parts. Mirrors the web's src/lib/fix.ts: the request the
// /api/fix route takes, its answer, and the minimal line diff shown to accept or reject.

const val FIX_MAX_CODE_CHARS = 50_000

@Serializable
data class FixRequest(val language: String, val code: String, val error: String, val outcome: String? = null)

@Serializable
data class FixAnswer(val code: String, val summary: String = "")

enum class DiffKind { Same, Del, Add }

data class DiffOp(val kind: DiffKind, val text: String)

data class DiffLine(val kind: DiffKind, val text: String, val line: Int)

/** Line diff by longest common subsequence. Falls back to delete-all/add-all on huge inputs. */
fun lineDiff(before: String, after: String): List<DiffOp> {
    val a = before.split("\n")
    val b = after.split("\n")
    val n = a.size
    val m = b.size
    if (n.toLong() * m > 4_000_000) return a.map { DiffOp(DiffKind.Del, it) } + b.map { DiffOp(DiffKind.Add, it) }
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
        lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    val ops = mutableListOf<DiffOp>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { ops += DiffOp(DiffKind.Same, a[i]); i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> ops += DiffOp(DiffKind.Del, a[i++])
            else -> ops += DiffOp(DiffKind.Add, b[j++])
        }
    }
    while (i < n) ops += DiffOp(DiffKind.Del, a[i++])
    while (j < m) ops += DiffOp(DiffKind.Add, b[j++])
    return ops
}

/** Only the changed lines plus `context` unchanged lines around each; null marks a gap. Empty when nothing changed. */
fun diffHunks(ops: List<DiffOp>, context: Int = 1): List<DiffLine?> {
    var oldLine = 0
    var newLine = 0
    val numbered = ops.map { op ->
        if (op.kind != DiffKind.Add) oldLine++
        if (op.kind != DiffKind.Del) newLine++
        DiffLine(op.kind, op.text, if (op.kind == DiffKind.Add) newLine else oldLine)
    }
    val keep = BooleanArray(numbered.size)
    numbered.forEachIndexed { k, op ->
        if (op.kind == DiffKind.Same) return@forEachIndexed
        for (x in maxOf(0, k - context)..minOf(numbered.size - 1, k + context)) keep[x] = true
    }
    val out = mutableListOf<DiffLine?>()
    numbered.forEachIndexed { k, op ->
        if (keep[k]) out += op
        else if (out.isNotEmpty() && out.last() != null && (k until numbered.size).any { keep[it] }) out += null
    }
    return out
}
