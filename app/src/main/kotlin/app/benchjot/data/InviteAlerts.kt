package app.benchjot.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

// Invitations on the phone: about every 15 minutes, when online, the app asks for notes shared with
// you that you haven't answered yet, and shows a notification for each new one ("@ada shared … with
// you"); a tap opens it. No Firebase or other push service: the phone checks by itself (Android
// decides the exact times, to save battery). Each invitation is told once.
object InviteAlerts {
    private const val CHANNEL = "invitations"
    private const val WORK = "invite-check"
    private const val SEEN = "invite-alerts"

    /** Starts the periodic check (once; later calls keep the existing schedule). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<InviteCheck>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Notes shared with you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When someone shares a note with you"
            })
        }
    }

    /** Notifies the invitations not told yet; remembers them so each is told once. */
    suspend fun check(context: Context) {
        // Started with the app closed, the saved login is still loading from storage: wait for it
        // (it was read as signed out, and the check skipped, so alerts came only while the app was open).
        val status = withTimeoutOrNull(15_000) {
            Backend.session.first { it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated || it is SessionStatus.RefreshFailure }
        }
        if (status !is SessionStatus.Authenticated && Backend.client.auth.currentSessionOrNull() == null) return
        // Without permission to show notifications, nothing is marked told: they show once it's allowed.
        if (!allowed(context)) return
        val pending = Shares.sharedWithMe().filter { it.pending }
        val prefs = context.getSharedPreferences(SEEN, Context.MODE_PRIVATE)
        val told = prefs.getStringSet("told", emptySet()).orEmpty()
        val fresh = pending.filter { it.noteId !in told }
        if (fresh.isNotEmpty()) {
            channel(context)
            fresh.forEach { n ->
                val open = PendingIntent.getActivity(
                    context, n.noteId.hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse(Shares.link(n.token))),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val note = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(app.benchjot.R.mipmap.ic_launcher)
                    .setContentTitle("${n.author?.let { "@$it" } ?: "Someone"} shared a note with you")
                    .setContentText(n.title.ifBlank { "Untitled" })
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                runCatching { NotificationManagerCompat.from(context).notify(n.noteId.hashCode(), note) }
            }
        }
        // Remember what's pending now (answered ones drop out, so the set stays small).
        prefs.edit().putStringSet("told", (told intersect pending.map { it.noteId }.toSet()) + fresh.map { it.noteId }).apply()
    }
}

class InviteCheck(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching { InviteAlerts.check(applicationContext) }.fold({ Result.success() }, { Result.retry() })
}
