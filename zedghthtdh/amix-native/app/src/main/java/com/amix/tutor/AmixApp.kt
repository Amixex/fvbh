package com.amix.tutor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AmixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
