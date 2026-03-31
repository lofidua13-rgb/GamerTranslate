package com.gamertranslate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Auto-start floating service on device boot (optional)
        // Only starts if user had it running before
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_start_on_boot", false)) {
            val serviceIntent = Intent(context, FloatingService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
