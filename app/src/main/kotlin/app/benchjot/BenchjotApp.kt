package app.benchjot

import android.app.Application
import app.benchjot.data.Backend
import app.benchjot.ui.ThemePref

class BenchjotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ":python" process (PythonService) only runs code: no UI, no Supabase.
        if (android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":python")) return
        ThemePref.load(this)
        app.benchjot.data.OutputStore.init(cacheDir)
        Backend.client // create the client early so the saved session loads while the first frame draws
    }
}
