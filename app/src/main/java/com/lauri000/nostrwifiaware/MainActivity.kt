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
import uniffi.nearby_hashtree_ffi.AndroidCommand
import uniffi.nearby_hashtree_ffi.AndroidEvent
import uniffi.nearby_hashtree_ffi.AppCore
import uniffi.nearby_hashtree_ffi.ControlsEnabled
import uniffi.nearby_hashtree_ffi.DiscoveryChannel
import uniffi.nearby_hashtree_ffi.FeedItem
import uniffi.nearby_hashtree_ffi.SocketSide
import uniffi.nearby_hashtree_ffi.UiAction
import uniffi.nearby_hashtree_ffi.UiPage
import uniffi.nearby_hashtree_ffi.ViewState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val permissionRequestCode = 1001
        private const val cameraRequestCode = 2001
        private const val logTag = "NostrWifiAware"
        private const val ioBufferSize = 64 * 1024
    }

    private data class CaptureRequest(
        val outputPath: String,
        val uri: Uri,
    )

    private data class SocketResource(
        val connectionId: Long,
        val side: SocketSide,
        val socket: Socket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val coreExecutor = Executors.newSingleThreadExecutor()
    private val appInstance = "${Build.MODEL}-${System.currentTimeMillis().toString(16).takeLast(6)}"

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appCore: AppCore

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

    private var currentCaptureRequest: CaptureRequest? = null
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val publishHandles = ConcurrentHashMap<Long, PeerHandle>()
    private val subscribeHandles = ConcurrentHashMap<Long, PeerHandle>()
    private val sockets = ConcurrentHashMap<Long, SocketResource>()

    private var responderServerSocket: ServerSocket? = null
    private var responderNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var initiatorNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var initiatorNetwork: Network? = null

    private var renderedLogCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        appCore = AppCore(filesDir.absolutePath, appInstance)

        setContentView(buildUi())
        renderView(appCore.currentViewState())
    }

    override fun onDestroy() {
        cleanupAndroidResources()
        coreExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        appCore.close()
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
        val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        dispatchAndroidEvent(
            if (granted) {
                AndroidEvent.PermissionsGranted
            } else {
                AndroidEvent.PermissionsDenied
            },
        )
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

        val captureRequest = currentCaptureRequest
        currentCaptureRequest = null
        if (captureRequest != null) {
            revokeUriPermission(
                captureRequest.uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        if (captureRequest == null) {
            dispatchAndroidEvent(AndroidEvent.CameraCaptureCancelled)
            return
        }

        val outputFile = File(captureRequest.outputPath)
        if (resultCode == RESULT_OK && outputFile.exists() && outputFile.length() > 0L) {
            dispatchAndroidEvent(
                AndroidEvent.CameraCaptureCompleted(captureRequest.outputPath),
            )
        } else {
            outputFile.delete()
            dispatchAndroidEvent(AndroidEvent.CameraCaptureCancelled)
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
            setOnClickListener { dispatchUiAction(UiAction.SwitchPage(UiPage.CONFIG)) }
        }
        pageFeedButton = buildMutedButton("Feed").apply {
            setOnClickListener { dispatchUiAction(UiAction.SwitchPage(UiPage.FEED)) }
        }

        takePhotoButton = buildAccentButton("Take Photo", "#f97316", "#fb7185").apply {
            setOnClickListener { dispatchUiAction(UiAction.TakePhotoRequested) }
        }
        startNearbyButton = buildMutedButton("Start Nearby").apply {
            setOnClickListener { dispatchUiAction(UiAction.StartNearbyRequested) }
        }
        stopButton = buildMutedButton("Stop").apply {
            setOnClickListener { dispatchUiAction(UiAction.StopRequested) }
        }
        fetchPeerButton = buildAccentButton("Fetch From Peer", "#38bdf8", "#06b6d4").apply {
            setOnClickListener { dispatchUiAction(UiAction.FetchFromPeerRequested) }
        }
        shareNowButton = buildMutedButton("Share Available Photos").apply {
            setOnClickListener { dispatchUiAction(UiAction.ShareAvailablePhotosRequested) }
        }
        clearDataButton = buildMutedButton("Clear Demo Data").apply {
            setOnClickListener { dispatchUiAction(UiAction.ClearDemoDataRequested) }
        }
        clearLogButton = buildGhostButton("Clear Log").apply {
            setOnClickListener { dispatchUiAction(UiAction.ClearLogRequested) }
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

    private fun dispatchUiAction(action: UiAction) {
        coreExecutor.execute {
            try {
                appCore.onUiAction(action)
                drainCommands()
                publishViewState()
            } catch (e: Throwable) {
                Log.e(logTag, "UI action failed", e)
            }
        }
    }

    private fun dispatchAndroidEvent(event: AndroidEvent) {
        coreExecutor.execute {
            try {
                appCore.onAndroidEvent(event)
                drainCommands()
                publishViewState()
            } catch (e: Throwable) {
                Log.e(logTag, "Android event failed", e)
            }
        }
    }

    private fun drainCommands() {
        while (true) {
            val commands = appCore.takePendingCommands()
            if (commands.isEmpty()) {
                return
            }
            commands.forEach(::executeCommand)
        }
    }

    private fun publishViewState() {
        val viewState = appCore.currentViewState()
        mainHandler.post {
            renderView(viewState)
        }
    }

    private fun renderView(viewState: ViewState) {
        statusView.text = viewState.statusText
        modeView.text = viewState.modeText
        transportView.text = viewState.linkText
        storageView.text = viewState.storageText
        localSummaryView.text = viewState.localSummaryText
        nearbySummaryView.text = viewState.nearbySummaryText

        val controls = viewState.controlsEnabled
        applyControls(controls)
        applyPage(viewState.page)
        applyFeed(viewState.feedItems)
        applyLogs(viewState.logLines)
    }

    private fun applyControls(controls: ControlsEnabled) {
        takePhotoButton.isEnabled = controls.takePhoto
        startNearbyButton.isEnabled = controls.startNearby
        stopButton.isEnabled = controls.stop
        fetchPeerButton.isEnabled = controls.fetchFromPeer
        shareNowButton.isEnabled = controls.shareAvailablePhotos
        clearDataButton.isEnabled = controls.clearDemoData
        clearLogButton.isEnabled = controls.clearLog
    }

    private fun applyPage(page: UiPage) {
        configPage.visibility = if (page == UiPage.CONFIG) View.VISIBLE else View.GONE
        feedPage.visibility = if (page == UiPage.FEED) View.VISIBLE else View.GONE
        pageConfigButton.alpha = if (page == UiPage.CONFIG) 1f else 0.7f
        pageFeedButton.alpha = if (page == UiPage.FEED) 1f else 0.7f
    }

    private fun applyFeed(feedItems: List<FeedItem>) {
        feedContainer.removeAllViews()
        if (feedItems.isEmpty()) {
            feedEmptyView.visibility = View.VISIBLE
            feedEmptyView.text = "No photos yet. Take a photo on Config, or fetch a nearby feed."
            return
        }

        feedEmptyView.visibility = View.GONE
        feedItems.forEachIndexed { index, item ->
            feedContainer.addView(createPhotoCard(item))
            if (index != feedItems.lastIndex) {
                feedContainer.addView(spacer(dp(12)))
            }
        }
    }

    private fun applyLogs(logLines: List<String>) {
        val newLines = if (renderedLogCount <= logLines.size) {
            logLines.drop(renderedLogCount)
        } else {
            logLines
        }
        newLines.forEach { line ->
            Log.d(logTag, line)
        }
        renderedLogCount = logLines.size
        logView.text = logLines.joinToString("\n")
    }

    private fun executeCommand(command: AndroidCommand) {
        when (command) {
            is AndroidCommand.RequestPermissions -> executeRequestPermissions()
            is AndroidCommand.LaunchCameraCapture -> executeLaunchCameraCapture(command.outputPath)
            is AndroidCommand.StartAwareAttach -> executeStartAwareAttach()
            is AndroidCommand.StartPublish -> executeStartPublish(command.serviceName, command.serviceInfo)
            is AndroidCommand.StartSubscribe -> executeStartSubscribe(command.serviceName)
            is AndroidCommand.SendDiscoveryMessage -> executeSendDiscoveryMessage(command)
            is AndroidCommand.OpenResponder -> executeOpenResponder(command)
            is AndroidCommand.OpenInitiator -> executeOpenInitiator(command)
            is AndroidCommand.ConnectInitiatorSocket -> executeConnectInitiatorSocket(command)
            is AndroidCommand.WriteSocketBytes -> executeWriteSocketBytes(command)
            is AndroidCommand.CloseSocket -> closeSocket(command.connectionId)
            is AndroidCommand.StopAware -> cleanupAndroidResources()
        }
    }

    private fun executeRequestPermissions() {
        val missingPermissions =
            requiredRuntimePermissions().filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missingPermissions.isEmpty()) {
            appCore.onAndroidEvent(AndroidEvent.PermissionsGranted)
            return
        }
        runOnMain {
            requestPermissions(missingPermissions.toTypedArray(), permissionRequestCode)
        }
    }

    private fun executeLaunchCameraCapture(outputPath: String) {
        runOnMain {
            try {
                val outputFile = File(outputPath)
                outputFile.parentFile?.mkdirs()
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
                val intent =
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }

                if (intent.resolveActivity(packageManager) == null) {
                    outputFile.delete()
                    dispatchAndroidEvent(AndroidEvent.CameraCaptureCancelled)
                    return@runOnMain
                }

                currentCaptureRequest = CaptureRequest(outputPath, uri)
                startActivityForResult(intent, cameraRequestCode)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to launch camera", e)
                dispatchAndroidEvent(AndroidEvent.CameraCaptureCancelled)
            }
        }
    }

    private fun executeStartAwareAttach() {
        runOnMain {
            if (Build.VERSION.SDK_INT < 29 || !packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) || !wifiAwareManager.isAvailable) {
                dispatchAndroidEvent(AndroidEvent.AwareAttachFailed)
                return@runOnMain
            }

            wifiAwareManager.attach(
                object : AttachCallback() {
                    override fun onAttached(session: WifiAwareSession) {
                        awareSession = session
                        dispatchAndroidEvent(AndroidEvent.AwareAttachSucceeded)
                    }

                    override fun onAttachFailed() {
                        dispatchAndroidEvent(AndroidEvent.AwareAttachFailed)
                    }
                },
                mainHandler,
            )
        }
    }

    private fun executeStartPublish(
        serviceName: String,
        serviceInfo: String,
    ) {
        runOnMain {
            val session = awareSession ?: return@runOnMain
            val config =
                PublishConfig.Builder()
                    .setServiceName(serviceName)
                    .setServiceSpecificInfo(serviceInfo.toByteArray())
                    .build()

            session.publish(
                config,
                object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        publishSession = session
                        dispatchAndroidEvent(AndroidEvent.PublishStarted)
                    }

                    override fun onSessionConfigFailed() {
                        dispatchAndroidEvent(AndroidEvent.PublishTerminated)
                    }

                    override fun onSessionTerminated() {
                        publishSession = null
                        publishHandles.clear()
                        dispatchAndroidEvent(AndroidEvent.PublishTerminated)
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        val handleId = handleId(peerHandle)
                        publishHandles[handleId] = peerHandle
                        dispatchAndroidEvent(
                            AndroidEvent.DiscoveryMessageReceived(
                                DiscoveryChannel.PUBLISH,
                                handleId,
                                message.toString(Charsets.UTF_8),
                            ),
                        )
                    }

                    override fun onMessageSendSucceeded(messageId: Int) {
                        dispatchAndroidEvent(AndroidEvent.DiscoveryMessageSent(messageId.toLong()))
                    }

                    override fun onMessageSendFailed(messageId: Int) {
                        dispatchAndroidEvent(AndroidEvent.DiscoveryMessageFailed(messageId.toLong()))
                    }
                },
                mainHandler,
            )
        }
    }

    private fun executeStartSubscribe(serviceName: String) {
        runOnMain {
            val session = awareSession ?: return@runOnMain
            val config =
                SubscribeConfig.Builder()
                    .setServiceName(serviceName)
                    .build()

            session.subscribe(
                config,
                object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        subscribeSession = session
                        dispatchAndroidEvent(AndroidEvent.SubscribeStarted)
                    }

                    override fun onSessionConfigFailed() {
                        dispatchAndroidEvent(AndroidEvent.SubscribeTerminated)
                    }

                    override fun onSessionTerminated() {
                        subscribeSession = null
                        subscribeHandles.clear()
                        dispatchAndroidEvent(AndroidEvent.SubscribeTerminated)
                    }

                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray,
                        matchFilter: MutableList<ByteArray>?,
                    ) {
                        val handleId = handleId(peerHandle)
                        subscribeHandles[handleId] = peerHandle
                        val instance =
                            serviceSpecificInfo
                                .toString(Charsets.UTF_8)
                                .removePrefix("peer:")
                                .takeIf { it.isNotBlank() }
                        dispatchAndroidEvent(
                            AndroidEvent.PeerDiscovered(handleId, instance),
                        )
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        val handleId = handleId(peerHandle)
                        subscribeHandles[handleId] = peerHandle
                        dispatchAndroidEvent(
                            AndroidEvent.DiscoveryMessageReceived(
                                DiscoveryChannel.SUBSCRIBE,
                                handleId,
                                message.toString(Charsets.UTF_8),
                            ),
                        )
                    }

                    override fun onMessageSendSucceeded(messageId: Int) {
                        dispatchAndroidEvent(AndroidEvent.DiscoveryMessageSent(messageId.toLong()))
                    }

                    override fun onMessageSendFailed(messageId: Int) {
                        dispatchAndroidEvent(AndroidEvent.DiscoveryMessageFailed(messageId.toLong()))
                    }
                },
                mainHandler,
            )
        }
    }

    private fun executeSendDiscoveryMessage(command: AndroidCommand.SendDiscoveryMessage) {
        runOnMain {
            val peerHandle =
                when (command.channel) {
                    DiscoveryChannel.PUBLISH -> publishHandles[command.handleId]
                    DiscoveryChannel.SUBSCRIBE -> subscribeHandles[command.handleId]
                } ?: return@runOnMain
            when (command.channel) {
                DiscoveryChannel.PUBLISH -> publishSession?.sendMessage(
                    peerHandle,
                    command.messageId.toInt(),
                    command.payload.toByteArray(Charsets.UTF_8),
                )
                DiscoveryChannel.SUBSCRIBE -> subscribeSession?.sendMessage(
                    peerHandle,
                    command.messageId.toInt(),
                    command.payload.toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    private fun executeOpenResponder(command: AndroidCommand.OpenResponder) {
        runOnMain {
            val session = publishSession ?: return@runOnMain
            val peerHandle = publishHandles[command.handleId] ?: return@runOnMain
            try {
                responderServerSocket?.close()
                val serverSocket = ServerSocket(0)
                responderServerSocket = serverSocket
                ioExecutor.execute {
                    try {
                        val socket = serverSocket.accept()
                        bindSocket(command.connectionId, SocketSide.RESPONDER, socket)
                    } catch (e: IOException) {
                        if (!serverSocket.isClosed) {
                            dispatchAndroidEvent(
                                AndroidEvent.SocketError(command.connectionId, e.message ?: "Responder accept failed"),
                            )
                        }
                    }
                }

                val specifier =
                    WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                        .setPskPassphrase(command.passphrase)
                        .setPort(serverSocket.localPort)
                        .setTransportProtocol(command.protocol)
                        .build()

                val request =
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                        .setNetworkSpecifier(specifier)
                        .build()

                responderNetworkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            dispatchAndroidEvent(AndroidEvent.ResponderNetworkAvailable)
                        }

                        override fun onLost(network: Network) {
                            dispatchAndroidEvent(AndroidEvent.ResponderNetworkLost)
                        }

                        override fun onUnavailable() {
                            dispatchAndroidEvent(AndroidEvent.ResponderNetworkLost)
                        }
                    }

                connectivityManager.requestNetwork(request, responderNetworkCallback!!)
            } catch (e: Exception) {
                dispatchAndroidEvent(
                    AndroidEvent.SocketError(command.connectionId, e.message ?: "Responder setup failed"),
                )
            }
        }
    }

    private fun executeOpenInitiator(command: AndroidCommand.OpenInitiator) {
        runOnMain {
            val session = subscribeSession ?: return@runOnMain
            val peerHandle = subscribeHandles[command.handleId] ?: return@runOnMain
            val specifier =
                WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                    .setPskPassphrase(command.passphrase)
                    .build()
            val request =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(specifier)
                    .build()

            initiatorNetworkCallback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        initiatorNetwork = network
                        dispatchAndroidEvent(AndroidEvent.InitiatorNetworkAvailable)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                        dispatchAndroidEvent(
                            AndroidEvent.InitiatorCapabilities(
                                awareInfo.port,
                                awareInfo.peerIpv6Addr?.hostAddress,
                            ),
                        )
                    }

                    override fun onLost(network: Network) {
                        dispatchAndroidEvent(AndroidEvent.InitiatorNetworkLost)
                    }

                    override fun onUnavailable() {
                        dispatchAndroidEvent(AndroidEvent.InitiatorNetworkLost)
                    }
                }

            connectivityManager.requestNetwork(request, initiatorNetworkCallback!!)
        }
    }

    private fun executeConnectInitiatorSocket(command: AndroidCommand.ConnectInitiatorSocket) {
        ioExecutor.execute {
            try {
                val network = initiatorNetwork ?: throw IOException("Initiator network unavailable")
                val address = Inet6Address.getByName(command.ipv6) as Inet6Address
                val socket = network.socketFactory.createSocket(address, command.port)
                bindSocket(command.connectionId, SocketSide.INITIATOR, socket)
            } catch (e: Exception) {
                dispatchAndroidEvent(
                    AndroidEvent.SocketError(command.connectionId, e.message ?: "Initiator socket connect failed"),
                )
            }
        }
    }

    private fun bindSocket(
        connectionId: Long,
        side: SocketSide,
        socket: Socket,
    ) {
        val resource =
            SocketResource(
                connectionId = connectionId,
                side = side,
                socket = socket,
                input = BufferedInputStream(socket.getInputStream(), ioBufferSize),
                output = BufferedOutputStream(socket.getOutputStream(), ioBufferSize),
            )
        sockets[connectionId]?.socket?.close()
        sockets[connectionId] = resource
        dispatchAndroidEvent(AndroidEvent.SocketConnected(connectionId, side))
        ioExecutor.execute { readSocket(resource) }
    }

    private fun readSocket(resource: SocketResource) {
        val buffer = ByteArray(ioBufferSize)
        try {
            while (!resource.socket.isClosed) {
                val read = resource.input.read(buffer)
                if (read < 0) {
                    break
                }
                dispatchAndroidEvent(
                    AndroidEvent.SocketRead(
                        resource.connectionId,
                        buffer.copyOf(read),
                    ),
                )
            }
        } catch (_: IOException) {
        } finally {
            closeSocket(resource.connectionId)
        }
    }

    private fun executeWriteSocketBytes(command: AndroidCommand.WriteSocketBytes) {
        val resource = sockets[command.connectionId] ?: return
        try {
            resource.output.write(command.bytes)
            resource.output.flush()
        } catch (e: IOException) {
            dispatchAndroidEvent(
                AndroidEvent.SocketError(command.connectionId, e.message ?: "Socket write failed"),
            )
            closeSocket(command.connectionId)
        }
    }

    private fun closeSocket(connectionId: Long) {
        val resource = sockets.remove(connectionId) ?: return
        try {
            resource.input.close()
        } catch (_: Exception) {
        }
        try {
            resource.output.close()
        } catch (_: Exception) {
        }
        try {
            resource.socket.close()
        } catch (_: Exception) {
        }
        dispatchAndroidEvent(AndroidEvent.SocketClosed(connectionId))
    }

    private fun cleanupAndroidResources() {
        runOnMain {
            currentCaptureRequest = null
            publishSession?.close()
            subscribeSession?.close()
            awareSession?.close()
            publishSession = null
            subscribeSession = null
            awareSession = null
            publishHandles.clear()
            subscribeHandles.clear()

            responderNetworkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }
            initiatorNetworkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }
            responderNetworkCallback = null
            initiatorNetworkCallback = null
            initiatorNetwork = null
        }

        responderServerSocket?.close()
        responderServerSocket = null

        sockets.keys.toList().forEach(::closeSocket)
    }

    private fun requiredRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun createPhotoCard(item: FeedItem): View {
        val image =
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedFill(parseColor("#0d1422"), parseColor("#26344a"))
                setImageBitmap(decodePreviewBitmap(File(item.filePath), 1080, 720))
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
                    text = item.sourceLabel
                    textSize = 12f
                    setTextColor(parseColor("#9fb2ce"))
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = item.id
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "${formatTimestamp(item.createdAtMs)}  ·  ${formatByteCount(item.sizeBytes.toLong())}"
                    textSize = 13f
                    setTextColor(parseColor("#d7def0"))
                    setPadding(0, dp(4), 0, 0)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = item.nhashSuffix
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

    private fun handleId(peerHandle: PeerHandle): Long = peerHandle.hashCode().toLong()

    private fun formatByteCount(bytes: Long): String =
        when {
            bytes >= 1_000_000L -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun formatTimestamp(createdAtMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(createdAtMs))

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val posted =
            mainHandler.post {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(logTag, "Main-thread task failed", t)
                }
            }
        if (!posted) {
            Log.e(logTag, "Failed to post work to the main thread")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun parseColor(hex: String): Int = Color.parseColor(hex)
}
