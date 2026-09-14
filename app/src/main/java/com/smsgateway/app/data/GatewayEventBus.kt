package com.smsgateway.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object GatewayEventBus {

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    fun updateConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun addOrUpdateLog(entry: LogEntry) {
        val currentList = _logs.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.taskId == entry.taskId }
        if (existingIndex >= 0) {
            currentList[existingIndex] = entry
        } else {
            currentList.add(0, entry) // prepend newest
            if (currentList.size > 200) {
                currentList.removeAt(currentList.lastIndex)
            }
        }
        _logs.value = currentList
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    suspend fun emitToast(message: String) {
        _toastEvents.emit(message)
    }
}
