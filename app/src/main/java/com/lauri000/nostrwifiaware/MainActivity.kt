package com.lauri000.nostrwifiaware

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
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
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

class MainActivity : Activity() {
    private enum class Role {
        IDLE,
        PEER,
    }

    private enum class Page {
        CONFIG,
        FEED,
    }

    private enum class DataPathRole {
        NONE,
        RESPONDER,
        INITIATOR,
    }

    private data class FileAck(
        val success: Boolean,
        val actualNhash: String?,
        val alreadyPresent: Boolean,
        val message: String,
    )

    companion object {
        private const val serviceName = "_nostrwifiaware._tcp"
        private const val permissionRequestCode = 1001
        private const val cameraRequestCode = 2001
        private const val logTag = "NostrWifiAware"
        private const val securePassphrase = "awarebenchpass123"
        private const val tcpProtocol = 6
        private const val ioBufferSize = 256 * 1024
        private const val commandSet = "SET"
        private const val commandPhoto = "PHOTO"
        private const val commandDone = "DONE"
        private const val commandAck = "ACK"
        private const val fetchMessagePrefix = "fetch:"
        private const val albumLabel = "Local Instagram Feed"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val logLines = ArrayDeque<String>()
    private val ioBuffer = ByteArray(ioBufferSize)
    private val appInstance = "${Build.MODEL}-${System.currentTimeMillis().toString(16).takeLast(6)}"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var photoStore: PhotoDemoStore

    private lateinit var statusView: TextView
    private lateinit var modeView: TextView
    private lateinit var transportView: TextView
    private lateinit var storageView: TextView
    private lateinit var localSummaryView: TextView
    private lateinit var nearbySummaryView: TextView
    private lateinit var pageConfigButton: Button
    private lateinit var pageFeedButton: Button
    private lateinit var configPage: LinearLayout
    private lateinit var feedPage: LinearLayout
    private lateinit var takePhotoButton: Button
    private lateinit var startNearbyButton: Button
    private lateinit var stopButton: Button
    private lateinit var fetchPeerButton: Button
    private lateinit var shareNowButton: Button
    private lateinit var clearDataButton: Button
    private lateinit var clearLogButton: Button
    private lateinit var feedContainer: LinearLayout
    private lateinit var feedEmptyView: TextView
    private lateinit var logView: TextView

    private var role = Role.IDLE
    private var pendingRole: Role? = null
    private var page = Page.CONFIG
    private var dataPathRole = DataPathRole.NONE
    private var transportStatus = "Idle"

    private var localPhotos: List<PhotoItem> = emptyList()
    private var receivedPhotos: List<PhotoItem> = emptyList()

    private var captureTempFile: File? = null
    private var captureTempUri: Uri? = null

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var nextMessageId = 1
    private var publishPeerHandle: PeerHandle? = null
    private var subscribePeerHandle: PeerHandle? = null
    private var remotePeerInstance: String? = null
    private var helloSent = false

    private var responderNetwork: Network? = null
    private var initiatorNetwork: Network? = null
    private var responderNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var initiatorNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var responderServerSocket: ServerSocket? = null
    private var responderSocket: Socket? = null
    private var initiatorSocket: Socket? = null
    private var connectedSocket: Socket? = null
    private var connectedInput: DataInputStream? = null
    private var connectedOutput: DataOutputStream? = null
    private var initiatorPeerIpv6: Inet6Address? = null
    private var initiatorPeerPort = 0
    private val pendingFileAcks = LinkedBlockingQueue<FileAck>()
    private val socketWriteLock = Any()

    @Volatile
    private var initiatorSocketConnecting = false

    @Volatile
    private var transferInFlight = false

    @Volatile
    private var pendingRemoteFetchRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        photoStore = PhotoDemoStore(this)
        reloadPhotoState()

        setContentView(buildUi())
        setStatus("Idle")
        updateModeView()
        updateTransportView()
        updateCounts()
        updatePage()
        updateFeed()
        updateControls()
        appendLog("Local Instagram demo loaded. Take photos, keep them in hashtree-addressed storage, and share them with nearby peers over Wi-Fi Aware.")
    }

