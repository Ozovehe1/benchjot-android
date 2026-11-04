package app.benchjot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.benchjot.BuildConfig
import app.benchjot.core.APP_DESCRIPTION
import app.benchjot.core.USERNAME_HINT
import app.benchjot.core.isValidEmail
import app.benchjot.core.isValidUsername
import app.benchjot.core.looksLikeEmail
import app.benchjot.core.normalizeUsername
import app.benchjot.data.Accounts
import kotlinx.coroutines.launch

// Signed-out screens, as the web's src/app/page.tsx (landing), login/page.tsx and
// components/choose-username.tsx. Same words, same order.

/** The landing's example jotter entry: a note, a Python snippet, its output and a takeaway. */
@Composable
private fun EntryPreview() {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().border(1.dp, t.line, shape).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
        Text("Project: tokenizer study", style = TextStyle(fontFamily = Type.mono, fontSize = 11.sp), color = t.faint)
        Text("Exp 3 — dropout 0.1 vs 0.3", Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = t.fg)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = t.fg)) { append("Hypothesis:") }
                append(" lower dropout helps on the small set.")
            },
            Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 13.sp, lineHeight = 1.375.em), color = t.muted,
        )
        val code = TextStyle(fontFamily = Type.mono, fontSize = 11.5.sp, lineHeight = 1.65.em)
        Column(Modifier.padding(top = 10.dp).fillMaxWidth().border(1.dp, t.line, RoundedCornerShape(4.dp)).background(t.tint, RoundedCornerShape(4.dp))) {
            Row(Modifier.fillMaxWidth().height(32.dp).bottomLine(t.line).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("dropout.py", style = TextStyle(fontSize = 11.sp, fontFamily = Type.mono), color = t.muted)
                Box(Modifier.background(t.fg, RoundedCornerShape(2.dp)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("Run", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = t.bg)
                }
            }
            val kw = SpanStyle(color = t.hlKeyword, fontWeight = FontWeight.Medium)
            val num = SpanStyle(color = t.hlNumber)
            Text(
                buildAnnotatedString {
                    append("acc = {"); withStyle(num) { append("0.1") }; append(": ["); withStyle(num) { append("91.8") }; append(", ")
                    withStyle(num) { append("92.4") }; append(", "); withStyle(num) { append("92.1") }; append("],\n")
                    append("       "); withStyle(num) { append("0.3") }; append(": ["); withStyle(num) { append("90.9") }; append(", ")
                    withStyle(num) { append("90.7") }; append(", "); withStyle(num) { append("91.1") }; append("]}\n")
                    withStyle(kw) { append("for") }; append(" p, r "); withStyle(kw) { append("in") }; append(" acc.items():\n")
                    append("    print(p, round(sum(r) / len(r), "); withStyle(num) { append("1") }; append("))")
                },
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), style = code, color = t.fg, softWrap = false,
            )
            Text("0.1 92.1\n0.3 90.9", Modifier.fillMaxWidth().topLine(t.line).padding(horizontal = 10.dp, vertical = 8.dp), style = code, color = t.fg)
        }
        Text("0.1 wins by 1.2 pts, keep it.", Modifier.padding(top = 10.dp), style = TextStyle(fontSize = 13.sp, lineHeight = 1.375.em), color = t.fg)
    }
}

@Composable
fun LandingScreen(onSignUp: () -> Unit, onSignIn: () -> Unit) {
    val uri = LocalUriHandler.current
    val minHeight = LocalConfiguration.current.screenHeightDp.dp
    PageColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), maxWidth = 440.dp) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) { Logo() }
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp).height(androidx.compose.ui.unit.max(minHeight - 200.dp, 560.dp)), verticalArrangement = Arrangement.Center) {
            Text(APP_DESCRIPTION, style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.15.em, letterSpacing = (-0.025).em), color = t.fg)
            Text(
                "Write notes, keep snippets, organise them in projects, and run Python and JavaScript right on your phone.",
                Modifier.padding(top = 12.dp), style = TextStyle(fontSize = 15.sp, lineHeight = 1.625.em), color = t.muted,
            )
            Gap(24.dp)
            EntryPreview()
            Gap(24.dp)
            FilledBtn("Create account", onSignUp, Modifier.fillMaxWidth())
            TextBtn("Sign in", onSignIn, Modifier.fillMaxWidth(), strong = true, center = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
            TextBtn("Privacy", { uri.openUri(BuildConfig.WEB_URL + "/privacy") }, style = Type.xs)
            TextBtn("Terms", { uri.openUri(BuildConfig.WEB_URL + "/terms") }, style = Type.xs)
        }
    }
}

private const val UNCONFIRMED = "Confirm your email first. Open the link we sent you."
private const val WRONG = "Wrong email, username or password."

