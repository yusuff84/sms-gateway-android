package com.smsgateway.app.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.smsgateway.app.data.PreferencesManager
import java.util.concurrent.TimeUnit

class GatewayWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        Log.d("GatewayWatchdog", "Watchdog checking service state (enabled: ${prefs.isServiceEnabled}, running: ${SmsGatewayService.isRunning})")

        if (prefs.isServiceEnabled && !SmsGatewayService.isRunning) {
            Log.w("GatewayWatchdog", "Watchdog detected service died! Restarting SmsGatewayService now...")
            SmsGatewayService.start(applicationContext)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "SMSGatewayWatchdogWork"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<GatewayWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i("GatewayWatchdog", "Scheduled 15-min periodic watchdog worker.")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i("GatewayWatchdog", "Cancelled watchdog worker.")
        }
    }
}
