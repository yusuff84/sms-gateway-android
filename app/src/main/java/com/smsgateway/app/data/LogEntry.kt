package com.smsgateway.app.data

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class LogEntry(
    val taskId: String,
    val phoneNumber: String,
    val message: String,
    var status: String,
    val timestamp: String,
    val simSlot: Int = 1,
    val error: String? = null
)
