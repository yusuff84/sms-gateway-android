package com.smsgateway.app.sms

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SmsSender {

    private const val TAG = "SmsSender"
    private const val ACTION_SMS_SENT_PREFIX = "com.smsgateway.app.SMS_SENT_"
    private const val ACTION_SMS_DELIVERED_PREFIX = "com.smsgateway.app.SMS_DELIVERED_"
    private const val TIMEOUT_MS = 180_000L // 3 minutes timeout to free receivers

    fun sendSms(
        context: Context,
        taskId: String,
        phoneNumber: String,
        messageText: String,
        simSlot: Int = 0, // 0 = default, 1 = SIM 1, 2 = SIM 2
        onStatusUpdate: (taskId: String, status: String, error: String?) -> Unit
    ) {
        val appContext = context.applicationContext

        val smsManager = resolveSmsManager(appContext, simSlot)

        val parts: ArrayList<String> = try {
            smsManager.divideMessage(messageText)
        } catch (e: Exception) {
            val list = ArrayList<String>()
            list.add(messageText)
            list
        }
        val totalParts = parts.size

        val sentAction = ACTION_SMS_SENT_PREFIX + taskId
        val deliveredAction = ACTION_SMS_DELIVERED_PREFIX + taskId

        val sentPartsCount = AtomicInteger(0)
        val deliveredPartsCount = AtomicInteger(0)
        val hasFailed = AtomicBoolean(false)

        lateinit var sentReceiver: BroadcastReceiver
        lateinit var deliveredReceiver: BroadcastReceiver

        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Log.w(TAG, "Task $taskId receiver cleanup timeout reached.")
            safeUnregister(appContext, sentReceiver)
            safeUnregister(appContext, deliveredReceiver)
        }

        sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val resultCode = resultCode
                Log.d(TAG, "Sent receiver for task $taskId part triggered (code: $resultCode)")

                if (resultCode == Activity.RESULT_OK) {
                    val count = sentPartsCount.incrementAndGet()
                    if (count == totalParts && !hasFailed.get()) {
                        Log.i(TAG, "All $totalParts parts sent successfully for task $taskId")
                        onStatusUpdate(taskId, "SENT", null)
                    }
                } else {
                    if (hasFailed.compareAndSet(false, true)) {
                        val errorDesc = getSmsErrorMessage(resultCode)
                        Log.e(TAG, "SMS part send failed for task $taskId: $errorDesc")
                        onStatusUpdate(taskId, "FAILED", errorDesc)
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        safeUnregister(appContext, this)
                        safeUnregister(appContext, deliveredReceiver)
                    }
                }
            }
        }

        deliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val resultCode = resultCode
                Log.d(TAG, "Delivered receiver for task $taskId part triggered (code: $resultCode)")

                if (resultCode == Activity.RESULT_OK) {
                    val count = deliveredPartsCount.incrementAndGet()
                    if (count == totalParts && !hasFailed.get()) {
                        Log.i(TAG, "All $totalParts parts delivered for task $taskId")
                        onStatusUpdate(taskId, "DELIVERED", null)
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        safeUnregister(appContext, this)
                        safeUnregister(appContext, sentReceiver)
                    }
                } else {
                    if (hasFailed.compareAndSet(false, true)) {
                        val errorMsg = "Carrier reported delivery failure (code: $resultCode)"
                        onStatusUpdate(taskId, "FAILED", errorMsg)
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        safeUnregister(appContext, this)
                        safeUnregister(appContext, sentReceiver)
                    }
                }
            }
        }

        // Register receivers - MUST be RECEIVER_EXPORTED so system telephony process can deliver callback
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }

        appContext.registerReceiver(sentReceiver, IntentFilter(sentAction), receiverFlags)
        appContext.registerReceiver(deliveredReceiver, IntentFilter(deliveredAction), receiverFlags)

        // Schedule timeout cleanup
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        try {
            if (totalParts > 1) {
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()

                for (i in 0 until totalParts) {
                    val partSentIntent = Intent(sentAction).apply {
                        setPackage(appContext.packageName)
                        putExtra("part_index", i)
                    }
                    val partDeliveredIntent = Intent(deliveredAction).apply {
                        setPackage(appContext.packageName)
                        putExtra("part_index", i)
                    }

                    sentIntents.add(PendingIntent.getBroadcast(appContext, taskId.hashCode() + i * 10, partSentIntent, pendingFlags))
                    deliveredIntents.add(PendingIntent.getBroadcast(appContext, taskId.hashCode() + i * 10 + 1, partDeliveredIntent, pendingFlags))
                }

                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveredIntents)
            } else {
                val sentIntent = PendingIntent.getBroadcast(
                    appContext,
                    taskId.hashCode(),
                    Intent(sentAction).apply { setPackage(appContext.packageName) },
                    pendingFlags
                )
                val deliveredIntent = PendingIntent.getBroadcast(
                    appContext,
                    taskId.hashCode() + 1,
                    Intent(deliveredAction).apply { setPackage(appContext.packageName) },
                    pendingFlags
                )
                smsManager.sendTextMessage(phoneNumber, null, messageText, sentIntent, deliveredIntent)
            }
            Log.i(TAG, "Dispatched SMS ($totalParts part(s)) to $phoneNumber for task $taskId")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during SmsManager dispatch: ${e.message}", e)
            timeoutHandler.removeCallbacks(timeoutRunnable)
            onStatusUpdate(taskId, "FAILED", e.localizedMessage ?: "SmsManager exception")
            safeUnregister(appContext, sentReceiver)
            safeUnregister(appContext, deliveredReceiver)
        }
    }

    private var lastRotatedSlotIndex = 0

    @SuppressLint("MissingPermission")
    private fun resolveSmsManager(context: Context, simSlot: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subList = subManager?.activeSubscriptionInfoList
                if (!subList.isNullOrEmpty()) {
                    val targetSlot = if (simSlot in 1..2) {
                        simSlot - 1 // 0-based
                    } else {
                        // Auto-rotate between active SIMs
                        lastRotatedSlotIndex = (lastRotatedSlotIndex + 1) % subList.size
                        subList[lastRotatedSlotIndex].simSlotIndex
                    }

                    val targetInfo = subList.find { it.simSlotIndex == targetSlot } ?: subList.first()
                    Log.i(TAG, "Routing via SIM ${targetInfo.simSlotIndex + 1} (${targetInfo.carrierName}, subId: ${targetInfo.subscriptionId})")
                    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val sm = context.getSystemService(SmsManager::class.java)
                        sm.createForSubscriptionId(targetInfo.subscriptionId)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getSmsManagerForSubscriptionId(targetInfo.subscriptionId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve specific SIM slot $simSlot, using default: ${e.message}")
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun getSmsErrorMessage(resultCode: Int): String {
        return when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic carrier failure (balance, limit or network issue)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular coverage or SIM card disabled"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU data returned by modem"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Cellular radio is turned off (Airplane mode)"
            else -> "Carrier failure code: $resultCode"
        }
    }

    private fun safeUnregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
