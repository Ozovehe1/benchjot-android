package app.benchjot.core

// Navigation rules shared by the screens. Mirrors the web's src/lib/paths.ts.

const val HOME_ROUTE = "home"
fun projectRoute(id: String) = "project/$id"
fun noteRoute(id: String) = "note/$id"

/** Where a deleted note leaves you: its project's list when it was filed in one, else all notes. */
fun afterDeleteRoute(projectId: String?): String = if (projectId.isNullOrEmpty()) HOME_ROUTE else projectRoute(projectId)

/** Which top-level screen tree is shown. */
enum class Gate { Loading, SignedIn, SignedOut }

/**
 * `signedIn` is null while the session is (re)loading. supabase-kt resets its status to
 * Initializing every time the app goes to the background, so a null keeps the last decision:
 * the signed-in tree, and with it the open project or note, stays composed.
 */
fun nextGate(previous: Gate, signedIn: Boolean?): Gate = when (signedIn) {
    null -> previous
    true -> Gate.SignedIn
    false -> Gate.SignedOut
}

/** The map's nav route: optional focus (the Around lens) and depth, as the web's /map?focus=&depth=. */
const val MAP_ROUTE = "map?focus={focus}&depth={depth}"

/** Where the map opens: Around a note (depth clamped to 1–3), or Everything. Mirrors the web's mapHref. */
fun mapRoute(focus: String? = null, depth: Int = Depth.DEFAULT): String =
    if (focus.isNullOrEmpty()) "map" else "map?focus=$focus&depth=${depth.coerceIn(Depth.MIN, Depth.MAX)}"
