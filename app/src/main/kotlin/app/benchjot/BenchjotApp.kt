package app.benchjot

import android.app.Application
import app.benchjot.data.Backend
import app.benchjot.ui.ThemePref

class BenchjotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePref.load(this)
        Backend.client // create the client early so the saved session loads while the first frame draws
    }
}
