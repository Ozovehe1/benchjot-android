package app.benchjot

import android.app.Application
import android.os.Build
import app.benchjot.data.Backend
import app.benchjot.ui.ThemePref
import java.io.File

class BenchjotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ":python" (PythonService) and ":llm" (LlmService) processes only run code:
        // no UI, no Supabase. Android runs onCreate in every process, so app setup is
        // limited to the main one. Building the Supabase client there crashes ":llm".
        if (isWorkerProcess(processName())) return
        ThemePref.load(this)
        app.benchjot.data.OutputStore.init(cacheDir)
        Backend.client // create the client early so the saved session loads while the first frame draws
    }

    companion object {
        private val WORKER_SUFFIXES = listOf(":python", ":llm")

        fun isWorkerProcess(name: String): Boolean = WORKER_SUFFIXES.any { name.endsWith(it) }

        /** API 28+ asks Android; API 26 and 27 read the kernel's record of this process. */
        private fun processName(): String =
            if (Build.VERSION.SDK_INT >= 28) getProcessName()
            else runCatching {
                File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            }.getOrDefault("")
    }
}
