package com.smsgateway.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smsgateway.app.MainActivity
import com.smsgateway.app.R
import com.smsgateway.app.data.ConnectionStatus
import com.smsgateway.app.data.GatewayEventBus
import com.smsgateway.app.data.PreferencesManager
import com.smsgateway.app.ws.GatewayWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SmsGatewayService : Service() {

    private val tag = "SmsGatewayService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wsClient: GatewayWebSocketClient? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sms_gateway_channel"

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, SmsGatewayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SmsGatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                Log.i(tag, "Stopping SMS Gateway Service...")
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                Log.i(tag, "Starting SMS Gateway Service...")
                isRunning = true
                val prefs = PreferencesManager(this)
                prefs.isServiceEnabled = true

                val notification = buildNotification("SMS Gateway Active - Connecting...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                // Schedule watchdog worker
                GatewayWatchdogWorker.schedule(this)

                // Connect WebSocket
                wsClient?.disconnect()
                wsClient = GatewayWebSocketClient(
                    context = this,
                    rawServerUrl = prefs.serverUrl,
                    deviceToken = prefs.deviceToken
                )
                wsClient?.connect()

                // Observe connection status to update persistent notification
                serviceScope.launch {
                    GatewayEventBus.connectionStatus.collectLatest { status ->
                        val statusText = when (status) {
                            ConnectionStatus.CONNECTED -> "Status: Connected to Server"
                            ConnectionStatus.CONNECTING -> "Status: Connecting..."
                            ConnectionStatus.DISCONNECTED -> "Status: Disconnected (reconnecting)"
                            ConnectionStatus.ERROR -> "Status: Connection Error"
                        }
                        updateNotification(statusText)
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun stopServiceInternal() {
        isRunning = false
        val prefs = PreferencesManager(this)
        prefs.isServiceEnabled = false

        GatewayWatchdogWorker.cancel(this)
        wsClient?.disconnect()
        wsClient = null
        serviceScope.cancel()
        releaseWakeLock()
        unregisterNetworkCallback()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(tag, "Active internet connection detected by NetworkCallback.")
                    wsClient?.onNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    Log.w(tag, "Network connection lost.")
                }
            }
            networkCallback?.let {
                connectivityManager?.registerNetworkCallback(builder.build(), it)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(tag, "Error unregistering network callback: ${e.message}")
        }
        networkCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServiceInternal()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMSGateway:ServiceWakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(tag, "Error releasing wakelock: ${e.message}")
        }
        wakeLock = null
    }
}
