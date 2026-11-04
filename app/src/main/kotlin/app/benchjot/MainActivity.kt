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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.benchjot.core.suggestUsername
import app.benchjot.data.Backend
import app.benchjot.data.Profile
import app.benchjot.ui.BenchjotTheme
import app.benchjot.ui.EntryListScreen
import app.benchjot.ui.LandingScreen
import app.benchjot.ui.LoginScreen
import app.benchjot.ui.NoteMapScreen
import app.benchjot.ui.NoteScreen
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
    val status by Backend.session.collectAsState()
    when (status) {
        is SessionStatus.Authenticated, is SessionStatus.RefreshFailure -> SignedIn()
        is SessionStatus.NotAuthenticated -> key("out") { SignedOut() }
        else -> Unit // loading the saved session: just the background
    }
}

@Composable
private fun SignedOut() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "landing") {
        composable("landing") { LandingScreen(onSignUp = { nav.navigate("login?mode=signup") }, onSignIn = { nav.navigate("login") }) }
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
        composable("settings") { SettingsScreen(username) { nav.home() } }
        composable("map") { NoteMapScreen(onHome = { nav.home() }, onOpenNote = { nav.navigate("note/$it") }) }
    }
}

@Composable
private fun List(nav: NavHostController, projectId: String?) = EntryListScreen(
    projectId = projectId,
    onOpenNote = { nav.navigate("note/$it") },
    onOpenProject = { nav.navigate("project/$it") },
    onHome = { nav.home() },
    onSettings = { nav.navigate("settings") },
    onMap = { nav.navigate("map") },
    onProjectGone = { nav.home() },
)

fun NavHostController.home() {
    if (!popBackStack("home", inclusive = false)) navigate("home")
}
