package app.benchjot.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.benchjot.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import java.security.MessageDigest
import java.util.UUID

/**
 * Continue with Google, as the web's google-button.tsx: Google issues an ID token
 * for the web OAuth client (Android's Credential Manager, "Sign in with Google";
 * the app is allowed to ask through its own Android OAuth client, matched by
 * package name and signing key), and Supabase signs in with it. The nonce goes
 * to Google hashed and to Supabase raw. A new account has no username yet, so the
 * app shows the username picker next, like the web's /welcome.
 */
object GoogleSignIn {
    sealed interface Result {
        data object Ok : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    private const val DIDNT_FINISH = "Google sign-in didn't finish. Try again."
    private const val NO_ACCOUNT = "No Google account on this phone. Add one in Settings, or use email."

    private fun sha256Hex(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** `activity` must be an Activity context: Credential Manager shows its sheet over it. */
    suspend fun signIn(activity: Context): Result {
        val nonce = UUID.randomUUID().toString()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).setNonce(sha256Hex(nonce)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return Result.Cancelled
        } catch (_: NoCredentialException) {
            return Result.Failed(NO_ACCOUNT)
        } catch (e: GetCredentialException) {
            android.util.Log.w("Benchjot", "Google credential failed", e)
            return Result.Failed(DIDNT_FINISH)
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return Result.Failed(DIDNT_FINISH)
        }
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return try {
            Backend.client.auth.signInWith(IDToken) {
                idToken = token
                provider = Google
                this.nonce = nonce
            }
            Result.Ok
        } catch (e: Exception) {
            Result.Failed(Accounts.message(e, "sign_in"))
        }
    }
}
