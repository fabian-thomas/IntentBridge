package de.fabianthomas.intentbridge

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

private const val TLS_PROTOCOL = "TLS"
private const val TAG = "IntentBridgeTls"

object TlsSocketHelper {
    fun connect(context: Context, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val sslSocket = clientContext(context).socketFactory.createSocket() as SSLSocket
        sslSocket.soTimeout = timeoutMs
        sslSocket.configureProtocols()
        sslSocket.connect(InetSocketAddress(host, port), timeoutMs)
        return sslSocket.handshake("Client")
    }

    fun wrapServerSocket(context: Context, socket: Socket): SSLSocket {
        val address = socket.inetAddress?.hostAddress ?: "127.0.0.1"
        val sslSocket = serverContext(context).socketFactory
            .createSocket(socket, address, socket.port, true) as SSLSocket
        sslSocket.useClientMode = false
        sslSocket.needClientAuth = true
        sslSocket.configureProtocols()
        return sslSocket.handshake("Server")
    }

    private fun clientContext(context: Context): SSLContext =
        SSLContext.getInstance(TLS_PROTOCOL).apply {
            init(
                TlsIdentityStore.keyManagers(context),
                TlsIdentityStore.trustManagers(context),
                null
            )
        }

    private fun serverContext(context: Context): SSLContext =
        SSLContext.getInstance(TLS_PROTOCOL).apply {
            init(
                TlsIdentityStore.keyManagers(context),
                TlsIdentityStore.trustManagers(context),
                null
            )
        }

    private fun SSLSocket.configureProtocols() {
        val latestTls = supportedProtocols
            .filter { it.startsWith("TLS") }
            .maxOrNull()

        if (latestTls != null) {
            enabledProtocols = arrayOf(latestTls)
        }
    }

    private fun SSLSocket.handshake(role: String): SSLSocket = apply {
        try {
            startHandshake()
        } catch (handshake: Exception) {
            runCatching { close() }
            Log.w(TAG, "$role TLS handshake failed", handshake)
            throw handshake
        }
    }
}
