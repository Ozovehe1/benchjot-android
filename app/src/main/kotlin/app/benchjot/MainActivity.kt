package app.benchjot

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import app.benchjot.core.SAMPLE_ASSETS
import app.benchjot.ui.OutputFiles
import app.benchjot.ui.OutputViewer
import app.benchjot.ui.loadSampleNote
import app.benchjot.ui.SampleNoteTag
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.benchjot.core.Gate
import app.benchjot.core.afterDeleteRoute
import app.benchjot.core.HOME_ROUTE
import app.benchjot.core.nextGate
import app.benchjot.core.projectRoute
import app.benchjot.core.MAP_ROUTE
import app.benchjot.core.mapRoute
import app.benchjot.core.noteRoute
import app.benchjot.core.parseDepth
import app.benchjot.core.suggestUsername
import app.benchjot.data.Backend
import app.benchjot.data.Profile
import app.benchjot.ui.BenchjotTheme
import app.benchjot.ui.EntryListScreen
import app.benchjot.ui.LandingScreen
import app.benchjot.ui.LoginScreen
import app.benchjot.ui.NoteMapScreen
import app.benchjot.ui.NoteScreen
import app.benchjot.ui.OUTPUT_ROUTE
import app.benchjot.ui.OutputScreen
import app.benchjot.ui.SettingsScreen
import app.benchjot.ui.WelcomeScreen
import app.benchjot.ui.isDark
import app.benchjot.ui.t
import io.github.jan.supabase.auth.status.SessionStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BenchjotTheme {
                val dark = isDark()
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                Box(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding().imePadding()) { AppRoot() }
            }
        }
    }
}

/** Who is signed in decides the start screen, as the web's (app) layout and /welcome do. */
@Composable
private fun AppRoot() {
    // Gate, not the raw status: supabase-kt goes back to Initializing whenever the app is
    // backgrounded, which used to drop the signed-in tree and reset the user to All notes.
    val gate by produceState(Gate.Loading) {
        Backend.session.collect { status ->
            val signedIn = when (status) {
                is SessionStatus.Authenticated, is SessionStatus.RefreshFailure -> true
                is SessionStatus.NotAuthenticated -> false
                else -> null
            }
            value = nextGate(value, signedIn)
        }
    }
    when (gate) {
        Gate.SignedIn -> SignedIn()
        Gate.SignedOut -> key("out") { SignedOut() }
        Gate.Loading -> Unit // loading the saved session: just the background
    }
}

@Composable
private fun SignedOut() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val sample = remember { loadSampleNote(context) }
    val files = remember { OutputFiles.Asset(context.assets, SAMPLE_ASSETS) }
    NavHost(nav, startDestination = "landing") {
        composable("landing") {
            LandingScreen(sample, onSignUp = { nav.navigate("login?mode=signup") }, onSignIn = { nav.navigate("login") }, onOpenOutput = { nav.navigate("example/$it") })
        }
        // The example note's outputs, read-only from the APK (the web's /example/out/{n}).
        composable("example/{n}", arguments = listOf(navArgument("n") { type = NavType.IntType })) {
            val s = sample.snippets.single()
            var index by rememberSaveable { mutableStateOf(it.arguments!!.getInt("n")) }
            OutputViewer(s.lastRun?.outputs?.items.orEmpty(), index, files, s.filename, onBack = { nav.popBackStack() }, onHome = { nav.popBackStack("landing", false) }, onStep = { index = it }, badge = { SampleNoteTag() })
        }
        composable("login?mode={mode}", arguments = listOf(navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            LoginScreen(startInSignUp = it.arguments?.getString("mode") == "signup", onHome = { nav.popBackStack("landing", false) })
        }
    }
}

@Composable
private fun SignedIn() {
    var profile by remember { mutableStateOf<Profile?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    LaunchedEffect(generation) {
        profile = runCatching { Backend.profile() }.getOrNull()
        loaded = true
    }
    if (!loaded) return
    val username = profile?.username
    if (username.isNullOrBlank()) {
        // Every account needs a username; older accounts choose one first.
        WelcomeScreen(suggestUsername(Backend.email())) { loaded = false; generation++ }
        return
    }
    val nav = rememberNavController()
    val pro = profile?.plan == "pro"
    NavHost(nav, startDestination = "home") {
        composable("home") { List(nav, null) }
        composable("project/{id}") { List(nav, it.arguments?.getString("id")) }
        composable("note/{id}") { NoteScreen(it.arguments?.getString("id").orEmpty(), pro, nav) }
        composable(OUTPUT_ROUTE, arguments = listOf(navArgument("n") { type = NavType.IntType })) {
            val a = it.arguments!!
            OutputScreen(a.getString("note").orEmpty(), a.getString("snippet").orEmpty(), a.getInt("n"), onBack = { nav.popBackStack() }, onHome = { nav.home() })
        }
        composable("settings") { SettingsScreen(username) { nav.home() } }
        composable(
            MAP_ROUTE,
            arguments = listOf(
                navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("depth") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            val focus = it.arguments?.getString("focus")?.takeIf(String::isNotEmpty)
            NoteMapScreen(
                focus = focus,
                initialDepth = parseDepth(it.arguments?.getString("depth")),
                onHome = { nav.home() },
                onOpenNote = { id -> nav.navigate(noteRoute(id)) },
                onMapAround = { id -> nav.navigate(mapRoute(id)) },
            )
        }
    }
}

@Composable
private fun List(nav: NavHostController, projectId: String?) = EntryListScreen(
    projectId = projectId,
    onOpenNote = { nav.navigate("note/$it") },
    onOpenProject = { nav.navigate("project/$it") },
    onHome = { nav.home() },
    onSettings = { nav.navigate("settings") },
    onMap = { nav.navigate(mapRoute()) },
    onProjectGone = { nav.home() },
)

fun NavHostController.home() {
    if (!popBackStack(HOME_ROUTE, inclusive = false)) navigate(HOME_ROUTE)
}

/** A project's list, on top of home (the note's back link and where deleting a filed note lands). */
fun NavHostController.project(id: String) = navigate(projectRoute(id)) { popUpTo(HOME_ROUTE) }

/** After deleting a note: its project's list when it was filed in one, else All notes. */
fun NavHostController.leaveDeletedNote(projectId: String?) =
    afterDeleteRoute(projectId).let { if (it == HOME_ROUTE) home() else navigate(it) { popUpTo(HOME_ROUTE) } }
