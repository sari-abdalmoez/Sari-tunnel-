package africa.saritunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide tunnel state shared by [XrayVpnService] and the UI (both run in the same process). */
object TunnelState {
    enum class Phase { IDLE, CONNECTING, VERIFYING, CONNECTED, UNVERIFIED, DISCONNECTING, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val serverId: String? = null,
        val message: String = "",
        val connectedAtMs: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun update(
        phase: Phase,
        serverId: String? = _state.value.serverId,
        message: String = "",
        connectedAtMs: Long = 0L
    ) {
        _state.value = State(phase, serverId, message, connectedAtMs)
    }

    fun isActive(): Boolean = _state.value.phase.let {
        it == Phase.CONNECTING || it == Phase.VERIFYING || it == Phase.CONNECTED || it == Phase.UNVERIFIED
    }

    fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _log.value = (_log.value + "[$stamp] $message").takeLast(80)
    }
}
