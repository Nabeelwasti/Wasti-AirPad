package com.wasti.airpad

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Pinned-certificate TLS transport with heartbeat and bounded reconnect. */
internal class WifiRemoteClient(
    private val onState: (message: String, connected: Boolean) -> Unit,
    private val onPairingToken: (token: String) -> Unit = {}
) : Closeable {
    private class ConnectionConfig(
        val address: String,
        val port: Int,
        val pairingCode: String,
        val fingerprint: String,
        trustedToken: String?
    ) {
        @Volatile var trustedToken: String? = trustedToken
    }

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(512),
        { runnable -> Thread(runnable, "AirPad-WiFi").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AirPad-WiFi-scheduler").apply { isDaemon = true }
        }

    private val stateLock = Any()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile private var closed = false
    @Volatile private var connectionConfig: ConnectionConfig? = null
    @Volatile private var reconnectAttempts = 0
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null
    private var stableConnectionTask: ScheduledFuture<*>? = null

    /**
     * Gesture callbacks can arrive on the UI thread at high frequency. Never let
     * executor saturation escape into Android input dispatch: discard stale queued
     * movement and fail closed, then attempt a bounded reconnect.
     */
    private fun enqueue(action: () -> Unit) {
        if (closed) return
        try {
            executor.execute { action() }
        } catch (_: RejectedExecutionException) {
            if (!executor.isShutdown && !closed) {
                val wasConnected = isConnected
                executor.queue.clear()
                closeSocket()
                if (wasConnected) {
                    scheduleReconnect("Wi-Fi input queue saturated.")
                } else {
                    onState("Wi-Fi input queue is full. Reopen Wi-Fi connection to retry.", false)
                }
            }
        }
    }

    fun connect(
        address: String,
        port: Int,
        pairingCode: String,
        fingerprint: String,
        trustedToken: String? = null
    ) {
        val config = ConnectionConfig(address, port, pairingCode, fingerprint, trustedToken)
        synchronized(stateLock) {
            if (closed) return
            connectionConfig = config
            reconnectAttempts = 0
            stableConnectionTask?.cancel(false)
            stableConnectionTask = null
            reconnectTask?.cancel(false)
            reconnectTask = null
        }
        enqueue { connectInternal(config, reconnecting = false) }
    }

    private fun connectInternal(config: ConnectionConfig, reconnecting: Boolean) {
        closeSocket()
        try {
            require(config.address.isNotBlank()) { "Enter the computer's local IP address." }
            require(config.port in 1..65535) { "Port must be between 1 and 65535." }
            val resolvedAddress = InetAddress.getByName(config.address)
            require(
                resolvedAddress.isSiteLocalAddress ||
                    resolvedAddress.isLinkLocalAddress ||
                    resolvedAddress.isLoopbackAddress
            ) { "Use the computer's private local-network IP address, not a public internet address." }
            val validTrustedToken = config.trustedToken?.takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{43}"))
            }
            require(validTrustedToken != null || config.pairingCode.matches(Regex("[0-9]{8}"))) {
                "Enter the 8-digit pairing code for a new computer."
            }
            val expectedFingerprint = config.fingerprint
                .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                .lowercase()
            require(expectedFingerprint.length == 64) {
                "Enter the complete SHA-256 certificate fingerprint shown on the computer."
            }

            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val certificate = chain?.firstOrNull()
                        ?: throw CertificateException("The computer did not provide a certificate.")
                    try {
                        certificate.checkValidity()
                        val actual = MessageDigest.getInstance("SHA-256")
                            .digest(certificate.encoded)
                            .joinToString("") { "%02x".format(it) }
                        if (!MessageDigest.isEqual(
                                actual.toByteArray(Charsets.US_ASCII),
                                expectedFingerprint.toByteArray(Charsets.US_ASCII)
                            )
                        ) {
                            throw CertificateException("Certificate fingerprint does not match. Connection blocked.")
                        }
                    } catch (error: CertificateException) {
                        throw error
                    } catch (error: Exception) {
                        throw CertificateException("Could not verify the computer certificate.", error)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            val connectedSocket = context.socketFactory.createSocket() as SSLSocket
            val socketAccepted = synchronized(stateLock) {
                if (closed) false else {
                    socket = connectedSocket
                    true
                }
            }
            if (!socketAccepted) {
                connectedSocket.close()
                return
            }
            connectedSocket.soTimeout = CONNECT_TIMEOUT_MS
            connectedSocket.connect(InetSocketAddress(resolvedAddress, config.port), CONNECT_TIMEOUT_MS)
            connectedSocket.enabledProtocols = connectedSocket.enabledProtocols.filter {
                it == "TLSv1.3" || it == "TLSv1.2"
            }.toTypedArray()
            connectedSocket.startHandshake()

            val input = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
            val output = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
            val authMessage = if (validTrustedToken != null) {
                "{\"type\":\"auth\",\"token\":\"$validTrustedToken\"}"
            } else {
                "{\"type\":\"auth\",\"code\":\"${config.pairingCode}\"}"
            }
            output.write(authMessage)
            output.write("\n")
            output.flush()
            val response = input.readLine()
            val responseObject = try {
                response?.let { JSONObject(it) }
            } catch (_: Exception) {
                null
            }
            if (responseObject?.optString("type") != "ready") {
                connectedSocket.close()
                val message = if (validTrustedToken != null) {
                    "Saved phone trust was rejected. Forget this computer and pair again using its current code."
                } else {
                    "The computer rejected pairing. Check the code and try again."
                }
                throw SecurityException(message)
            }
            val newlyIssuedToken = responseObject.optString("token")
                .takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }

            val sessionAccepted = synchronized(stateLock) {
                if (closed || socket !== connectedSocket) false else {
                    writer = output
                    isConnected = true
                    true
                }
            }
            if (!sessionAccepted) {
                connectedSocket.close()
                return
            }

            if (newlyIssuedToken != null) {
                config.trustedToken = newlyIssuedToken
                onPairingToken(newlyIssuedToken)
            }
            stableConnectionTask?.cancel(false)
            stableConnectionTask = try {
                scheduler.schedule({
                    synchronized(stateLock) {
                        if (!closed && socket === connectedSocket && isConnected) {
                            reconnectAttempts = 0
                        }
                    }
                }, STABLE_CONNECTION_RESET_SECONDS, TimeUnit.SECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
            connectedSocket.soTimeout = HEARTBEAT_TIMEOUT_MS
            reconnectTask?.cancel(false)
            reconnectTask = null
            onState(
                if (reconnecting) "Wi-Fi reconnected to computer." else "Connected to computer over Wi-Fi.",
                true
            )
            startHeartbeat(connectedSocket)
            startMonitor(connectedSocket, input)
        } catch (error: Exception) {
            closeSocket()
            if (reconnecting) {
                scheduleReconnect(error.message ?: "Reconnect attempt failed.")
            } else {
                onState(error.message ?: "Wi-Fi connection failed.", false)
            }
        }
    }

    private fun startHeartbeat(connectedSocket: SSLSocket) {
        heartbeatTask?.cancel(false)
        try {
            heartbeatTask = scheduler.scheduleWithFixedDelay({
                if (!closed && socket === connectedSocket && isConnected) {
                    sendLine("{\"type\":\"ping\"}")
                }
            }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            if (!closed) connectionLost(connectedSocket, "Wi-Fi heartbeat scheduler stopped.")
        }
    }

    private fun startMonitor(connectedSocket: SSLSocket, input: BufferedReader) {
        Thread({
            try {
                while (true) {
                    val line = input.readLine() ?: break
                    val type = try {
                        JSONObject(line).optString("type")
                    } catch (_: Exception) {
                        throw IOException("Invalid heartbeat response from the desktop companion.")
                    }
                    if (type != "pong") {
                        throw IOException("Unexpected response from the desktop companion.")
                    }
                }
                connectionLost(connectedSocket, "Desktop companion disconnected.")
            } catch (error: Exception) {
                connectionLost(
                    connectedSocket,
                    if (error is java.net.SocketTimeoutException) {
                        "Wi-Fi heartbeat timed out."
                    } else {
                        error.message ?: "Wi-Fi connection lost."
                    }
                )
            }
        }, "AirPad-WiFi-monitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun connectionLost(connectedSocket: SSLSocket, reason: String) {
        val lost = synchronized(stateLock) {
            if (closed || socket !== connectedSocket || !isConnected) {
                false
            } else {
                socket = null
                writer = null
                isConnected = false
                heartbeatTask?.cancel(false)
                heartbeatTask = null
                stableConnectionTask?.cancel(false)
                stableConnectionTask = null
                true
            }
        }
        if (lost) {
            try { connectedSocket.close() } catch (_: Exception) { }
            scheduleReconnect(reason)
        }
    }

    private fun scheduleReconnect(reason: String) {
        val config = connectionConfig ?: return
        val attempt = synchronized(stateLock) {
            if (closed || reconnectAttempts >= WifiReconnectPolicy.MAX_ATTEMPTS) {
                -1
            } else {
                reconnectAttempts += 1
                reconnectAttempts
            }
        }
        val delaySeconds = WifiReconnectPolicy.delaySeconds(attempt)
        if (delaySeconds == null) {
            onState("Wi-Fi disconnected. Automatic reconnect stopped; tap Wi-Fi to try again.", false)
            return
        }
        onState("$reason Reconnecting in $delaySeconds seconds ($attempt/${WifiReconnectPolicy.MAX_ATTEMPTS}).", false)
        reconnectTask?.cancel(false)
        try {
            reconnectTask = scheduler.schedule({
                if (!closed) enqueue { connectInternal(config, reconnecting = true) }
            }, delaySeconds, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            if (!closed) onState("Automatic reconnect could not be scheduled. Tap Wi-Fi to try again.", false)
        }
    }

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        sendLine("{\"type\":\"mouse\",\"buttons\":$buttons,\"dx\":$dx,\"dy\":$dy,\"wheel\":$wheel}")
    }

    fun sendKey(modifiers: Int, keyCode: Int) {
        sendLine("{\"type\":\"key\",\"modifiers\":$modifiers,\"key\":$keyCode}")
    }

    fun releaseAll() {
        sendLine("{\"type\":\"release\"}")
    }

    private fun sendLine(line: String) {
        if (!isConnected) return
        enqueue {
            val output = writer ?: return@enqueue
            val activeSocket = socket ?: return@enqueue
            try {
                output.write(line)
                output.write("\n")
                output.flush()
            } catch (_: Exception) {
                connectionLost(activeSocket, "Wi-Fi connection lost while sending input.")
            }
        }
    }

    private fun closeSocket() {
        synchronized(stateLock) {
            isConnected = false
            writer = null
            val activeSocket = socket
            socket = null
            heartbeatTask?.cancel(false)
            heartbeatTask = null
            stableConnectionTask?.cancel(false)
            stableConnectionTask = null
            try { activeSocket?.close() } catch (_: Exception) { }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            closed = true
            reconnectTask?.cancel(false)
            reconnectTask = null
        }
        closeSocket()
        scheduler.shutdownNow()
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val HEARTBEAT_INTERVAL_SECONDS = 10L
        private const val HEARTBEAT_TIMEOUT_MS = 30_000
        private const val STABLE_CONNECTION_RESET_SECONDS = 60L
    }
}
