package com.lauri000.nostrwifiaware

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {
    private enum class Role {
        IDLE,
        HOST,
        CLIENT,
    }

    companion object {
        private const val serviceName = "nostr-wifi-aware"
        private const val permissionRequestCode = 1001
        private const val logTag = "NostrWifiAware"
        private const val securePassphrase = "awarebenchpass123"
        private const val tcpProtocol = 6
        private const val ioBufferSize = 256 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val logLines = ArrayDeque<String>()
    private val transferBuffer = ByteArray(ioBufferSize) { (it and 0xff).toByte() }
    private val appInstance = "${Build.MODEL}-${System.currentTimeMillis().toString(16).takeLast(6)}"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var connectivityManager: ConnectivityManager

    private lateinit var statusView: TextView
    private lateinit var roleView: TextView
    private lateinit var logView: TextView
    private lateinit var hostButton: Button
    private lateinit var clientButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearButton: Button
    private lateinit var benchmark1Button: Button
    private lateinit var benchmark10Button: Button
    private lateinit var benchmark100Button: Button

    private var role = Role.IDLE
    private var pendingRole: Role? = null

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var nextMessageId = 1
    private var activePeerHandle: PeerHandle? = null

    private var hostNetwork: Network? = null
    private var clientNetwork: Network? = null
    private var hostNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var clientNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var hostServerSocket: ServerSocket? = null
    private var hostSocket: Socket? = null

    private var clientSocket: Socket? = null
    private var clientInput: DataInputStream? = null
    private var clientOutput: DataOutputStream? = null
    private var clientPeerIpv6: Inet6Address? = null
    private var clientPeerPort = 0
    @Volatile
    private var clientSocketConnecting = false

    @Volatile
    private var benchmarkInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        setContentView(buildUi())
        setStatus("Idle")
        updateRoleView()
        updateControls()
        appendLog("Use one phone as Host and the other as Client. Then run 1, 10, or 100 Mbit tests from the client.")
    }

    override fun onDestroy() {
        stopAll("Activity destroyed")
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != permissionRequestCode) {
            return
        }

        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendLog("Permissions granted.")
            pendingRole?.let(::startAware)
        } else {
            pendingRole = null
            role = Role.IDLE
            setStatus("Permission denied")
            updateRoleView()
            updateControls()
            appendLog("Missing runtime permission. Wi-Fi Aware cannot start.")
        }
    }

    private fun buildUi(): LinearLayout {
        val padding = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            textSize = 18f
        }

        roleView = TextView(this)

        val hintView = TextView(this).apply {
            text = "Keep both phones open, plugged in, with Wi-Fi and location services enabled. Host first, then client."
        }

        hostButton = Button(this).apply {
            text = "Start Host"
            setOnClickListener { ensurePermissionsAndStart(Role.HOST) }
        }

        clientButton = Button(this).apply {
            text = "Start Client"
            setOnClickListener { ensurePermissionsAndStart(Role.CLIENT) }
        }

        stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopAll("Stopped manually") }
        }

        clearButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener {
                logLines.clear()
                renderLog()
            }
        }

        benchmark1Button = Button(this).apply {
            text = "1 Mbit"
            setOnClickListener { sendBenchmark(1) }
        }

        benchmark10Button = Button(this).apply {
            text = "10 Mbit"
            setOnClickListener { sendBenchmark(10) }
        }

        benchmark100Button = Button(this).apply {
            text = "100 Mbit"
            setOnClickListener { sendBenchmark(100) }
        }

        val roleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(hostButton)
            addView(clientButton)
            addView(stopButton)
        }

        val benchmarkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(benchmark1Button)
            addView(benchmark10Button)
            addView(benchmark100Button)
            addView(clearButton)
        }

        logView = TextView(this).apply {
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(statusView)
            addView(roleView)
            addView(hintView)
            addView(roleRow)
            addView(benchmarkRow)
            addView(scrollView)
        }
    }

    private fun ensurePermissionsAndStart(selectedRole: Role) {
        if (role != Role.IDLE) {
            appendLog("Already running as ${roleLabel(role)}.")
            return
        }

        if (Build.VERSION.SDK_INT < 29) {
            setStatus("Unsupported OS")
            appendLog("Bandwidth tests require Android 10+ because Wi-Fi Aware port metadata is only available there.")
            return
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            setStatus("Unsupported")
            appendLog("This device does not report Wi-Fi Aware support.")
            return
        }

        val missingPermissions = requiredRuntimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        pendingRole = selectedRole
        if (missingPermissions.isNotEmpty()) {
            appendLog("Requesting permission: ${missingPermissions.joinToString()}")
            requestPermissions(missingPermissions.toTypedArray(), permissionRequestCode)
            return
        }

        startAware(selectedRole)
    }

    private fun requiredRuntimePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startAware(selectedRole: Role) {
        if (!wifiAwareManager.isAvailable) {
            pendingRole = null
            setStatus("Unavailable")
            appendLog("Wi-Fi Aware is unavailable. Check Wi-Fi, location services, or hotspot/tethering state.")
            return
        }

        pendingRole = null
        role = selectedRole
        nextMessageId = 1
        activePeerHandle = null
        benchmarkInFlight = false
        updateRoleView()
        updateControls()
        setStatus("Attaching")
        appendLog("Attaching as ${roleLabel(selectedRole)} ($appInstance)")

        wifiAwareManager.attach(
            object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (role == Role.IDLE) {
                        session.close()
                        return
                    }

                    awareSession = session
                    setStatus("Running")
                    appendLog("Attach succeeded.")
                    when (role) {
                        Role.HOST -> startPublishHost(session)
                        Role.CLIENT -> startSubscribeClient(session)
                        Role.IDLE -> session.close()
                    }
                }

                override fun onAttachFailed() {
                    role = Role.IDLE
                    updateRoleView()
                    updateControls()
                    setStatus("Attach failed")
                    appendLog("Attach failed.")
                }
            },
            mainHandler,
        )
    }

    private fun startPublishHost(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo("host:$appInstance".toByteArray())
            .build()

        session.publish(
            config,
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    appendLog("Host publish started. Waiting for a client hello.")
                }

                override fun onSessionConfigFailed() {
                    appendLog("Host publish config failed.")
                }

                override fun onSessionTerminated() {
                    publishSession = null
                    appendLog("Host publish session terminated.")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val text = message.toString(Charsets.UTF_8)
                    appendLog("Host received '$text' from ${peerLabel(peerHandle)}")
                    if (text.startsWith("hello:")) {
                        handleHostHello(peerHandle)
                    }
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    appendLog("Host sent message #$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    appendLog("Host failed to send message #$messageId")
                }
            },
            mainHandler,
        )
    }

    private fun startSubscribeClient(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .build()

        session.subscribe(
            config,
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    appendLog("Client subscribe started. Looking for a host.")
                }

                override fun onSessionConfigFailed() {
                    appendLog("Client subscribe config failed.")
                }

                override fun onSessionTerminated() {
                    subscribeSession = null
                    appendLog("Client subscribe session terminated.")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>,
                ) {
                    if (activePeerHandle != null) {
                        return
                    }

                    activePeerHandle = peerHandle
                    appendLog("Client discovered host ${peerLabel(peerHandle)}")
                    sendMessage(
                        session = subscribeSession,
                        peerHandle = peerHandle,
                        payload = "hello:$appInstance",
                        reason = "client hello",
                    )
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val text = message.toString(Charsets.UTF_8)
                    appendLog("Client received '$text' from ${peerLabel(peerHandle)}")
                    if (text.startsWith("ready:")) {
                        handleClientReady(peerHandle)
                    }
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    appendLog("Client sent message #$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    appendLog("Client failed to send message #$messageId")
                }
            },
            mainHandler,
        )
    }

    private fun handleHostHello(peerHandle: PeerHandle) {
        if (hostNetworkCallback != null) {
            appendLog("Host already requested a data path. Ignoring duplicate hello.")
            return
        }

        activePeerHandle = peerHandle
        appendLog("Host preparing a data path for ${peerLabel(peerHandle)}")

        val session = publishSession
        if (session == null) {
            appendLog("Host publish session is null.")
            return
        }

        try {
            closeHostSockets()
            val serverSocket = ServerSocket(0)
            hostServerSocket = serverSocket
            appendLog("Host server socket listening on port ${serverSocket.localPort}")
            ioExecutor.execute { acceptHostConnections(serverSocket) }

            val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                .setPskPassphrase(securePassphrase)
                .setPort(serverSocket.localPort)
                .setTransportProtocol(tcpProtocol)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    hostNetwork = network
                    appendLog("Host data path available.")
                }

                override fun onLost(network: Network) {
                    if (network == hostNetwork) {
                        appendLog("Host data path lost.")
                        hostNetwork = null
                        clearHostNetworkRequest()
                        closeHostSockets()
                    }
                }

                override fun onUnavailable() {
                    appendLog("Host data path unavailable.")
                    clearHostNetworkRequest()
                    closeHostSockets()
                }
            }

            hostNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)

            sendMessage(
                session = publishSession,
                peerHandle = peerHandle,
                payload = "ready:$appInstance",
                reason = "host requested data path",
            )
        } catch (e: IOException) {
            appendLog("Host setup failed: ${e.message}")
            clearHostNetworkRequest()
            closeHostSockets()
        }
    }

    private fun acceptHostConnections(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed && role == Role.HOST) {
            try {
                val socket = serverSocket.accept()
                hostSocket?.close()
                hostSocket = socket
                appendLog("Host accepted TCP socket from client.")
                handleHostBenchmarks(socket)
            } catch (e: IOException) {
                if (!serverSocket.isClosed && role == Role.HOST) {
                    appendLog("Host accept failed: ${e.message}")
                }
                return
            }
        }
    }

    private fun handleHostBenchmarks(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), ioBufferSize))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), ioBufferSize))

            while (!socket.isClosed && role == Role.HOST) {
                val expectedBytes = try {
                    input.readLong()
                } catch (_: EOFException) {
                    appendLog("Host benchmark stream closed by client.")
                    break
                }

                if (expectedBytes <= 0L) {
                    appendLog("Host received a zero-length benchmark frame. Closing socket.")
                    break
                }

                appendLog("Host receiving ${bitsLabelForBytes(expectedBytes)} (${formatByteCount(expectedBytes)})")
                var remaining = expectedBytes
                val startNs = SystemClock.elapsedRealtimeNanos()

                while (remaining > 0) {
                    val chunk = min(transferBuffer.size.toLong(), remaining).toInt()
                    val read = input.read(transferBuffer, 0, chunk)
                    if (read < 0) {
                        throw EOFException("Client disconnected mid-transfer.")
                    }
                    remaining -= read.toLong()
                }

                val elapsedSeconds = elapsedSecondsSince(startNs)
                val mbps = toMegabitsPerSecond(expectedBytes, elapsedSeconds)
                appendLog(
                    "Host received ${bitsLabelForBytes(expectedBytes)} in ${formatSeconds(elapsedSeconds)} = ${formatMbps(mbps)} Mbit/s",
                )
                output.writeLong(expectedBytes)
                output.flush()
            }
        } catch (e: IOException) {
            if (role == Role.HOST) {
                appendLog("Host socket error: ${e.message}")
            }
        } finally {
            safeClose(socket)
            if (hostSocket === socket) {
                hostSocket = null
            }
            appendLog("Host TCP socket closed.")
        }
    }

    private fun handleClientReady(peerHandle: PeerHandle) {
        if (clientNetworkCallback != null || clientSocket != null) {
            appendLog("Client already requested a data path.")
            return
        }

        val session = subscribeSession
        if (session == null) {
            appendLog("Client subscribe session is null.")
            return
        }

        appendLog("Client requesting a data path to ${peerLabel(peerHandle)}")
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(securePassphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                clientNetwork = network
                appendLog("Client data path available.")
                maybeConnectClientSocket()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                clientPeerIpv6 = awareInfo.peerIpv6Addr
                clientPeerPort = awareInfo.port
                appendLog("Client learned host port=${awareInfo.port} ipv6=${awareInfo.peerIpv6Addr?.hostAddress ?: "null"}")
                maybeConnectClientSocket()
            }

            override fun onLost(network: Network) {
                if (network == clientNetwork) {
                    appendLog("Client data path lost.")
                    clientNetwork = null
                    clientPeerIpv6 = null
                    clientPeerPort = 0
                    clearClientNetworkRequest()
                    closeClientSocket()
                }
            }

            override fun onUnavailable() {
                appendLog("Client data path unavailable.")
                clearClientNetworkRequest()
                closeClientSocket()
            }
        }

        clientNetworkCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    private fun maybeConnectClientSocket() {
        val network = clientNetwork ?: return
        val peerIpv6 = clientPeerIpv6 ?: return
        val peerPort = clientPeerPort
        if (peerPort <= 0 || clientSocket != null || clientSocketConnecting) {
            return
        }
        clientSocketConnecting = true

        ioExecutor.execute {
            try {
                appendLog("Client opening TCP socket to [${peerIpv6.hostAddress}]:$peerPort")
                val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
                clientSocket = socket
                clientInput = DataInputStream(BufferedInputStream(socket.getInputStream(), ioBufferSize))
                clientOutput = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), ioBufferSize))
                clientSocketConnecting = false
                appendLog("Client TCP socket connected. Ready for benchmarks.")
                updateControls()
            } catch (e: IOException) {
                clientSocketConnecting = false
                appendLog("Client socket connect failed: ${e.message}")
                closeClientSocket()
            }
        }
    }

    private fun sendBenchmark(megabits: Int) {
        if (role != Role.CLIENT) {
            appendLog("Only the client can send benchmark traffic.")
            return
        }

        if (benchmarkInFlight) {
            appendLog("A benchmark is already in flight.")
            return
        }

        val socket = clientSocket
        val input = clientInput
        val output = clientOutput
        if (socket == null || input == null || output == null) {
            appendLog("Client socket is not connected yet.")
            return
        }

        benchmarkInFlight = true
        updateControls()
        val payloadBytes = megabits * 1_000_000L / 8L

        ioExecutor.execute {
            try {
                appendLog("Client sending ${megabits} Mbit (${formatByteCount(payloadBytes)})")

                output.writeLong(payloadBytes)
                output.flush()

                var remaining = payloadBytes
                val startNs = SystemClock.elapsedRealtimeNanos()
                while (remaining > 0) {
                    val chunk = min(transferBuffer.size.toLong(), remaining).toInt()
                    output.write(transferBuffer, 0, chunk)
                    remaining -= chunk.toLong()
                }
                output.flush()

                val ackBytes = input.readLong()
                val elapsedSeconds = elapsedSecondsSince(startNs)
                val mbps = toMegabitsPerSecond(payloadBytes, elapsedSeconds)
                appendLog(
                    "Client sent ${megabits} Mbit in ${formatSeconds(elapsedSeconds)} = ${formatMbps(mbps)} Mbit/s (ack=${formatByteCount(ackBytes)})",
                )
            } catch (e: IOException) {
                appendLog("Benchmark send failed: ${e.message}")
                closeClientSocket()
            } finally {
                benchmarkInFlight = false
                updateControls()
            }
        }
    }

    private fun sendMessage(
        session: DiscoverySession?,
        peerHandle: PeerHandle,
        payload: String,
        reason: String,
    ) {
        if (session == null) {
            appendLog("Cannot send '$payload': discovery session is null.")
            return
        }

        val messageId = nextMessageId++
        appendLog("Sending '$payload' to ${peerLabel(peerHandle)} ($reason)")
        session.sendMessage(peerHandle, messageId, payload.toByteArray(Charsets.UTF_8))
    }

    private fun stopAll(reason: String) {
        pendingRole = null
        benchmarkInFlight = false

        clearClientNetworkRequest()
        clearHostNetworkRequest()
        closeClientSocket()
        closeHostSockets()

        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()

        publishSession = null
        subscribeSession = null
        awareSession = null
        activePeerHandle = null
        hostNetwork = null
        clientNetwork = null
        clientPeerIpv6 = null
        clientPeerPort = 0
        nextMessageId = 1

        if (role != Role.IDLE) {
            role = Role.IDLE
            updateRoleView()
            updateControls()
            setStatus("Stopped")
            appendLog(reason)
        }
    }

    private fun clearHostNetworkRequest() {
        hostNetworkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        hostNetworkCallback = null
    }

    private fun clearClientNetworkRequest() {
        clientNetworkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        clientNetworkCallback = null
    }

    private fun closeHostSockets() {
        safeClose(hostSocket)
        safeClose(hostServerSocket)
        hostSocket = null
        hostServerSocket = null
    }

    private fun closeClientSocket() {
        safeClose(clientInput)
        safeClose(clientOutput)
        safeClose(clientSocket)
        clientInput = null
        clientOutput = null
        clientSocket = null
        clientSocketConnecting = false
        updateControls()
    }

    private fun safeClose(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun setStatus(status: String) {
        onMain {
            statusView.text = "Status: $status"
        }
    }

    private fun updateRoleView() {
        onMain {
            roleView.text = "Role: ${roleLabel(role)}"
        }
    }

    private fun updateControls() {
        onMain {
            hostButton.isEnabled = role == Role.IDLE
            clientButton.isEnabled = role == Role.IDLE
            stopButton.isEnabled = role != Role.IDLE

            val canBenchmark = role == Role.CLIENT && clientSocket != null && !clientSocketConnecting && !benchmarkInFlight
            benchmark1Button.isEnabled = canBenchmark
            benchmark10Button.isEnabled = canBenchmark
            benchmark100Button.isEnabled = canBenchmark
        }
    }

    private fun appendLog(message: String) {
        Log.d(logTag, message)
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$timestamp] $message"
        onMain {
            logLines.addLast(line)
            while (logLines.size > 300) {
                logLines.removeFirst()
            }
            renderLog()
        }
    }

    private fun renderLog() {
        onMain {
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun roleLabel(value: Role): String {
        return when (value) {
            Role.IDLE -> "Idle"
            Role.HOST -> "Host"
            Role.CLIENT -> "Client"
        }
    }

    private fun peerLabel(peerHandle: PeerHandle): String {
        return "peer-${peerHandle.hashCode().toUInt().toString(16)}"
    }

    private fun elapsedSecondsSince(startNs: Long): Double {
        return (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000_000.0
    }

    private fun toMegabitsPerSecond(bytes: Long, seconds: Double): Double {
        return if (seconds <= 0.0) {
            0.0
        } else {
            bytes * 8.0 / seconds / 1_000_000.0
        }
    }

    private fun formatSeconds(seconds: Double): String {
        return String.format(Locale.US, "%.3fs", seconds)
    }

    private fun formatMbps(mbps: Double): String {
        return String.format(Locale.US, "%.2f", mbps)
    }

    private fun formatByteCount(bytes: Long): String {
        return when {
            bytes >= 1_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    private fun bitsLabelForBytes(bytes: Long): String {
        val megabits = bytes * 8.0 / 1_000_000.0
        return String.format(Locale.US, "%.0f Mbit", megabits)
    }
}
