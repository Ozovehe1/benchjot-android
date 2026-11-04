package app.benchjot.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import app.benchjot.home

/** Tracer-bullet stub: replaced by the full editor. */
@Composable
fun NoteScreen(id: String, pro: Boolean, nav: NavHostController) {
    PageColumn {
        TopBar(onHome = { nav.home() }, back = "All notes" to { nav.home() })
        Muted("Note $id (pro=$pro)")
    }
}
