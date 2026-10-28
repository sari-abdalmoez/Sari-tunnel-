package africa.saritunnel

import africa.saritunnel.databinding.ItemCountryBinding
import africa.saritunnel.databinding.ItemServerBinding
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

enum class SortMode { FASTEST, BEST, COUNTRY }

sealed class ListItem {
    object Auto : ListItem()
    data class Header(val code: String, val country: String, val count: Int, val bestPing: Int, val collapsed: Boolean) : ListItem()
    data class Row(val server: VlessServer) : ListItem()
}

/** Builds the grouped list. Ranking uses measured data only; untested servers are never ranked as fast. */
object ServerSorter {
    private const val UNTESTED = 1_000_000L
    private const val UNREACHABLE = 2_000_000L

    /** Lower is better. Latency, adjusted by the outcome of real connection attempts. */
    fun fastestKey(s: VlessServer): Long = when (s.reachable) {
        true -> s.pingMs.toLong()
        null -> UNTESTED
        false -> UNREACHABLE
    }

    fun bestKey(s: VlessServer): Long = when (s.reachable) {
        true -> s.pingMs.toLong() + s.failCount * 400L - minOf(s.okCount, 3) * 100L +
            (if (s.lastConnectOk == false) 500L else 0L)
        null -> UNTESTED
        false -> UNREACHABLE
    }

    fun key(s: VlessServer, mode: SortMode): Long = if (mode == SortMode.BEST) bestKey(s) else fastestKey(s)

    fun build(servers: List<VlessServer>, mode: SortMode, collapsed: Set<String>): List<ListItem> {
        val groups = servers.groupBy { it.countryCode }
        val ordered = when (mode) {
            SortMode.COUNTRY -> groups.entries.sortedWith(
                compareBy<Map.Entry<String, List<VlessServer>>>({ it.key.isEmpty() }, { CountryUtils.displayName(it.key) })
            )
            else -> groups.entries.sortedBy { e -> e.value.minOf { key(it, mode) } }
        }
        val out = ArrayList<ListItem>(servers.size + groups.size + 1)
        out.add(ListItem.Auto)
        for ((code, members) in ordered) {
            val sorted = members.sortedBy { key(it, if (mode == SortMode.COUNTRY) SortMode.FASTEST else mode) }
            val best = sorted.firstOrNull { it.reachable == true }?.pingMs ?: -1
            val isCollapsed = code in collapsed
            out.add(ListItem.Header(code, CountryUtils.displayName(code), members.size, best, isCollapsed))
            if (!isCollapsed) sorted.forEach { out.add(ListItem.Row(it)) }
        }
        return out
    }
}

class ServerAdapter(
    private val onSelect: (VlessServer?) -> Unit,
    private val onCopy: (VlessServer) -> Unit,
    private val onToggleCountry: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ListItem> = emptyList()
    private var selectedId: String? = null
    private var activeId: String? = null
    private var phase: TunnelState.Phase = TunnelState.Phase.IDLE
    private var autoSubtitle: String = ""

    fun submit(
        newItems: List<ListItem>, selectedId: String?, activeId: String?,
        phase: TunnelState.Phase, autoSubtitle: String
    ) {
        items = newItems
        this.selectedId = selectedId
        this.activeId = activeId
        this.phase = phase
        this.autoSubtitle = autoSubtitle
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ListItem.Header -> 0
        else -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderHolder(ItemCountryBinding.inflate(inflater, parent, false))
        else RowHolder(ItemServerBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderHolder).bind(item)
            is ListItem.Row -> (holder as RowHolder).bindServer(item.server)
            ListItem.Auto -> (holder as RowHolder).bindAuto()
        }
    }

    inner class HeaderHolder(private val v: ItemCountryBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(h: ListItem.Header) {
            v.flag.text = CountryUtils.flag(h.code)
            v.country.text = h.country
            val servers = if (h.count == 1) "1 server" else "${h.count} servers"
            v.summary.text = if (h.bestPing >= 0) "$servers · best ${h.bestPing} ms" else servers
            v.chevron.rotation = if (h.collapsed) -90f else 0f
            v.root.setOnClickListener { onToggleCountry(h.code) }
        }
    }

    inner class RowHolder(private val v: ItemServerBinding) : RecyclerView.ViewHolder(v.root) {
        private val ctx get() = v.root.context

        fun bindAuto() {
            v.flag.text = "\u26A1"
            v.name.text = "Auto · fastest tested"
            v.details.text = autoSubtitle
            v.ping.text = ""
            v.status.text = ""
            v.check.visibility = if (selectedId == null) View.VISIBLE else View.INVISIBLE
            v.root.setOnClickListener { onSelect(null) }
            v.root.setOnLongClickListener(null)
        }

        fun bindServer(s: VlessServer) {
            v.flag.text = CountryUtils.flag(s.countryCode)
            v.name.text = s.name
            v.details.text = listOf(s.country, s.transport).filter { it.isNotBlank() }.joinToString(" · ")

            val isActive = s.id == activeId
            when (s.reachable) {
                true -> {
                    v.ping.text = "${s.pingMs} ms"
                    v.ping.setTextColor(ContextCompat.getColor(ctx, pingColor(s.pingMs)))
                }
                false -> {
                    v.ping.text = "—"
                    v.ping.setTextColor(ContextCompat.getColor(ctx, R.color.sari_muted))
                }
                null -> {
                    v.ping.text = "—"
                    v.ping.setTextColor(ContextCompat.getColor(ctx, R.color.sari_muted))
                }
            }

            val (label, color) = when {
                isActive && phase == TunnelState.Phase.CONNECTED -> "Connected" to R.color.sari_blue
                isActive && (phase == TunnelState.Phase.CONNECTING || phase == TunnelState.Phase.VERIFYING) ->
                    "Connecting…" to R.color.sari_amber
                s.lastConnectOk == false -> "Last attempt failed" to R.color.sari_danger
                s.lastConnectOk == true && s.reachable == true -> "Verified before" to R.color.sari_green
                s.reachable == true -> "Reachable" to R.color.sari_green
                s.reachable == false -> "Unreachable" to R.color.sari_danger
                else -> "Untested" to R.color.sari_muted
            }
            v.status.text = label
            v.status.setTextColor(ContextCompat.getColor(ctx, color))

            v.check.visibility = if (s.id == selectedId) View.VISIBLE else View.INVISIBLE
            v.root.setOnClickListener { onSelect(s) }
            v.root.setOnLongClickListener { onCopy(s); true }
        }

        private fun pingColor(ms: Int) = when {
            ms < 150 -> R.color.sari_green
            ms < 350 -> R.color.sari_amber
            else -> R.color.sari_danger
        }
    }
}
