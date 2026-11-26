package app.benchjot

import android.app.Application
import android.os.Build
import app.benchjot.data.Backend
import app.benchjot.ui.ThemePref
import java.io.File

class BenchjotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ":python" process (PythonService) only runs code: no UI, no Supabase. Android
        // runs onCreate in every process, so app setup is limited to the main one.
        if (isWorkerProcess(processName())) return
        ThemePref.load(this)
        app.benchjot.data.OutputStore.init(cacheDir)
        app.benchjot.data.Offline.init(this)
        app.benchjot.data.PendingOutputs.init(this)
        app.benchjot.data.NoteFolder.init(this)
        app.benchjot.data.AiModel.load(this)
        Backend.client // create the client early so the saved session loads while the first frame draws
        app.benchjot.data.InviteAlerts.schedule(this) // notes shared with you: checked about every 15 minutes
        Thread { forgetOldFiles() }.start()
    }

    /** Files earlier versions left: the on-phone AI model (about 563 MB, and any download of it still queued) and settings under old names. */
    private fun forgetOldFiles() {
        val prefs = getSharedPreferences("on_device_model", MODE_PRIVATE)
        prefs.getLong("download_id", -1).takeIf { it >= 0 }?.let { id ->
            runCatching { (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).remove(id) }
        }
        listOfNotNull(getExternalFilesDir(null), filesDir).forEach { File(it, "models").deleteRecursively() }
        // Left by earlier versions under their old names: the model's and the Assistant's ("helper") settings, and its history copy.
        for (name in listOf("on_device_model", "helper")) deleteSharedPreferences(name)
        File(filesDir, "helper-history").deleteRecursively()
    }

    companion object {
        private val WORKER_SUFFIXES = listOf(":python")

        fun isWorkerProcess(name: String): Boolean = WORKER_SUFFIXES.any { name.endsWith(it) }

        /** API 28+ asks Android; API 26 and 27 read the kernel's record of this process. */
        private fun processName(): String =
            if (Build.VERSION.SDK_INT >= 28) getProcessName()
            else runCatching {
                File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            }.getOrDefault("")
    }
}
