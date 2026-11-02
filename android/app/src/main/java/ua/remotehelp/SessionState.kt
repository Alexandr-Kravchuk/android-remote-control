package ua.remotehelp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared state between [ScreenShareService] and [MainActivity]. */
object SessionState {
    enum class Phase { IDLE, CONNECTING, WAITING, CONNECTED, RECONNECTING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val code: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    fun set(state: State) {
        _state.value = state
    }
}
