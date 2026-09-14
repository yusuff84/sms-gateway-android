package com.smsgateway.app.ws

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.smsgateway.app.data.ConnectionStatus
import com.smsgateway.app.data.GatewayEventBus
import com.smsgateway.app.data.LogEntry
import com.smsgateway.app.sms.SmsSender
import kotlinx.coroutines.*
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class GatewayWebSocketClient(
    private val context: Context,
    private val rawServerUrl: String,
    private val deviceToken: String
) {

    private val tag = "GatewayWSClient"
    private val gson = Gson()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for persistent WebSocket
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var isManuallyClosed = false
    private var reconnectAttempts = 0

    fun connect() {
        isManuallyClosed = false
        val wsUrl = buildWebSocketUrl(rawServerUrl, deviceToken)
        Log.i(tag, "Connecting to WebSocket: $wsUrl")

        GatewayEventBus.updateConnectionStatus(ConnectionStatus.CONNECTING)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(tag, "WebSocket connection established!")
                reconnectAttempts = 0
                GatewayEventBus.updateConnectionStatus(ConnectionStatus.CONNECTED)
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(tag, "Received message: $text")
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closing: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closed: $code / $reason")
                stopHeartbeat()
                GatewayEventBus.updateConnectionStatus(ConnectionStatus.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket error: ${t.message}", t)
                stopHeartbeat()
                GatewayEventBus.updateConnectionStatus(ConnectionStatus.ERROR)
                scheduleReconnect()
            }
        })
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val root = gson.fromJson(jsonText, JsonObject::class.java)
            val type = root.get("type")?.asString

            when (type) {
                "send_sms" -> {
                    val taskId = root.get("task_id")?.asString ?: return
                    val phone = root.get("phone_number")?.asString ?: return
                    val message = root.get("message")?.asString ?: return
                    val simSlot = root.get("sim_slot")?.asInt ?: 0

                    val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val logEntry = LogEntry(taskId, phone, message, "QUEUED", timeStamp, simSlot = simSlot)
                    GatewayEventBus.addOrUpdateLog(logEntry)

                    SmsSender.sendSms(
                        context = context,
                        taskId = taskId,
                        phoneNumber = phone,
                        messageText = message,
                        simSlot = simSlot
                    ) { tId, status, error ->
                        val updateEntry = LogEntry(tId, phone, message, status, timeStamp, simSlot = simSlot, error = error)
                        GatewayEventBus.addOrUpdateLog(updateEntry)
                        sendStatusUpdate(tId, status, error)
                    }
                }
                "heartbeat_ack" -> {
                    Log.d(tag, "Heartbeat acknowledged by server")
                }
                "pong" -> {
                    Log.d(tag, "Pong received")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: $jsonText", e)
        }
    }

    fun sendStatusUpdate(taskId: String, status: String, error: String? = null) {
        val payload = JsonObject().apply {
            addProperty("type", "status")
            addProperty("task_id", taskId)
            addProperty("status", status)
            if (error != null) {
                addProperty("error", error)
            }
        }
        val text = gson.toJson(payload)
        val sent = webSocket?.send(text) ?: false
        Log.i(tag, "Reported status for task $taskId: $status (sent: $sent)")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(25_000)
                sendHeartbeat()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeat() {
        val (battery, isCharging) = getBatteryInfo()
        val payload = JsonObject().apply {
            addProperty("type", "heartbeat")
            addProperty("battery_level", battery)
            addProperty("is_charging", isCharging)
        }
        webSocket?.send(gson.toJson(payload))
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    private fun scheduleReconnect() {
        if (isManuallyClosed) return
        reconnectAttempts++
        val delayMs = (1000L * (1 shl minOf(reconnectAttempts, 5))).coerceIn(1000L, 30_000L)
        Log.i(tag, "Scheduling reconnect attempt $reconnectAttempts in ${delayMs}ms...")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isManuallyClosed) {
                Log.i(tag, "Attempting to reconnect WebSocket...")
                connect()
            }
        }
    }

    fun onNetworkAvailable() {
        if (isManuallyClosed) return
        Log.i(tag, "Network became active. Triggering instant reconnect...")
        reconnectJob?.cancel()
        if (webSocket == null || GatewayEventBus.connectionStatus.value != ConnectionStatus.CONNECTED) {
            connect()
        }
    }

    fun disconnect() {
        isManuallyClosed = true
        reconnectJob?.cancel()
        stopHeartbeat()
        try {
            webSocket?.close(1000, "App service stopped")
        } catch (e: Exception) {
            Log.w(tag, "Error during close: ${e.message}")
        }
        webSocket = null
        GatewayEventBus.updateConnectionStatus(ConnectionStatus.DISCONNECTED)
    }

    private fun buildWebSocketUrl(rawUrl: String, token: String): String {
        var base = rawUrl.trim()
        if (base.startsWith("http://")) {
            base = "ws://" + base.removePrefix("http://")
        } else if (base.startsWith("https://")) {
            base = "wss://" + base.removePrefix("https://")
        } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "ws://$base"
        }

        while (base.endsWith("/")) {
            base = base.substring(0, base.length - 1)
        }

        return "$base/ws/device?token=$token"
    }
}
