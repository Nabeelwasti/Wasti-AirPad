package com.wasti.airpad

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Pinned-certificate TLS transport for the optional desktop companion. */
internal class WifiRemoteClient(
    private val onState: (message: String, connected: Boolean) -> Unit
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AirPad-WiFi").apply { isDaemon = true }
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile var isConnected: Boolean = false
        private set

    fun connect(address: String, port: Int, pairingCode: String, fingerprint: String) {
        executor.execute {
            closeSocket()
            try {
                require(address.isNotBlank()) { "Enter the computer's local IP address." }
                require(port in 1..65535) { "Port must be between 1 and 65535." }
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
                    connect(InetSocketAddress(address, port), 7000)
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
        executor.execute {
            val output = writer ?: return@execute
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
        closeSocket()
        executor.shutdownNow()
    }
}
