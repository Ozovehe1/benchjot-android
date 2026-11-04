package app.benchjot.data

import app.benchjot.BuildConfig
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Sign-in, sign-up and usernames, as the web's login page: sign in by email or
// @username (login_email resolves a username only with the right password),
// sign up with a username, confirm by the emailed link (opened in the browser;
// the web's /auth/confirm finishes it), then sign in here.

object Accounts {
    private val client get() = Backend.client

    /** Where the confirmation link sends people: the web app's confirm route. */
    private val confirmUrl get() = "${BuildConfig.WEB_URL}/auth/confirm?next=%2F"

    sealed interface SignIn {
        data object Ok : SignIn
        data object Unconfirmed : SignIn
        data object Wrong : SignIn
        data class Failed(val message: String) : SignIn
    }

    /** The account email for a username, or null when the username/password pair is wrong. */
    suspend fun emailForUsername(username: String, password: String): String? {
        val res = client.postgrest.rpc("login_email", buildJsonObject { put("username", username); put("password", password) })
        val v = Backend.json.parseToJsonElement(res.data)
        return if (v is JsonPrimitive && v !is JsonNull) v.content.ifEmpty { null } else null
    }

    suspend fun signIn(email: String, password: String): SignIn = try {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        SignIn.Ok
    } catch (e: RestException) {
        val text = "${e.error} ${e.description.orEmpty()} ${e.message.orEmpty()}"
        when {
            text.contains("not_confirmed", true) || text.contains("not confirmed", true) -> SignIn.Unconfirmed
            text.contains("invalid_credentials", true) || text.contains("Invalid login credentials", true) -> SignIn.Wrong
            else -> SignIn.Failed(message(e))
        }
    }

    suspend fun usernameAvailable(name: String): Boolean {
        val res = client.postgrest.rpc("username_available", buildJsonObject { put("name", name) })
        return res.data.trim() == "true"
    }

    /** True when a confirmation email went out (no session yet); false when signed straight in. */
    suspend fun signUp(email: String, password: String, username: String): Boolean {
        client.auth.signUpWith(Email, redirectUrl = confirmUrl) {
            this.email = email
            this.password = password
            data = buildJsonObject { put("username", username) }
        }
        return client.auth.currentSessionOrNull() == null
    }

    suspend fun resend(email: String) {
        client.auth.resendEmail(OtpType.Email.SIGNUP, email)
    }

    suspend fun setUsername(name: String) = Backend.setUsername(name)

    suspend fun signOut() = client.auth.signOut()

    /** A short message for the user from any failure. */
    fun message(e: Throwable): String = when (e) {
        is RestException -> e.description?.takeIf { it.isNotBlank() } ?: e.error
        is java.io.IOException -> "No connection. Check your internet and try again."
        else -> e.message ?: "Something went wrong."
    }
}
