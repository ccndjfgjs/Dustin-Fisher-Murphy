package dev.opencode.mobile

import android.app.Application
import dev.opencode.mobile.data.Prefs
import dev.opencode.mobile.data.SessionStore

class OpenCodeApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var sessions: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sessions = SessionStore(this)
    }
}
