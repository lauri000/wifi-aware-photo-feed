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
import java.io.FileInputStream
import java.io.FileOutputStream
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
        private const val commandBlob = "BLOB"
        private const val commandAck = "ACK"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val logLines = ArrayDeque<String>()
    private val ioBuffer = ByteArray(ioBufferSize)
    private val appInstance = "${Build.MODEL}-${System.currentTimeMillis().toString(16).takeLast(6)}"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var demoStore: HashtreeDemoStore

    private lateinit var statusView: TextView
    private lateinit var roleView: TextView
    private lateinit var transportView: TextView
    private lateinit var localBlobView: TextView
    private lateinit var receivedBlobView: TextView
    private lateinit var storageView: TextView
    private lateinit var logView: TextView
    private lateinit var seedButton: Button
    private lateinit var hostButton: Button
    private lateinit var clientButton: Button
    private lateinit var sendBlobButton: Button
    private lateinit var clearDataButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearLogButton: Button

    private var role = Role.IDLE
    private var pendingRole: Role? = null
    private var transportStatus = "Idle"

    private var localBlobInfo: DemoBlobInfo? = null
    private var lastReceivedBlobInfo: DemoBlobInfo? = null

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
    private var transferInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        demoStore = HashtreeDemoStore(this)
        localBlobInfo = demoStore.currentLocalBlob()
        lastReceivedBlobInfo = demoStore.lastReceivedBlob()

        setContentView(buildUi())
        setStatus("Idle")
        updateRoleView()
        updateTransportView()
        updateBlobViews()
        updateControls()
        appendLog(
            "Increment 1: seed one deterministic blob on the client, connect host/client over Wi-Fi Aware, and send the blob for receiver-side nhash verification.",
        )
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
        transportView = TextView(this)
        localBlobView = TextView(this)
        receivedBlobView = TextView(this)
        storageView = TextView(this)

        val hintView = TextView(this).apply {
            text =
                "This increment only proves one thing: a real hashtree-addressed blob can move over a Wi-Fi Aware data path. Seed the blob on the client, connect both phones, then tap Send Hashtree Blob."
        }

        seedButton = Button(this).apply {
            text = "Seed Demo Blob"
            setOnClickListener { seedDemoBlob() }
        }

        clearDataButton = Button(this).apply {
            text = "Clear Demo Data"
            setOnClickListener { clearDemoData() }
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

        sendBlobButton = Button(this).apply {
            text = "Send Hashtree Blob"
            setOnClickListener { sendHashtreeBlob() }
        }

        clearLogButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener {
                logLines.clear()
                renderLog()
            }
        }

        val seedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(seedButton)
            addView(clearDataButton)
        }

        val roleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(hostButton)
            addView(clientButton)
            addView(stopButton)
        }

        val transferRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(sendBlobButton)
            addView(clearLogButton)
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
            addView(transportView)
            addView(localBlobView)
            addView(receivedBlobView)
            addView(storageView)
            addView(hintView)
            addView(seedRow)
            addView(roleRow)
            addView(transferRow)
            addView(scrollView)
        }
    }

    private fun seedDemoBlob() {
        if (role != Role.IDLE) {
            appendLog("Stop Wi-Fi Aware before reseeding the demo blob.")
            return
        }

        try {
            localBlobInfo = demoStore.seedLocalBlob()
            updateBlobViews()
            updateControls()
            appendLog(
                "Seeded local demo blob ${localBlobInfo!!.nhash} (${formatByteCount(localBlobInfo!!.sizeBytes)}) at ${localBlobInfo!!.file.absolutePath}",
            )
        } catch (e: Exception) {
            appendLog("Failed to seed local blob: ${e.message}")
        }
    }

    private fun clearDemoData() {
        if (role != Role.IDLE) {
            stopAll("Stopped for demo data clear")
        }

        demoStore.clearAll()
        localBlobInfo = null
        lastReceivedBlobInfo = null
        updateBlobViews()
        updateControls()
        appendLog("Cleared all demo blob data.")
    }

    private fun ensurePermissionsAndStart(selectedRole: Role) {
        if (role != Role.IDLE) {
            appendLog("Already running as ${roleLabel(role)}.")
            return
        }

        if (Build.VERSION.SDK_INT < 29) {
            setStatus("Unsupported OS")
            appendLog("This demo requires Android 10+ because the client needs Wi-Fi Aware port metadata.")
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
            setTransportStatus("Wi-Fi Aware unavailable")
            appendLog("Wi-Fi Aware is unavailable. Check Wi-Fi, location services, or hotspot/tethering state.")
            return
        }

        pendingRole = null
        role = selectedRole
        nextMessageId = 1
        activePeerHandle = null
        transferInFlight = false
        updateRoleView()
        updateControls()
        setStatus("Attaching")
        setTransportStatus("Attaching to Wi-Fi Aware")
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
                    setTransportStatus("Attach failed")
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
                    setTransportStatus("Host publish started")
                    appendLog("Host publish started. Waiting for a client hello.")
                }

                override fun onSessionConfigFailed() {
                    setTransportStatus("Host publish config failed")
                    appendLog("Host publish config failed.")
                }

                override fun onSessionTerminated() {
                    publishSession = null
                    setTransportStatus("Host publish terminated")
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
                    setTransportStatus("Client subscribe started")
                    appendLog("Client subscribe started. Looking for a host.")
                }

                override fun onSessionConfigFailed() {
                    setTransportStatus("Client subscribe config failed")
                    appendLog("Client subscribe config failed.")
                }

                override fun onSessionTerminated() {
                    subscribeSession = null
                    setTransportStatus("Client subscribe terminated")
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
                    setTransportStatus("Client discovered host")
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
        setTransportStatus("Host preparing Wi-Fi Aware data path")
        appendLog("Host preparing a Wi-Fi Aware data path for ${peerLabel(peerHandle)}")

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
                    setTransportStatus("Host Wi-Fi Aware data path available")
                    appendLog("Host Wi-Fi Aware data path available.")
                }

                override fun onLost(network: Network) {
                    if (network == hostNetwork) {
                        appendLog("Host data path lost.")
                        setTransportStatus("Host Wi-Fi Aware data path lost")
                        hostNetwork = null
                        clearHostNetworkRequest()
                        closeHostSockets()
                    }
                }

                override fun onUnavailable() {
                    appendLog("Host data path unavailable.")
                    setTransportStatus("Host Wi-Fi Aware data path unavailable")
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
            setTransportStatus("Host setup failed")
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
                setTransportStatus("Host Wi-Fi Aware TCP socket connected")
                appendLog("Host accepted Wi-Fi Aware TCP socket from client.")
                handleHostTransfers(socket)
            } catch (e: IOException) {
                if (!serverSocket.isClosed && role == Role.HOST) {
                    appendLog("Host accept failed: ${e.message}")
                    setTransportStatus("Host socket accept failed")
                }
                return
            }
        }
    }

    private fun handleHostTransfers(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), ioBufferSize))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), ioBufferSize))

            while (!socket.isClosed && role == Role.HOST) {
                val command = try {
                    input.readUTF()
                } catch (_: EOFException) {
                    appendLog("Host blob stream closed by client.")
                    break
                }

                if (command != commandBlob) {
                    appendLog("Host received unexpected command '$command'. Closing socket.")
                    break
                }

                val announcedNhash = input.readUTF()
                val expectedBytes = input.readLong()
                if (expectedBytes <= 0L) {
                    appendLog("Host received invalid blob size $expectedBytes.")
                    break
                }

                appendLog(
                    "Host receiving Wi-Fi Aware hashtree blob $announcedNhash (${formatByteCount(expectedBytes)})",
                )
                val tempFile = demoStore.createIncomingTempFile()
                var remaining = expectedBytes

                FileOutputStream(tempFile).use { fileOutput ->
                    while (remaining > 0) {
                        val chunk = min(ioBuffer.size.toLong(), remaining).toInt()
                        val read = input.read(ioBuffer, 0, chunk)
                        if (read < 0) {
                            throw EOFException("Client disconnected mid-transfer.")
                        }
                        fileOutput.write(ioBuffer, 0, read)
                        remaining -= read.toLong()
                    }
                }

                val result = try {
                    demoStore.verifyAndStoreReceivedBlob(tempFile, announcedNhash)
                } catch (e: Exception) {
                    tempFile.delete()
                    StoredBlobResult(
                        success = false,
                        actualNhash = null,
                        file = null,
                        alreadyPresent = false,
                        message = "Failed to verify received blob: ${e.message}",
                    )
                }

                if (result.success) {
                    lastReceivedBlobInfo = demoStore.lastReceivedBlob()
                    updateBlobViews()
                }

                appendLog(result.message)
                output.writeUTF(commandAck)
                output.writeBoolean(result.success)
                output.writeUTF(result.actualNhash ?: "")
                output.writeBoolean(result.alreadyPresent)
                output.writeUTF(result.message)
                output.flush()
            }
        } catch (e: IOException) {
            if (role == Role.HOST) {
                appendLog("Host socket error: ${e.message}")
                setTransportStatus("Host socket error")
            }
        } finally {
            safeClose(socket)
            if (hostSocket === socket) {
                hostSocket = null
            }
            appendLog("Host TCP socket closed.")
            updateControls()
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

        setTransportStatus("Client requesting Wi-Fi Aware data path")
        appendLog("Client requesting a Wi-Fi Aware data path to ${peerLabel(peerHandle)}")
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
                setTransportStatus("Client Wi-Fi Aware data path available")
                appendLog("Client Wi-Fi Aware data path available.")
                maybeConnectClientSocket()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                clientPeerIpv6 = awareInfo.peerIpv6Addr
                clientPeerPort = awareInfo.port
                appendLog(
                    "Client learned host Wi-Fi Aware endpoint port=${awareInfo.port} ipv6=${awareInfo.peerIpv6Addr?.hostAddress ?: "null"}",
                )
                maybeConnectClientSocket()
            }

            override fun onLost(network: Network) {
                if (network == clientNetwork) {
                    appendLog("Client data path lost.")
                    setTransportStatus("Client Wi-Fi Aware data path lost")
                    clientNetwork = null
                    clientPeerIpv6 = null
                    clientPeerPort = 0
                    clearClientNetworkRequest()
                    closeClientSocket()
                }
            }

            override fun onUnavailable() {
                appendLog("Client data path unavailable.")
                setTransportStatus("Client Wi-Fi Aware data path unavailable")
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
        updateControls()

        ioExecutor.execute {
            try {
                appendLog("Client opening Wi-Fi Aware TCP socket to [${peerIpv6.hostAddress}]:$peerPort")
                val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
                clientSocket = socket
                clientInput = DataInputStream(BufferedInputStream(socket.getInputStream(), ioBufferSize))
                clientOutput = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), ioBufferSize))
                clientSocketConnecting = false
                setTransportStatus("Client Wi-Fi Aware TCP socket connected")
                appendLog("Client Wi-Fi Aware TCP socket connected. Ready to send the seeded hashtree blob.")
                updateControls()
            } catch (e: IOException) {
                clientSocketConnecting = false
                appendLog("Client socket connect failed: ${e.message}")
                setTransportStatus("Client socket connect failed")
                closeClientSocket()
            }
        }
    }

    private fun sendHashtreeBlob() {
        if (role != Role.CLIENT) {
            appendLog("Only the client sends the demo blob in increment 1.")
            return
        }

        if (transferInFlight) {
            appendLog("A blob transfer is already in flight.")
            return
        }

        val blob = localBlobInfo ?: demoStore.currentLocalBlob()
        if (blob == null) {
            appendLog("Seed the demo blob on the client first.")
            return
        }
        localBlobInfo = blob
        updateBlobViews()

        val input = clientInput
        val output = clientOutput
        if (clientSocket == null || input == null || output == null) {
            appendLog("Client socket is not connected yet.")
            return
        }

        transferInFlight = true
        updateControls()

        ioExecutor.execute {
            try {
                appendLog(
                    "Client sending Wi-Fi Aware hashtree blob ${blob.nhash} (${formatByteCount(blob.sizeBytes)}) from ${blob.file.absolutePath}",
                )
                output.writeUTF(commandBlob)
                output.writeUTF(blob.nhash)
                output.writeLong(blob.sizeBytes)

                FileInputStream(blob.file).use { fileInput ->
                    while (true) {
                        val read = fileInput.read(ioBuffer)
                        if (read < 0) {
                            break
                        }
                        output.write(ioBuffer, 0, read)
                    }
                }
                output.flush()

                val ack = input.readUTF()
                if (ack != commandAck) {
                    appendLog("Client expected ACK but received '$ack'.")
                    return@execute
                }

                val success = input.readBoolean()
                val actualNhash = input.readUTF()
                val alreadyPresent = input.readBoolean()
                val message = input.readUTF()
                appendLog(
                    "Host reply over Wi-Fi Aware: success=$success actualNhash=${actualNhash.ifBlank { "n/a" }} alreadyPresent=$alreadyPresent",
                )
                appendLog(message)
            } catch (e: IOException) {
                appendLog("Blob transfer failed: ${e.message}")
                setTransportStatus("Blob transfer failed")
                closeClientSocket()
            } finally {
                transferInFlight = false
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
        transferInFlight = false

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
            setTransportStatus("Idle")
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

    private fun setTransportStatus(status: String) {
        transportStatus = status
        updateTransportView()
    }

    private fun updateRoleView() {
        onMain {
            roleView.text = "Role: ${roleLabel(role)}"
        }
    }

    private fun updateTransportView() {
        onMain {
            transportView.text = "Transport: $transportStatus"
        }
    }

    private fun updateBlobViews() {
        onMain {
            val localBlob = localBlobInfo
            localBlobView.text = if (localBlob == null) {
                "Local blob: none seeded"
            } else {
                "Local blob: ${localBlob.nhash} (${formatByteCount(localBlob.sizeBytes)})"
            }

            val receivedBlob = lastReceivedBlobInfo
            receivedBlobView.text = if (receivedBlob == null) {
                "Last received blob: none"
            } else {
                "Last received blob: ${receivedBlob.nhash} (${formatByteCount(receivedBlob.sizeBytes)})"
            }

            storageView.text = "Demo storage: ${formatByteCount(demoStore.totalStorageBytes())}"
        }
    }

    private fun updateControls() {
        onMain {
            seedButton.isEnabled = role == Role.IDLE
            clearDataButton.isEnabled = !transferInFlight
            hostButton.isEnabled = role == Role.IDLE
            clientButton.isEnabled = role == Role.IDLE
            stopButton.isEnabled = role != Role.IDLE
            sendBlobButton.isEnabled =
                role == Role.CLIENT &&
                clientSocket != null &&
                !clientSocketConnecting &&
                !transferInFlight &&
                localBlobInfo != null
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

    private fun formatByteCount(bytes: Long): String {
        return when {
            bytes >= 1_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