    override fun onDestroy() {
        stopAll("Activity destroyed")
        cleanupCaptureTemp()
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
            updateModeView()
            updateControls()
            appendLog("Missing runtime permission. Wi-Fi Aware cannot start.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != cameraRequestCode) {
            return
        }

        val tempFile = captureTempFile
        captureTempFile = null
        captureTempUri?.let { revokeUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        captureTempUri = null

        if (tempFile == null) {
            appendLog("Camera returned without a temp file.")
            return
        }

        if (resultCode != RESULT_OK) {
            tempFile.delete()
            appendLog("Photo capture canceled.")
            return
        }

        if (!tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            appendLog("Camera capture produced no image.")
            return
        }

        try {
            val photo = photoStore.finalizeCapturedPhoto(tempFile)
            reloadPhotoState()
            updateCounts()
            updateFeed()
            updateControls()
            page = Page.FEED
            updatePage()
            appendLog("Captured photo ${photo.id} nhash=${photo.nhash} size=${formatByteCount(photo.sizeBytes)}")
        } catch (e: Exception) {
            tempFile.delete()
            appendLog("Failed to finalize captured photo: ${e.message}")
        }
    }

    private fun buildUi(): ScrollView {
        val contentPadding = dp(20)
        val sectionSpacing = dp(18)

        statusView = buildStatPill()
        modeView = buildStatPill()
        transportView = buildStatPill()
        storageView = buildStatPill()
        localSummaryView = buildBodyText()
        nearbySummaryView = buildBodyText()
        logView =
            TextView(this).apply {
                setTextColor(parseColor("#b7c4da"))
                textSize = 12f
                setTextIsSelectable(true)
                setTypeface(Typeface.MONOSPACE)
            }
        feedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        feedEmptyView = buildEmptyText()

        pageConfigButton = buildMutedButton("Config").apply {
            setOnClickListener {
                page = Page.CONFIG
                updatePage()
            }
        }
        pageFeedButton = buildMutedButton("Feed").apply {
            setOnClickListener {
                page = Page.FEED
                updatePage()
            }
        }

        takePhotoButton = buildAccentButton("Take Photo", "#f97316", "#fb7185").apply {
            setOnClickListener { launchCameraCapture() }
        }
        startNearbyButton = buildMutedButton("Start Nearby").apply {
            setOnClickListener { ensurePermissionsAndStart(Role.PEER) }
        }
        stopButton = buildMutedButton("Stop").apply {
            setOnClickListener { stopAll("Stopped manually") }
        }
        fetchPeerButton = buildAccentButton("Fetch From Peer", "#38bdf8", "#06b6d4").apply {
            setOnClickListener { requestFetchFromPeer() }
        }
        shareNowButton = buildMutedButton("Share Available Photos").apply {
            setOnClickListener { sendAvailablePhotos("manual share") }
        }
        clearDataButton = buildMutedButton("Clear Demo Data").apply {
            setOnClickListener { clearDemoData() }
        }
        clearLogButton = buildGhostButton("Clear Log").apply {
            setOnClickListener {
                logLines.clear()
                renderLog()
            }
        }

        val headerCard =
            cardContainer().apply {
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Local Instagram"
                        textSize = 30f
                        setTextColor(Color.WHITE)
                        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Take photos, store them in hashtree-addressed local storage, then move them directly between phones over Wi-Fi Aware."
                        textSize = 14f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(14))
                    },
                )
                addView(statRow(statusView, modeView))
                addView(spacer(dp(10)))
                addView(statRow(transportView, storageView))
                addView(spacer(dp(14)))
                addView(buttonRow(pageConfigButton, pageFeedButton))
            }

        configPage =
            cardContainer().apply {
                addView(sectionTitle("Config"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Use this page to capture photos, connect nearby peers, fetch their feed, and inspect the transport log."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(14))
                    },
                )
                addView(localSummaryView)
                addView(nearbySummaryView.apply { setPadding(0, dp(8), 0, dp(14)) })
                addView(buttonRow(takePhotoButton, startNearbyButton))
                addView(spacer(dp(10)))
                addView(buttonRow(fetchPeerButton, shareNowButton))
                addView(spacer(dp(10)))
                addView(buttonRow(stopButton, clearDataButton))
                addView(spacer(dp(10)))
                addView(buttonRow(clearLogButton))
                addView(spacer(dp(18)))
                addView(sectionTitle("Transport Log"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "This is the proof that photo bytes crossed the Wi-Fi Aware data path and were re-verified by nhash."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(logView)
            }

        feedPage =
            cardContainer().apply {
                addView(sectionTitle("Photo Feed"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Newest photos first. Local photos and nearby photos are shown together in one feed."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(feedContainer)
                addView(feedEmptyView)
            }

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
                addView(headerCard)
                addView(spacer(sectionSpacing))
                addView(configPage)
                addView(spacer(sectionSpacing))
                addView(feedPage)
                addView(spacer(dp(24)))
            }

        return ScrollView(this).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(parseColor("#0d1220"), parseColor("#05070c")),
                )
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun launchCameraCapture() {
        if (transferInFlight) {
            appendLog("Wait for the current transfer to finish before taking another photo.")
            return
        }

        try {
            val tempFile = photoStore.createCaptureTempFile()
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tempFile)
            val intent =
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }

            if (intent.resolveActivity(packageManager) == null) {
                tempFile.delete()
                appendLog("No camera app is available on this device.")
                return
            }

            captureTempFile = tempFile
            captureTempUri = uri
            startActivityForResult(intent, cameraRequestCode)
        } catch (e: Exception) {
            cleanupCaptureTemp()
            appendLog("Failed to launch camera: ${e.message}")
        }
    }

    private fun cleanupCaptureTemp() {
        captureTempFile?.delete()
        captureTempFile = null
        captureTempUri = null
    }

    private fun clearDemoData() {
        if (role != Role.IDLE) {
            stopAll("Stopped for demo data clear")
        }
        cleanupCaptureTemp()
        photoStore.clearAll()
        reloadPhotoState()
        updateCounts()
        updateFeed()
        updateControls()
        appendLog("Cleared all local and nearby photos.")
    }

    private fun ensurePermissionsAndStart(selectedRole: Role) {
        if (role != Role.IDLE) {
            appendLog("Already running as ${roleLabel(role)}.")
            return
        }

        if (Build.VERSION.SDK_INT < 29) {
            setStatus("Unsupported OS")
            appendLog("This demo requires Android 10+ because the peer data path needs Wi-Fi Aware port metadata.")
            return
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            setStatus("Unsupported")
            appendLog("This device does not report Wi-Fi Aware support.")
            return
        }

        val missingPermissions =
            requiredRuntimePermissions().filter {
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
        pendingRemoteFetchRequest = false
        role = selectedRole
        dataPathRole = DataPathRole.NONE
        nextMessageId = 1
        publishPeerHandle = null
        subscribePeerHandle = null
        remotePeerInstance = null
        helloSent = false
        pendingFileAcks.clear()
        transferInFlight = false
        updateModeView()
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
                    startPublishPeer(session)
                    startSubscribePeer(session)
                }

                override fun onAttachFailed() {
                    role = Role.IDLE
                    updateModeView()
                    updateControls()
                    setStatus("Attach failed")
                    setTransportStatus("Attach failed")
                    appendLog("Attach failed.")
                }
            },
            mainHandler,
        )
    }

    private fun startPublishPeer(session: WifiAwareSession) {
        val config =
            PublishConfig.Builder()
                .setServiceName(serviceName)
                .setServiceSpecificInfo("peer:$appInstance".toByteArray())
                .build()

        session.publish(
            config,
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    setTransportStatus("Nearby publish started")
                    appendLog("Nearby publish started. Waiting for a peer hello if this phone becomes the responder.")
                }

                override fun onSessionConfigFailed() {
                    setTransportStatus("Nearby publish config failed")
                    appendLog("Nearby publish config failed.")
                }

                override fun onSessionTerminated() {
                    publishSession = null
                    setTransportStatus("Nearby publish terminated")
                    appendLog("Nearby publish session terminated.")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val text = message.toString(Charsets.UTF_8)
                    appendLog("Nearby publish received '$text' from ${peerLabel(peerHandle)}")
                    if (text.startsWith("hello:")) {
                        handleResponderHello(peerHandle, text.removePrefix("hello:"))
                    }
                    if (text.startsWith(fetchMessagePrefix)) {
                        rememberPeer(peerHandle, remotePeerInstance, isPublishHandle = true)
                        pendingRemoteFetchRequest = true
                        appendLog("Nearby peer requested this phone's available feed.")
                        maybeStartPendingFetch()
                    }
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    appendLog("Nearby publish sent message #$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    appendLog("Nearby publish failed to send message #$messageId")
                }
            },
            mainHandler,
        )
    }

    private fun startSubscribePeer(session: WifiAwareSession) {
        val config =
            SubscribeConfig.Builder()
                .setServiceName(serviceName)
                .build()

        session.subscribe(
            config,
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    setTransportStatus("Nearby subscribe started")
                    appendLog("Nearby subscribe started. Looking for a peer.")
                }

                override fun onSessionConfigFailed() {
                    setTransportStatus("Nearby subscribe config failed")
                    appendLog("Nearby subscribe config failed.")
                }

                override fun onSessionTerminated() {
                    subscribeSession = null
                    setTransportStatus("Nearby subscribe terminated")
                    appendLog("Nearby subscribe session terminated.")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>,
                ) {
                    val discoveredInstance = parseDiscoveredPeerInstance(serviceSpecificInfo)
                    if (!rememberPeer(peerHandle, discoveredInstance, isPublishHandle = false)) {
                        return
                    }

                    if (discoveredInstance == null) {
                        appendLog("Discovered ${peerLabel(peerHandle)}, but it did not advertise an app instance.")
                        return
                    }

                    setTransportStatus("Nearby peer discovered")
                    appendLog("Discovered nearby peer $discoveredInstance as ${peerLabel(peerHandle)}")
                    if (shouldInitiateDataPath(discoveredInstance)) {
                        maybeSendHello(peerHandle, discoveredInstance)
                    } else {
                        appendLog("Tie-break selected the other phone to initiate the Wi-Fi Aware data path.")
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val text = message.toString(Charsets.UTF_8)
                    appendLog("Nearby subscribe received '$text' from ${peerLabel(peerHandle)}")
                    if (text.startsWith("ready:")) {
                        rememberPeer(peerHandle, text.removePrefix("ready:"), isPublishHandle = false)
                        handleInitiatorReady(peerHandle)
                    }
                    if (text.startsWith(fetchMessagePrefix)) {
                        rememberPeer(peerHandle, remotePeerInstance, isPublishHandle = false)
                        pendingRemoteFetchRequest = true
                        appendLog("Nearby peer requested this phone's available feed.")
                        maybeStartPendingFetch()
                    }
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    appendLog("Nearby subscribe sent message #$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    appendLog("Nearby subscribe failed to send message #$messageId")
                }
            },
            mainHandler,
        )
    }

    private fun rememberPeer(
        peerHandle: PeerHandle,
        remoteInstance: String?,
        isPublishHandle: Boolean,
    ): Boolean {
        val normalizedRemote = remoteInstance ?: remotePeerInstance ?: peerLabel(peerHandle)
        val currentRemote = remotePeerInstance
        if (currentRemote != null && currentRemote != normalizedRemote) {
            appendLog("Ignoring extra nearby peer $normalizedRemote because this demo currently stays paired with $currentRemote.")
            return false
        }

        remotePeerInstance = normalizedRemote
        if (isPublishHandle) {
            publishPeerHandle = peerHandle
        } else {
            subscribePeerHandle = peerHandle
        }
        return true
    }

    private fun parseDiscoveredPeerInstance(serviceSpecificInfo: ByteArray): String? {
        if (serviceSpecificInfo.isEmpty()) {
            return null
        }
        val text = serviceSpecificInfo.toString(Charsets.UTF_8)
        return if (text.startsWith("peer:")) {
            text.removePrefix("peer:").takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun shouldInitiateDataPath(remoteInstance: String): Boolean = appInstance < remoteInstance

    private fun maybeSendHello(
        peerHandle: PeerHandle,
        remoteInstance: String,
    ) {
        if (helloSent || responderNetworkCallback != null || initiatorNetworkCallback != null || connectedSocket != null || initiatorSocketConnecting) {
            return
        }

        helloSent = true
        dataPathRole = DataPathRole.INITIATOR
        setTransportStatus("Initiating Wi-Fi Aware data path")
        appendLog("Tie-break selected this phone to initiate the Wi-Fi Aware data path with $remoteInstance.")
        sendMessage(subscribeSession, peerHandle, "hello:$appInstance", "peer hello")
    }

    private fun handleResponderHello(
        peerHandle: PeerHandle,
        remoteInstance: String?,
    ) {
        rememberPeer(peerHandle, remoteInstance, isPublishHandle = true)
        if (responderNetworkCallback != null) {
            appendLog("Responder already requested a Wi-Fi Aware data path. Ignoring duplicate hello.")
            return
        }

        dataPathRole = DataPathRole.RESPONDER
        setTransportStatus("Preparing Wi-Fi Aware data path")
        appendLog("Preparing a Wi-Fi Aware data path for ${remotePeerInstance ?: peerLabel(peerHandle)}")

        val session = publishSession
        if (session == null) {
            appendLog("Publish session is null.")
            return
        }

        try {
            closeResponderSockets()
            val serverSocket = ServerSocket(0)
            responderServerSocket = serverSocket
            appendLog("Responder server socket listening on port ${serverSocket.localPort}")
            ioExecutor.execute { acceptResponderConnections(serverSocket) }

            val specifier =
                WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                    .setPskPassphrase(securePassphrase)
                    .setPort(serverSocket.localPort)
                    .setTransportProtocol(tcpProtocol)
                    .build()

            val request =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(specifier)
                    .build()

            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        responderNetwork = network
                        setTransportStatus("Wi-Fi Aware data path available")
                        appendLog("Responder Wi-Fi Aware data path available.")
                    }

                    override fun onLost(network: Network) {
                        if (network == responderNetwork) {
                            appendLog("Responder data path lost.")
                            setTransportStatus("Wi-Fi Aware data path lost")
                            responderNetwork = null
                            clearResponderNetworkRequest()
                            closeResponderSockets()
                        }
                    }

                    override fun onUnavailable() {
                        appendLog("Responder data path unavailable.")
                        setTransportStatus("Wi-Fi Aware data path unavailable")
                        clearResponderNetworkRequest()
                        closeResponderSockets()
                    }
                }

            responderNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            sendMessage(publishSession, peerHandle, "ready:$appInstance", "responder requested data path")
        } catch (e: IOException) {
            appendLog("Responder setup failed: ${e.message}")
            setTransportStatus("Wi-Fi Aware setup failed")
            clearResponderNetworkRequest()
            closeResponderSockets()
        }
    }

    private fun acceptResponderConnections(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed && role == Role.PEER) {
            try {
                val socket = serverSocket.accept()
                responderSocket?.close()
                responderSocket = socket
                bindConnectedSocket(socket, "Responder")
            } catch (e: IOException) {
                if (!serverSocket.isClosed && role == Role.PEER) {
                    appendLog("Responder accept failed: ${e.message}")
                    setTransportStatus("Wi-Fi Aware socket accept failed")
                }
                return
            }
        }
    }

    private fun bindConnectedSocket(
        socket: Socket,
        label: String,
    ) {
        synchronized(socketWriteLock) {
            safeClose(connectedInput)
            safeClose(connectedOutput)
            if (connectedSocket != null && connectedSocket !== socket) {
                safeClose(connectedSocket)
            }
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), ioBufferSize))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), ioBufferSize))
            connectedSocket = socket
            connectedInput = input
            connectedOutput = output
            pendingFileAcks.clear()
            setTransportStatus("Wi-Fi Aware peer socket connected")
            appendLog("$label Wi-Fi Aware TCP socket connected.")
            updateControls()
            ioExecutor.execute { readConnectedSocket(socket, input, output, label) }
        }
        maybeStartPendingFetch()
    }

    private fun readConnectedSocket(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        label: String,
    ) {
        try {
            var activeLabel = albumLabel
            while (!socket.isClosed && role == Role.PEER) {
                val command =
                    try {
                        input.readUTF()
                    } catch (_: EOFException) {
                        appendLog("Peer photo stream closed.")
                        break
                    }

                when (command) {
                    commandSet -> {
                        activeLabel = input.readUTF()
                        val count = input.readInt()
                        appendLog("Receiving Wi-Fi Aware photo feed $activeLabel with $count photos.")
                    }

                    commandPhoto -> {
                        val photoId = input.readUTF()
                        val sourceLabel = input.readUTF()
                        val createdAtMs = input.readLong()
                        val announcedNhash = input.readUTF()
                        val expectedBytes = input.readLong()
                        if (expectedBytes <= 0L) {
                            appendLog("Received invalid photo size $expectedBytes for $photoId.")
                            break
                        }

                        appendLog("Receiving photo $photoId from $sourceLabel inside $activeLabel nhash=$announcedNhash (${formatByteCount(expectedBytes)}) over Wi-Fi Aware")
                        val tempFile = photoStore.createIncomingTempFile(photoId)
                        var remaining = expectedBytes
                        FileOutputStream(tempFile).use { fileOutput ->
                            while (remaining > 0) {
                                val chunk = min(ioBuffer.size.toLong(), remaining).toInt()
                                val read = input.read(ioBuffer, 0, chunk)
                                if (read < 0) {
                                    throw EOFException("Peer disconnected mid-photo transfer.")
                                }
                                fileOutput.write(ioBuffer, 0, read)
                                remaining -= read.toLong()
                            }
                        }

                        val result =
                            try {
                                photoStore.verifyAndStoreReceivedPhoto(
                                    tempFile = tempFile,
                                    photoId = photoId,
                                    createdAtMs = createdAtMs,
                                    announcedNhash = announcedNhash,
                                    sourceLabel = sourceLabel,
                                )
                            } catch (e: Exception) {
                                tempFile.delete()
                                StoredPhotoResult(
                                    success = false,
                                    actualNhash = null,
                                    photo = null,
                                    alreadyPresent = false,
                                    message = "Failed to verify received photo $photoId: ${e.message}",
                                )
                            }

                        if (result.success) {
                            receivedPhotos = photoStore.receivedPhotos()
                            updateCounts()
                            updateFeed()
                        }

                        appendLog(result.message)
                        synchronized(socketWriteLock) {
                            output.writeUTF(commandAck)
                            output.writeBoolean(result.success)
                            output.writeUTF(result.actualNhash ?: "")
                            output.writeBoolean(result.alreadyPresent)
                            output.writeUTF(result.message)
                            output.flush()
                        }
                    }

                    commandDone -> {
                        val labelDone = input.readUTF()
                        val count = input.readInt()
                        appendLog("Finished receiving Wi-Fi Aware photo feed $labelDone ($count photos).")
                    }

                    commandAck -> {
                        pendingFileAcks.offer(
                            FileAck(
                                success = input.readBoolean(),
                                actualNhash = input.readUTF().ifBlank { null },
                                alreadyPresent = input.readBoolean(),
                                message = input.readUTF(),
                            ),
                        )
                    }

                    else -> {
                        appendLog("Received unexpected command '$command'. Closing socket.")
                        break
                    }
                }
            }
        } catch (e: IOException) {
            if (role == Role.PEER) {
                appendLog("$label socket error: ${e.message}")
                setTransportStatus("Wi-Fi Aware socket error")
            }
        } finally {
            safeClose(socket)
            if (responderSocket === socket) {
                responderSocket = null
            }
            if (initiatorSocket === socket) {
                initiatorSocket = null
            }
            if (connectedSocket === socket) {
                connectedSocket = null
                connectedInput = null
                connectedOutput = null
            }
            appendLog("$label TCP socket closed.")
            updateControls()
        }
    }

    private fun handleInitiatorReady(peerHandle: PeerHandle) {
        if (initiatorNetworkCallback != null || initiatorSocket != null) {
            appendLog("Initiator already requested a data path.")
            return
        }

        val session = subscribeSession
        if (session == null) {
            appendLog("Subscribe session is null.")
            return
        }

        dataPathRole = DataPathRole.INITIATOR
        setTransportStatus("Requesting Wi-Fi Aware data path")
        appendLog("Initiating a Wi-Fi Aware data path to ${remotePeerInstance ?: peerLabel(peerHandle)}")
        val specifier =
            WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                .setPskPassphrase(securePassphrase)
                .build()

        val request =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    initiatorNetwork = network
                    setTransportStatus("Wi-Fi Aware data path available")
                    appendLog("Initiator Wi-Fi Aware data path available.")
                    maybeConnectInitiatorSocket()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                    initiatorPeerIpv6 = awareInfo.peerIpv6Addr
                    initiatorPeerPort = awareInfo.port
                    appendLog("Initiator learned responder endpoint port=${awareInfo.port} ipv6=${awareInfo.peerIpv6Addr?.hostAddress ?: "null"}")
                    maybeConnectInitiatorSocket()
                }

                override fun onLost(network: Network) {
                    if (network == initiatorNetwork) {
                        appendLog("Initiator data path lost.")
                        setTransportStatus("Wi-Fi Aware data path lost")
                        initiatorNetwork = null
                        initiatorPeerIpv6 = null
                        initiatorPeerPort = 0
                        clearInitiatorNetworkRequest()
                        closeInitiatorSocket()
                    }
                }

                override fun onUnavailable() {
                    appendLog("Initiator data path unavailable.")
                    setTransportStatus("Wi-Fi Aware data path unavailable")
                    clearInitiatorNetworkRequest()
                    closeInitiatorSocket()
                }
            }

        initiatorNetworkCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    private fun maybeConnectInitiatorSocket() {
        val network = initiatorNetwork ?: return
        val peerIpv6 = initiatorPeerIpv6 ?: return
        val peerPort = initiatorPeerPort
        if (peerPort <= 0 || initiatorSocket != null || initiatorSocketConnecting) {
            return
        }
        initiatorSocketConnecting = true
        updateControls()

        ioExecutor.execute {
            try {
                appendLog("Initiator opening Wi-Fi Aware TCP socket to [${peerIpv6.hostAddress}]:$peerPort")
                val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
                initiatorSocket = socket
                initiatorSocketConnecting = false
                bindConnectedSocket(socket, "Initiator")
            } catch (e: IOException) {
                initiatorSocketConnecting = false
                appendLog("Initiator socket connect failed: ${e.message}")
                setTransportStatus("Wi-Fi Aware socket connect failed")
                closeInitiatorSocket()
            }
        }
    }

    private fun requestFetchFromPeer() {
        if (role != Role.PEER) {
            appendLog("Start Nearby first.")
            return
        }
        if (connectedSocket == null) {
            appendLog("Wait for the Wi-Fi Aware peer socket before requesting a fetch.")
            return
        }
        if (!sendMessageToActivePeer("$fetchMessagePrefix$appInstance", "peer requested photo feed")) {
            appendLog("No nearby peer discovered yet.")
            return
        }
        appendLog("Requested the nearby peer's available photo feed.")
    }

    private fun maybeStartPendingFetch() {
        if (role == Role.PEER && pendingRemoteFetchRequest && connectedSocket != null && !initiatorSocketConnecting && !transferInFlight) {
            pendingRemoteFetchRequest = false
            sendAvailablePhotos("nearby fetch request")
        }
    }

    private fun sendAvailablePhotos(trigger: String) {
        if (role != Role.PEER) {
            appendLog("Start Nearby first.")
            return
        }
        if (transferInFlight) {
            appendLog("A photo transfer is already in flight.")
            return
        }

        reloadPhotoState()
        val availablePhotos = shareablePhotos()
        if (availablePhotos.isEmpty()) {
            appendLog("Take a photo first, or fetch photos from a nearby peer.")
            updateCounts()
            updateFeed()
            return
        }

        val output = connectedOutput
        if (connectedSocket == null || output == null) {
            appendLog("The Wi-Fi Aware peer socket is not connected yet.")
            return
        }

        transferInFlight = true
        pendingFileAcks.clear()
        updateControls()

        ioExecutor.execute {
            try {
                appendLog("Sending Wi-Fi Aware photo feed with ${availablePhotos.size} photos ($trigger).")
                synchronized(socketWriteLock) {
                    output.writeUTF(commandSet)
                    output.writeUTF(albumLabel)
                    output.writeInt(availablePhotos.size)
                    output.flush()
                }

                for (photo in availablePhotos.sortedByDescending { it.createdAtMs }) {
                    appendLog("Sending ${photo.id} nhash=${photo.nhash} size=${formatByteCount(photo.sizeBytes)} over Wi-Fi Aware")
                    synchronized(socketWriteLock) {
                        output.writeUTF(commandPhoto)
                        output.writeUTF(photo.id)
                        output.writeUTF(photo.sourceLabel)
                        output.writeLong(photo.createdAtMs)
                        output.writeUTF(photo.nhash)
                        output.writeLong(photo.sizeBytes)

                        FileInputStream(photo.file).use { fileInput ->
                            while (true) {
                                val read = fileInput.read(ioBuffer)
                                if (read < 0) {
                                    break
                                }
                                output.write(ioBuffer, 0, read)
                            }
                        }
                        output.flush()
                    }

                    val ack =
                        pendingFileAcks.poll(20, TimeUnit.SECONDS)
                            ?: throw IOException("Timed out waiting for peer ACK for ${photo.id}.")
                    appendLog("Peer reply for ${photo.id}: success=${ack.success} actualNhash=${ack.actualNhash ?: "n/a"} alreadyPresent=${ack.alreadyPresent}")
                    appendLog(ack.message)
                    if (!ack.success) {
                        appendLog("Stopping photo transfer because the peer rejected ${photo.id}.")
                        break
                    }
                }

                synchronized(socketWriteLock) {
                    output.writeUTF(commandDone)
                    output.writeUTF(albumLabel)
                    output.writeInt(availablePhotos.size)
                    output.flush()
                }
                appendLog("Finished sending Wi-Fi Aware photo feed.")
            } catch (e: IOException) {
                appendLog("Photo transfer failed: ${e.message}")
                setTransportStatus("Photo transfer failed")
                closeConnectedSocket()
            } finally {
                transferInFlight = false
                maybeStartPendingFetch()
                updateControls()
            }
        }
    }

    private fun sendMessageToActivePeer(
        payload: String,
        reason: String,
    ): Boolean {
        val subscribeHandle = subscribePeerHandle
        if (subscribeSession != null && subscribeHandle != null) {
            sendMessage(subscribeSession, subscribeHandle, payload, reason)
            return true
        }

        val publishHandle = publishPeerHandle
        if (publishSession != null && publishHandle != null) {
            sendMessage(publishSession, publishHandle, payload, reason)
            return true
        }

        return false
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
        pendingRemoteFetchRequest = false
        pendingFileAcks.clear()

        clearInitiatorNetworkRequest()
        clearResponderNetworkRequest()
        closeConnectedSocket()
        closeInitiatorSocket()
        closeResponderSockets()

        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()

        publishSession = null
        subscribeSession = null
        awareSession = null
        publishPeerHandle = null
        subscribePeerHandle = null
        remotePeerInstance = null
        helloSent = false
        dataPathRole = DataPathRole.NONE
        responderNetwork = null
        initiatorNetwork = null
        initiatorPeerIpv6 = null
        initiatorPeerPort = 0
        nextMessageId = 1

        if (role != Role.IDLE) {
            role = Role.IDLE
            updateModeView()
            updateControls()
            setStatus("Stopped")
            setTransportStatus("Idle")
            appendLog(reason)
        }
    }

    private fun clearResponderNetworkRequest() {
        responderNetworkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        responderNetworkCallback = null
    }

    private fun clearInitiatorNetworkRequest() {
        initiatorNetworkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        initiatorNetworkCallback = null
    }

    private fun closeResponderSockets() {
        safeClose(responderSocket)
        safeClose(responderServerSocket)
        responderSocket = null
        responderServerSocket = null
    }

    private fun closeInitiatorSocket() {
        safeClose(initiatorSocket)
        initiatorSocket = null
        initiatorSocketConnecting = false
        updateControls()
    }

    private fun closeConnectedSocket() {
        safeClose(connectedInput)
        safeClose(connectedOutput)
        safeClose(connectedSocket)
        connectedInput = null
        connectedOutput = null
        connectedSocket = null
        updateControls()
    }

    private fun safeClose(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun reloadPhotoState() {
        localPhotos = photoStore.currentLocalPhotos()
        receivedPhotos = photoStore.receivedPhotos()
    }

    private fun shareablePhotos(): List<PhotoItem> {
        val deduped = LinkedHashMap<String, PhotoItem>()
        (localPhotos + receivedPhotos).forEach { photo ->
            if (!deduped.containsKey(photo.nhash)) {
                deduped[photo.nhash] = photo
            }
        }
        return deduped.values.sortedByDescending { it.createdAtMs }
    }

    private fun allFeedPhotos(): List<PhotoItem> =
        (localPhotos + receivedPhotos)
            .sortedByDescending { it.createdAtMs }

    private fun updateCounts() {
        onMain {
            localSummaryView.text = "Local photos: ${localPhotos.size}  ·  Taken on this phone"
            nearbySummaryView.text = "Nearby photos: ${receivedPhotos.size}  ·  ${formatByteCount(photoStore.totalStorageBytes())}"
            storageView.text = "STORAGE  ${formatByteCount(photoStore.totalStorageBytes())}"
        }
    }

    private fun updateFeed() {
        onMain {
            feedContainer.removeAllViews()
            val feed = allFeedPhotos()
            if (feed.isEmpty()) {
                feedEmptyView.visibility = View.VISIBLE
                feedEmptyView.text = "No photos yet. Take a photo on Config, or fetch a nearby feed."
                return@onMain
            }

            feedEmptyView.visibility = View.GONE
            feed.forEachIndexed { index, photo ->
                feedContainer.addView(createPhotoCard(photo))
                if (index != feed.lastIndex) {
                    feedContainer.addView(spacer(dp(12)))
                }
            }
        }
    }

    private fun createPhotoCard(photo: PhotoItem): View {
        val image =
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedFill(parseColor("#0d1422"), parseColor("#26344a"))
                setImageBitmap(decodePreviewBitmap(photo.file, 1080, 720))
                clipToOutline = true
            }

        return cardContainer(
            colors = intArrayOf(parseColor("#121929"), parseColor("#0d1321")),
            strokeColor = parseColor("#233047"),
        ).apply {
            addView(image)
            addView(spacer(dp(12)))
            addView(
                TextView(this@MainActivity).apply {
                    text = photo.sourceLabel
                    textSize = 12f
                    setTextColor(parseColor("#9fb2ce"))
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = photo.id
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "${formatTimestamp(photo.createdAtMs)}  ·  ${formatByteCount(photo.sizeBytes)}"
                    textSize = 13f
                    setTextColor(parseColor("#d7def0"))
                    setPadding(0, dp(4), 0, 0)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = photo.nhash.takeLast(14)
                    textSize = 11f
                    setTextColor(parseColor("#9fb2ce"))
                    setTypeface(Typeface.MONOSPACE)
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
    }

    private fun decodePreviewBitmap(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
    ) = BitmapFactory.Options().run {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
        inSampleSize = computeSampleSize(outWidth, outHeight, targetWidth, targetHeight)
        inJustDecodeBounds = false
        BitmapFactory.decodeFile(file.absolutePath, this)
    }

    private fun computeSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth / 2 >= targetWidth && currentHeight / 2 >= targetHeight) {
            sampleSize *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun updatePage() {
        onMain {
            configPage.visibility = if (page == Page.CONFIG) View.VISIBLE else View.GONE
            feedPage.visibility = if (page == Page.FEED) View.VISIBLE else View.GONE
            pageConfigButton.alpha = if (page == Page.CONFIG) 1f else 0.7f
            pageFeedButton.alpha = if (page == Page.FEED) 1f else 0.7f
        }
    }

    private fun updateControls() {
        onMain {
            takePhotoButton.isEnabled = !transferInFlight
            clearDataButton.isEnabled = !transferInFlight
            startNearbyButton.isEnabled = role == Role.IDLE
            stopButton.isEnabled = role != Role.IDLE
            shareNowButton.isEnabled =
                role == Role.PEER &&
                    connectedSocket != null &&
                    !initiatorSocketConnecting &&
                    !transferInFlight &&
                    shareablePhotos().isNotEmpty()
            fetchPeerButton.isEnabled =
                role == Role.PEER &&
                    connectedSocket != null &&
                    (publishPeerHandle != null || subscribePeerHandle != null) &&
                    !transferInFlight
        }
    }

    private fun setStatus(status: String) {
        onMain { statusView.text = "STATUS  $status" }
    }

    private fun setTransportStatus(status: String) {
        transportStatus = status
        updateTransportView()
    }

    private fun updateModeView() {
        onMain { modeView.text = "MODE  ${roleLabel(role)}" }
    }

    private fun updateTransportView() {
        onMain { transportView.text = "LINK  $transportStatus" }
    }

    private fun appendLog(message: String) {
        Log.d(logTag, message)
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$timestamp] $message"
        onMain {
            logLines.addLast(line)
            while (logLines.size > 400) {
                logLines.removeFirst()
            }
            renderLog()
        }
    }

    private fun renderLog() {
        onMain { logView.text = logLines.joinToString("\n") }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun roleLabel(value: Role): String =
        when (value) {
            Role.IDLE -> "Idle"
            Role.PEER -> "Nearby"
        }

    private fun peerLabel(peerHandle: PeerHandle): String = "peer-${peerHandle.hashCode().toUInt().toString(16)}"

    private fun formatByteCount(bytes: Long): String =
        when {
            bytes >= 1_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun formatTimestamp(createdAtMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(createdAtMs))

    private fun buildStatPill(): TextView =
        TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = roundedFill(parseColor("#111827"), parseColor("#233047"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun buildBodyText(): TextView =
        TextView(this).apply {
            setTextColor(parseColor("#d7def0"))
            textSize = 14f
        }

    private fun buildEmptyText(): TextView =
        TextView(this).apply {
            setTextColor(parseColor("#9fb2ce"))
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }

    private fun cardContainer(
        colors: IntArray = intArrayOf(parseColor("#121929"), parseColor("#0d1321")),
        strokeColor: Int = parseColor("#233047"),
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    colors,
                ).apply {
                    cornerRadius = dp(30).toFloat()
                    setStroke(dp(1), strokeColor)
                }
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        }

    private fun buildAccentButton(
        text: String,
        startColor: String,
        endColor: String,
    ): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(parseColor(startColor), parseColor(endColor)),
                ).apply {
                    cornerRadius = dp(20).toFloat()
                }
            minimumHeight = dp(48)
        }

    private fun buildMutedButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedFill(parseColor("#141d2d"), parseColor("#2d3a55"))
            minimumHeight = dp(48)
        }

    private fun buildGhostButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(parseColor("#d7def0"))
            textSize = 15f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedFill(parseColor("#0d1422"), parseColor("#26344a"))
            minimumHeight = dp(48)
        }

    private fun roundedFill(
        fillColor: Int,
        strokeColor: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }

    private fun statRow(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { index, view ->
                val params =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) {
                            marginStart = dp(10)
                        }
                    }
                addView(view, params)
            }
        }

    private fun buttonRow(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { index, view ->
                val params =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) {
                            marginStart = dp(10)
                        }
                    }
                addView(view, params)
            }
        }

    private fun spacer(heightPx: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun parseColor(hex: String): Int = Color.parseColor(hex)
}
