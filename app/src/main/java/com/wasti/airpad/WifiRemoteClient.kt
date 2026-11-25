package com.wasti.airpad

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Pinned-certificate TLS transport for the optional desktop companion. */
internal class WifiRemoteClient(
    private val onState: (message: String, connected: Boolean) -> Unit
) : Closeable {
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(512),
        { runnable -> Thread(runnable, "AirPad-WiFi").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile private var closed = false

    /**
     * Gesture callbacks can arrive on the UI thread at high frequency. Never let
     * executor saturation escape into Android input dispatch: close the transport
     * so the desktop's EOF handler releases any held mouse buttons.
     */
    private fun enqueue(action: () -> Unit) {
        if (closed) return
        try {
            executor.execute(action)
        } catch (_: RejectedExecutionException) {
            if (!executor.isShutdown && !closed) {
                closeSocket()
                onState("Wi-Fi input queue is full. The connection was safely closed; reconnect to continue.", false)
            }
        }
    }

    fun connect(address: String, port: Int, pairingCode: String, fingerprint: String) {
        enqueue {
            closeSocket()
            try {
                require(address.isNotBlank()) { "Enter the computer's local IP address." }
                require(port in 1..65535) { "Port must be between 1 and 65535." }
                val resolvedAddress = InetAddress.getByName(address)
                require(
                    resolvedAddress.isSiteLocalAddress ||
                        resolvedAddress.isLinkLocalAddress ||
                        resolvedAddress.isLoopbackAddress
                ) { "Use the computer's private local-network IP address, not a public internet address." }
                require(pairingCode.matches(Regex("[0-9]{8}"))) { "Pairing code must contain 8 digits." }
                val expectedFingerprint = fingerprint
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
                val connectedSocket = (context.socketFactory.createSocket() as SSLSocket).apply {
                    soTimeout = 7000
                    connect(InetSocketAddress(resolvedAddress, port), 7000)
                    enabledProtocols = enabledProtocols.filter {
                        it == "TLSv1.3" || it == "TLSv1.2"
                    }.toTypedArray()
                    startHandshake()
                }

                val input = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
                val output = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
                output.write("{\"type\":\"auth\",\"code\":\"$pairingCode\"}\n")
                output.flush()
                val response = input.readLine()
                if (response != "{\"type\":\"ready\"}") {
                    connectedSocket.close()
                    throw SecurityException("The computer rejected pairing. Check the code and try again.")
                }

                connectedSocket.soTimeout = 0
                socket = connectedSocket
                writer = output
                isConnected = true
                onState("Connected to computer over Wi-Fi.", true)

                // The desktop sends no application data after its ready response.
                // A dedicated reader therefore watches for EOF and detects a
                // companion shutdown even when the phone is idle.
                Thread({
                    try {
                        while (input.readLine() != null) {
                            throw IOException("Unexpected response from the desktop companion.")
                        }
                        if (socket === connectedSocket && isConnected) {
                            closeSocket()
                            onState("Desktop companion disconnected. Reconnect to continue.", false)
                        }
                    } catch (error: Exception) {
                        if (socket === connectedSocket && isConnected) {
                            closeSocket()
                            onState(error.message ?: "Wi-Fi connection lost. Reconnect to continue.", false)
                        }
                    }
                }, "AirPad-WiFi-monitor").apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                closeSocket()
                onState(error.message ?: "Wi-Fi connection failed.", false)
            }
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
            try {
                output.write(line)
                output.write("\n")
                output.flush()
            } catch (error: Exception) {
                closeSocket()
                onState("Wi-Fi connection lost. Reconnect to continue.", false)
            }
        }
    }

    private fun closeSocket() {
        isConnected = false
        writer = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    override fun close() {
        closed = true
        closeSocket()
        executor.shutdownNow()
    }
}