@Composable
fun LoginScreen(startInSignUp: Boolean, onHome: () -> Unit) {
    val scope = rememberCoroutineScope()
    var signin by rememberSaveable { mutableStateOf(!startInSignUp) }
    var identifier by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun fail(message: String) { loading = false; error = message }

    fun submit() {
        error = null
        sent = null
        if (pass.length < 8) return run { error = "Password needs at least 8 characters." }
        loading = true
        scope.launch {
            try {
                if (signin) {
                    val id = identifier.trim()
                    val address = if (looksLikeEmail(id)) id else Accounts.emailForUsername(normalizeUsername(id), pass) ?: return@launch fail(WRONG)
                    when (val r = Accounts.signIn(address, pass)) {
                        Accounts.SignIn.Ok -> Unit // the session change takes the app in
                        Accounts.SignIn.Unconfirmed -> fail(UNCONFIRMED)
                        Accounts.SignIn.Wrong -> fail(WRONG)
                        is Accounts.SignIn.Failed -> fail(r.message)
                    }
                } else {
                    val name = normalizeUsername(username)
                    if (!isValidUsername(name)) return@launch fail("Username: $USERNAME_HINT")
                    val em = identifier.trim()
                    if (!isValidEmail(em)) return@launch fail("Enter a valid email.")
                    if (!Accounts.usernameAvailable(name)) return@launch fail("That username is taken.")
                    if (Accounts.signUp(em, pass, name)) {
                        loading = false
                        signin = true
                        sent = em
                    }
                }
            } catch (e: Exception) {
                fail(Accounts.message(e))
            }
        }
    }

    fun resend() {
        val em = identifier.trim()
        if (!isValidEmail(em)) return run { error = "Enter your email to resend the link." }
        loading = true
        scope.launch {
            try {
                Accounts.resend(em)
                loading = false
                error = null
                sent = em
            } catch (e: Exception) {
                fail(Accounts.message(e))
            }
        }
    }

    val canResend = sent != null || error == UNCONFIRMED
    val noCaps = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    PageColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), maxWidth = 440.dp) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) { Logo(onClick = onHome) }
        Gap((LocalConfiguration.current.screenHeightDp * 0.08f).dp + 32.dp)
        Text(if (signin) "Sign in" else "Create account", Modifier.padding(bottom = 24.dp), style = Type.h1, color = t.fg)
        GoogleButton(enabled = !loading, onError = { error = it })
        Text("or", Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), style = Type.xs.copy(textAlign = TextAlign.Center), color = t.faint)
        if (!signin) UnderlineField(username, { username = it }, "Username", keyboard = noCaps)
        UnderlineField(
            identifier, { identifier = it }, if (signin) "Email or username" else "Email",
            keyboard = noCaps.copy(keyboardType = KeyboardType.Email),
        )
        UnderlineField(pass, { pass = it }, if (signin) "Password" else "Password (8+ characters)", password = true,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        error?.let { Text(it, Modifier.padding(top = 16.dp), style = Type.sm, color = t.fg) }
        sent?.let {
            Text("We sent a link to $it. Open it to confirm, then you're in. Check spam if it's not there.", Modifier.padding(top = 16.dp), style = Type.sm, color = t.fg)
        }
        Gap(32.dp)
        FilledBtn(if (signin) "Sign in" else "Create account", ::submit, Modifier.fillMaxWidth(), enabled = !loading)
        TextBtn(
            if (signin) "Create an account" else "I have an account",
            { signin = !signin; error = null }, Modifier.fillMaxWidth().padding(top = 4.dp), center = true,
        )
        if (signin && canResend) TextBtn("Resend confirmation email", ::resend, Modifier.fillMaxWidth(), enabled = !loading, center = true)
    }
}

/**
 * Continue with Google, drawn as the Sign in with Google button the web renders
 * (filled black in dark mode, outlined in light, Google's G). The session change
 * takes the app in; a new account goes to the username picker first.
 */
@Composable
private fun GoogleButton(enabled: Boolean, onError: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val dark = t.dark
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 400.dp).height(40.dp)
                .then(if (dark) Modifier.background(Color(0xFF131314), shape) else Modifier.background(Color.White, shape).border(1.dp, Color(0xFF747775), shape))
                .plainClick(enabled = enabled && !busy) {
                    busy = true
                    scope.launch {
                        when (val r = app.benchjot.data.GoogleSignIn.signIn(context)) {
                            is app.benchjot.data.GoogleSignIn.Result.Failed -> onError(r.message)
                            else -> Unit
                        }
                        busy = false
                    }
                }
                .semantics(mergeDescendants = true) { contentDescription = "Continue with Google" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.benchjot.R.drawable.ic_google_g), contentDescription = null, Modifier.size(18.dp))
            Text(
                "Continue with Google", Modifier.padding(start = 10.dp),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.25.sp),
                color = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F),
            )
        }
    }
}

@Composable
fun WelcomeScreen(suggestion: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(suggestion) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val wanted = normalizeUsername(name)
        if (!isValidUsername(wanted)) return run { error = USERNAME_HINT }
        saving = true
        error = null
        scope.launch {
            try {
                Accounts.setUsername(wanted)
                onDone()
            } catch (e: Exception) {
                error = Accounts.message(e)
            } finally {
                saving = false
            }
        }
    }

    PageColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), maxWidth = 440.dp) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) { Logo() }
        Gap((LocalConfiguration.current.screenHeightDp * 0.08f).dp + 32.dp)
        Text("Choose a username", Modifier.padding(bottom = 8.dp), style = Type.h1, color = t.fg)
        Text("$USERNAME_HINT You can sign in with it.", Modifier.padding(bottom = 24.dp), style = Type.sm, color = t.muted)
        UnderlineField(name, { name = it }, "Username", keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        error?.let { Text(it, Modifier.padding(top = 16.dp), style = Type.sm, color = t.fg) }
        Gap(32.dp)
        FilledBtn("Continue", ::save, Modifier.fillMaxWidth(), enabled = !saving)
    }
}

