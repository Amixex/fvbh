package com.amix.tutor.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Конфиг Firebase-проекта Amix (те же публичные значения, что и в веб-версии). */
object FirebaseSetup {
    const val PROJECT_ID = "amix-3e8a4"

    fun init(ctx: Context) {
        if (FirebaseApp.getApps(ctx).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApiKey("AIzaSyBssw0YzH7Q4lzcAxYlVCCPvuiqnmInAHk")
            .setApplicationId("1:275232802925:web:808b8e9c3c16eb8c331764")
            .setProjectId(PROJECT_ID)
            .setStorageBucket("amix-3e8a4.firebasestorage.app")
            .setGcmSenderId("275232802925")
            .build()
        FirebaseApp.initializeApp(ctx, options)
    }
}
