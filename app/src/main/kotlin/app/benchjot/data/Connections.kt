package app.benchjot.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Connect Hugging Face from the phone, through the web's app hand-off
 * (benchjot lib/server/app-connect.ts): the app asks for a link bound to this user and a nonce of its
 * own, opens it in the browser to sign in and Allow, and comes back on benchjot://connected with a
 * sealed result, which it hands to the web to save. A result whose nonce isn't the one this app started
 * with is ignored, so no other page can connect an account of its choosing here.
 */
object Connections {
    enum class Provider(val id: String, val label: String) {
        Hf("hf", "Hugging Face");

        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    private const val PREFS = "connections"
    private val random = SecureRandom()

    /** What the last connect said, for Settings to show; null once read. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    /** Bumped when a connection changes, so Settings reloads its status. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun newNonce(): String {
        val b = ByteArray(24).also(random::nextBytes)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /** Starts connecting [provider]: gets the link and opens it in the browser. */
    suspend fun start(context: Context, provider: Provider) {
        val nonce = newNonce()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(provider.id, nonce).apply()
        try {
            val url = WebApi.connectLink(provider.id, nonce)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            _message.value = Accounts.message(e, "load")
        }
    }

    /** Whether [uri] is a connect's way back to the app. */
    fun isReturn(uri: Uri?): Boolean = uri?.scheme == "benchjot" && uri.host == "connected"

    /** Finishes a connect the app started when the browser returns on benchjot://connected. */
    fun handle(context: Context, uri: Uri) {
        val provider = Provider.of(uri.getQueryParameter("p")) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expected = prefs.getString(provider.id, null)
        val nonce = uri.getQueryParameter("n")
        if (expected == null || nonce != expected) return // not a connect this app started
        prefs.edit().remove(provider.id).apply()
        val error = uri.getQueryParameter("error")
        val r = uri.getQueryParameter("r")
        if (error != null || r == null) {
            _message.value = if (error == "cancelled") "Nothing was connected." else "Couldn’t finish connecting. Try again."
            return
        }
        Backend.scope.launch {
            _message.value = try {
                WebApi.connectFinish(r)
                _version.value++
                "Hugging Face connected. Server runs can now download the gated and private models and datasets your account can open."
            } catch (e: Exception) {
                Accounts.message(e, "load")
            }
        }
    }

    suspend fun disconnect(provider: Provider) {
        if (provider == Provider.Hf) Backend.disconnectHf()
        _version.value++
    }
}
