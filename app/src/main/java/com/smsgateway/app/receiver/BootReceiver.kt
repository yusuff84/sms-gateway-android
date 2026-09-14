package com.smsgateway.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smsgateway.app.data.PreferencesManager
import com.smsgateway.app.service.GatewayWatchdogWorker
import com.smsgateway.app.service.SmsGatewayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BootReceiver", "Device reboot detected. Checking if service was enabled...")
            val prefs = PreferencesManager(context)
            if (prefs.isServiceEnabled) {
                Log.i("BootReceiver", "Auto-starting SMS Gateway Service after reboot...")
                GatewayWatchdogWorker.schedule(context)
                SmsGatewayService.start(context)
            }
        }
    }
}
