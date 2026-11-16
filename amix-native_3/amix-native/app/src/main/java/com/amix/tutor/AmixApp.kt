package com.amix.tutor

import android.app.Application
import com.amix.tutor.auth.Backend
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AmixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Backend.configure(getString(R.string.supabase_url), getString(R.string.supabase_key))
    }
}
