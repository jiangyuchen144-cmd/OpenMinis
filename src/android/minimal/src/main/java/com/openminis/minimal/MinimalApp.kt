package com.openminis.minimal

// [T-minimal] Where the three singletons live.
//
// A hand-rolled holder rather than Hilt: the app has exactly three objects with
// no scoping beyond "one per process", and Hilt would add a compiler, a set of
// annotations and about forty files' worth of indirection to produce the same
// three objects.

import android.app.Application
import android.content.Context
import com.openminis.minimal.data.DeepSeekClient
import com.openminis.minimal.data.SessionStore
import com.openminis.minimal.data.Settings

class MinimalApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var sessions: SessionStore
        private set
    lateinit var client: DeepSeekClient
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        sessions = SessionStore(this)
        client = DeepSeekClient()
    }
}

/** Reaches the holder from a composable without threading it through every call. */
val Context.minimalApp: MinimalApp
    get() = applicationContext as MinimalApp
