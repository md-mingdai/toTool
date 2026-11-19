package com.mingdai.toTool.weaknet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WeakNetState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val error: String? = null,
    val port: Int? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxSpeed: Long = 0,
    val txSpeed: Long = 0,
    val connections: Int = 0,
    val lastHost: String = "",
    val config: WeakNetConfig = WeakNetConfig(),
)

object WeakNetManager {
    private val _state = MutableStateFlow(WeakNetState())
    val state: StateFlow<WeakNetState> = _state

    fun update(transform: (WeakNetState) -> WeakNetState) {
        _state.value = transform(_state.value)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
