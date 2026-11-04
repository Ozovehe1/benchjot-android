package app.benchjot.core

// Note nesting (entries.parent_id). Mirrors the web's src/lib/tree.ts.
// The database refuses cycles; these keep the UI from offering one.

interface TreeNode : NoteRef {
    val parentId: String?
}

/** Ancestors of a note, root first (excludes the note). Stops at a missing parent or a cycle. */
fun <T : TreeNode> ancestors(id: String, nodes: List<T>): List<T> {
    val byId = nodes.associateBy { it.id }
    val out = ArrayDeque<T>()
    val seen = mutableSetOf(id)
    var next = byId[id]?.parentId
    while (next != null && next !in seen) {
        val node = byId[next] ?: break
        seen += next
        out.addFirst(node)
        next = node.parentId
    }
    return out.toList()
}

/** Direct sub-notes of a note, in the given order. */
fun <T : TreeNode> childrenOf(id: String, nodes: List<T>): List<T> = nodes.filter { it.parentId == id }

/** Every note below `id`. */
fun descendantIds(id: String, nodes: List<TreeNode>): Set<String> {
    val kids = nodes.filter { it.parentId != null }.groupBy({ it.parentId!! }, { it.id })
    val out = mutableSetOf<String>()
    val stack = ArrayDeque(kids[id].orEmpty())
    while (stack.isNotEmpty()) {
        val next = stack.removeLast()
        if (next in out || next == id) continue
        out += next
        stack.addAll(kids[next].orEmpty())
    }
    return out
}

/** True if putting `childId` under `parentId` would make a cycle (including a note under itself). */
fun wouldCycle(childId: String, parentId: String?, nodes: List<TreeNode>): Boolean =
    parentId != null && (parentId == childId || parentId in descendantIds(childId, nodes))

/** Notes that can be the parent of `id`: everything except itself and its descendants. */
fun <T : TreeNode> parentCandidates(id: String, nodes: List<T>): List<T> {
    val below = descendantIds(id, nodes)
    return nodes.filter { it.id != id && it.id !in below }
}
