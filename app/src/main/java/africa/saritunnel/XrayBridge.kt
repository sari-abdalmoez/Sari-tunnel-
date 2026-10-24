package africa.saritunnel

import android.net.VpnService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Small reflection adapter so Kotlin code does not depend on generated gomobile package spelling. */
object XrayBridge {
    private val classNames = listOf("xraybridge.Xraybridge", "go.xraybridge.Xraybridge", "xraybridge.XrayBridge", "go.xraybridge.XrayBridge")

    private fun bridgeClass(): Class<*> {
        classNames.forEach { name -> try { return Class.forName(name) } catch (_: ClassNotFoundException) { } }
        throw IllegalStateException("Xray native library is missing. Build app/libs/xray.aar first.")
    }

    fun start(service: VpnService, config: String, tunFd: Int, assetDir: String) {
        registerProtector(service)
        val clazz = bridgeClass()
        val method = clazz.methods.firstOrNull {
            it.name.equals("StartXray", true) && it.parameterTypes.size == 3 && it.parameterTypes[0] == String::class.java
        } ?: throw IllegalStateException("StartXray is not present in the native bridge.")
        val fdType = method.parameterTypes[1]
        val fd: Any = when (fdType) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> tunFd
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> tunFd.toLong()
            else -> throw IllegalStateException("The Xray bridge returned an unsupported TUN file descriptor type.")
        }
        val result = method.invoke(null, config, fd, assetDir) as? String
        if (!result.isNullOrBlank()) throw IllegalStateException(result)
    }

    fun stop() {
        val clazz = bridgeClass()
        val method = clazz.methods.firstOrNull { it.name.equals("StopXray", true) && it.parameterTypes.isEmpty() }
            ?: throw IllegalStateException("StopXray is not present in the native bridge.")
        val result = method.invoke(null) as? String
        if (!result.isNullOrBlank()) throw IllegalStateException(result)
    }

    private fun registerProtector(service: VpnService) {
        val clazz = bridgeClass()
        val method = clazz.methods.firstOrNull { it.name.equals("RegisterProtector", true) && it.parameterTypes.size == 1 }
            ?: throw IllegalStateException("RegisterProtector is not present in the native bridge.")
        val api = method.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(api.classLoader, arrayOf(api), InvocationHandler { obj, called, args ->
            when (called.name) {
                "equals" -> obj === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(obj)
                "toString" -> "SariVpnSocketProtector"
                else -> {
                    val fd = (args?.getOrNull(0) as? Number)?.toInt()
                        ?: throw IllegalStateException("Unexpected socket-protector callback")
                    service.protect(fd)
                }
            }
        })
        val result = method.invoke(null, proxy) as? String
        if (!result.isNullOrBlank()) throw IllegalStateException(result)
    }
}
