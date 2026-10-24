package africa.saritunnel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback

/**
 * Thin client for the documented OpenVPN for Android external API. The OpenVPN engine runs in
 * that companion app; first use requires its external-API approval and Android VPN consent.
 */
class OpenVpnClient(
    private val activity: Activity,
    private val launchConsent: (Intent) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onStarted: () -> Unit,
    private val onError: (String) -> Unit
) {
    private enum class ConsentStep { NONE, API_ACCESS, VPN_PERMISSION }

    private var api: IOpenVPNAPIService? = null
    private var bindRequested = false
    private var consentStep = ConsentStep.NONE
    private var pendingConfig: String? = null
    private var callbackRegistered = false

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            val safeState = state.orEmpty()
            val safeMessage = message.orEmpty()
            activity.runOnUiThread { onStatus(safeState, safeMessage) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            api = IOpenVPNAPIService.Stub.asInterface(binder)
            if (pendingConfig != null) requestApiAccess()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            callbackRegistered = false
            onStatus("DISCONNECTED", "OpenVPN for Android service disconnected.")
        }
    }

    fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        val installedPackage = activity.packageManager.getPackageInfo(OPENVPN_PACKAGE, 0)
        installedPackage.packageName.isNotBlank()
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun connect(config: String) {
        if (!isInstalled()) {
            onError("OpenVPN for Android is not installed. Install it to provide the OpenVPN engine.")
            return
        }
        if (config.isBlank()) {
            onError("The OpenVPN profile is empty.")
            return
        }
        pendingConfig = config
        if (api != null) {
            requestApiAccess()
            return
        }
        if (bindRequested) return
        val intent = Intent(API_SERVICE_ACTION).setPackage(OPENVPN_PACKAGE)
        bindRequested = try {
            activity.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
        if (!bindRequested) onError("Could not reach OpenVPN for Android. Open or reinstall the companion app and try again.")
    }

    fun onPermissionResult(granted: Boolean) {
        val step = consentStep
        consentStep = ConsentStep.NONE
        if (!granted) {
            pendingConfig = null
            onError(if (step == ConsentStep.API_ACCESS) {
                "Sari VPN was not granted access by OpenVPN for Android."
            } else {
                "Android VPN permission was not granted."
            })
            return
        }
        when (step) {
            ConsentStep.API_ACCESS -> requestApiAccess()
            ConsentStep.VPN_PERMISSION -> startPendingConfig()
            ConsentStep.NONE -> Unit
        }
    }

    fun disconnect() {
        pendingConfig = null
        try {
            api?.disconnect()
        } catch (e: Exception) {
            onError("Could not send the disconnect command to OpenVPN.")
        }
    }

    fun close() {
        try {
            if (callbackRegistered) api?.unregisterStatusCallback(statusCallback)
        } catch (_: Exception) {
        }
        callbackRegistered = false
        if (bindRequested) {
            try { activity.unbindService(serviceConnection) } catch (_: Exception) { }
        }
        bindRequested = false
        api = null
    }

    private fun requestApiAccess() {
        val service = api ?: return
        try {
            val intent = service.prepare(activity.packageName)
            if (intent != null) {
                consentStep = ConsentStep.API_ACCESS
                launchConsent(intent)
                return
            }
            if (!callbackRegistered) {
                service.registerStatusCallback(statusCallback)
                callbackRegistered = true
            }
            requestVpnPermission()
        } catch (e: RemoteException) {
            onError("OpenVPN for Android rejected the API request: ${e.message ?: "remote service error"}")
        } catch (e: SecurityException) {
            onError("Allow Sari VPN in OpenVPN for Android's external API settings, then reconnect.")
        } catch (e: Exception) {
            onError("Could not prepare OpenVPN: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun requestVpnPermission() {
        val service = api ?: return
        try {
            val intent = service.prepareVPNService()
            if (intent != null) {
                consentStep = ConsentStep.VPN_PERMISSION
                launchConsent(intent)
            } else {
                startPendingConfig()
            }
        } catch (e: Exception) {
            onError("Could not request Android VPN permission: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startPendingConfig() {
        val service = api ?: return
        val config = pendingConfig ?: return
        try {
            service.startVPN(config)
            pendingConfig = null
            activity.runOnUiThread { onStarted() }
        } catch (e: Exception) {
            pendingConfig = null
            onError("OpenVPN could not start this profile: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
        private const val API_SERVICE_ACTION = "de.blinkt.openvpn.api.IOpenVPNAPIService"
    }
}
