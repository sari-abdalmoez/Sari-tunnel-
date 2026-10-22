package africa.saritunnel

import com.wireguard.android.backend.GoBackend

/** VpnService entry point used by the WireGuard backend. Declared in the manifest with BIND_VPN_SERVICE. */
class TunnelVpnService : GoBackend.VpnService()
