package africa.saritunnel

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Bounded diagnostic log. Never pass keys, configs or tokens into this. */
object Diag {
    private const val MAX_LINES = 150
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<String>()
    val text = MutableStateFlow("")

    fun i(msg: String) = add("INFO", msg)
    fun w(msg: String) = add("WARN", msg)

    @Synchronized
    private fun add(level: String, msg: String) {
        buffer.addLast("${fmt.format(Date())} $level  ${msg.take(200)}")
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        text.value = buffer.joinToString("\n")
    }
}
