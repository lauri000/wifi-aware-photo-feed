package com.lauri000.nostrwifiaware

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val permissionRequestCode = 1001
        private const val cameraRequestCode = 2001
        private const val logTag = "NostrWifiAware"
        private const val ioBufferSize = 64 * 1024
        private const val renderDebounceMs = 48L
        private const val captureReadyTimeoutMs = 5_000L
        private const val captureReadyPollMs = 100L
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
    private val renderStateLock = Any()
    private val previewBitmapCache = object : LruCache<String, Bitmap>(24) {}

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appCore: AppCore

    private lateinit var statusView: TextView
    private lateinit var modeView: TextView
    private lateinit var transportView: TextView
    private lateinit var storageView: TextView
    private lateinit var localSummaryFeedView: TextView
    private lateinit var nearbySummaryFeedView: TextView
    private lateinit var localSummarySettingsView: TextView
    private lateinit var nearbySummarySettingsView: TextView
    private lateinit var pageConfigButton: Button
    private lateinit var settingsBackButton: Button
    private lateinit var configPage: LinearLayout
    private lateinit var feedPage: LinearLayout
    private lateinit var takePhotoButton: Button
    private lateinit var nearbyToggleButton: Button
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
    private val socketWriters = ConcurrentHashMap<Long, ExecutorService>()

    private val responderServerSockets = ConcurrentHashMap<Long, ServerSocket>()
    private val responderNetworkCallbacks = ConcurrentHashMap<Long, ConnectivityManager.NetworkCallback>()
    private val initiatorNetworkCallbacks = ConcurrentHashMap<Long, ConnectivityManager.NetworkCallback>()
    private val initiatorNetworks = ConcurrentHashMap<Long, Network>()

    private var pendingViewState: ViewState? = null
    private var renderScheduled = false
    private var renderedLogCount = 0
    private var renderedFeedSignature = ""

    private val renderRunnable: Runnable =
        object : Runnable {
            override fun run() {
                val viewState =
                    synchronized(renderStateLock) {
                        val next = pendingViewState
                        pendingViewState = null
                        next
                    }

                if (viewState != null) {
                    renderView(viewState)
                }

                val shouldContinue =
                    synchronized(renderStateLock) {
                        if (pendingViewState == null) {
                            renderScheduled = false
                            false
                        } else {
                            true
                        }
                    }

                if (shouldContinue) {
                    mainHandler.postDelayed(this, renderDebounceMs)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        appCore = AppCore(filesDir.absolutePath, cacheDir.absolutePath, appInstance)
        currentCaptureRequest = restorePendingCaptureRequest()

        setContentView(buildUi())
        renderView(appCore.currentViewState())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentCaptureRequest?.let { outState.putString("pending_capture_output_path", it.outputPath) }
    }

    override fun onDestroy() {
        cleanupAndroidResources()
        coreExecutor.shutdownNow()
        socketWriters.values.forEach { it.shutdownNow() }
        socketWriters.clear()
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

        val captureRequest = currentCaptureRequest ?: restorePendingCaptureRequest()
        clearPendingCaptureRequest()
        if (captureRequest == null) {
            Log.d(logTag, "Ignoring camera result with no pending capture request.")
            return
        }

        val outputFile = File(captureRequest.outputPath)
        if (resultCode == RESULT_OK) {
            ioExecutor.execute {
                val ready = waitForCapturedFile(outputFile)
                revokeCaptureUri(captureRequest)
                if (ready) {
                    dispatchAndroidEvent(
                        AndroidEvent.CameraCaptureCompleted(captureRequest.outputPath),
                    )
                } else {
                    outputFile.delete()
                    dispatchAndroidEvent(AndroidEvent.CameraCaptureCancelled)
                }
            }
            return
        }

        revokeCaptureUri(captureRequest)
        outputFile.delete()
        dispatchAndroidEvent(AndroidEvent.CameraCaptureCancelled)
    }

    private fun buildUi(): ScrollView {
        val contentPadding = dp(18)
        val sectionSpacing = dp(18)

        statusView = buildStatPill()
        modeView = buildStatPill()
        transportView = buildStatPill()
        storageView = buildStatPill()
        localSummaryFeedView = buildBodyText()
        nearbySummaryFeedView = buildBodyText()
        localSummarySettingsView = buildBodyText()
        nearbySummarySettingsView = buildBodyText()
        logView =
            TextView(this).apply {
                setTextColor(parseColor("#b7c4da"))
                textSize = 12f
                setTextIsSelectable(true)
                setTypeface(Typeface.MONOSPACE)
            }
        feedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        feedEmptyView = buildEmptyText()

        pageConfigButton = buildGhostButton("Settings").apply {
            setOnClickListener { dispatchUiAction(UiAction.SwitchPage(UiPage.SETTINGS)) }
        }
        settingsBackButton = buildGhostButton("Back to Feed").apply {
            setOnClickListener { dispatchUiAction(UiAction.SwitchPage(UiPage.FEED)) }
        }

        takePhotoButton = buildAccentButton("Take Photo", "#f97316", "#fb7185").apply {
            setOnClickListener { dispatchUiAction(UiAction.TakePhotoRequested) }
        }
        nearbyToggleButton = buildAccentButton("Connect", "#0ea5e9", "#22c55e").apply {
            setOnClickListener { dispatchUiAction(UiAction.ToggleNearbyRequested) }
        }
        clearDataButton = buildMutedButton("Clear Demo Data").apply {
            setOnClickListener { dispatchUiAction(UiAction.ClearDemoDataRequested) }
        }
        clearLogButton = buildGhostButton("Clear Log").apply {
            setOnClickListener { dispatchUiAction(UiAction.ClearLogRequested) }
        }

        val headerCard =
            cardContainer(
                colors = intArrayOf(parseColor("#17120f"), parseColor("#0d1220")),
                strokeColor = parseColor("#6b3416"),
                radiusDp = 34,
            ).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams =
                                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                addView(buildTag("LOCAL PHOTO MESH", "#2b170d", "#8a4b1c"))
                                addView(spacer(dp(12)))
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Local Instagram"
                                        textSize = 34f
                                        setTextColor(Color.WHITE)
                                        setTypeface(Typeface.SERIF, Typeface.BOLD)
                                    },
                                )
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Take photos, keep nearby mode on, and linked phones sync your feed automatically over Wi-Fi Aware."
                                        textSize = 15f
                                        setTextColor(parseColor("#d1d8e9"))
                                        setPadding(0, dp(8), dp(12), 0)
                                    },
                                )
                            },
                        )
                        addView(
                            pageConfigButton,
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                        )
                    },
                )
                addView(spacer(dp(18)))
                addView(statRow(statusView, modeView))
                addView(spacer(dp(10)))
                addView(statRow(transportView, storageView))
            }

        configPage =
            cardContainer().apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Settings"
                                textSize = 24f
                                setTextColor(Color.WHITE)
                                setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                                layoutParams =
                                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            },
                        )
                        addView(settingsBackButton)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Nearby mode, reset actions, and the proof log live here. The feed page stays focused on photos."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(14))
                    },
                )
                addView(
                    miniCard(title = "This Phone", accent = parseColor("#fb923c")).apply {
                        addView(localSummarySettingsView)
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Camera only. Photos stay in app-private storage."
                                textSize = 12f
                                setTextColor(parseColor("#7f8da7"))
                                setPadding(0, dp(8), 0, 0)
                            },
                        )
                    },
                )
                addView(spacer(dp(10)))
                addView(
                    miniCard(title = "Nearby Peer", accent = parseColor("#38bdf8")).apply {
                        addView(nearbySummarySettingsView)
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Linked phones sync the full feed on connect, and new photos sync right after capture."
                                textSize = 12f
                                setTextColor(parseColor("#7f8da7"))
                                setPadding(0, dp(8), 0, 0)
                            },
                        )
                    },
                )
                addView(spacer(dp(14)))
                addView(
                    miniCard(title = "Nearby Mode", accent = parseColor("#0ea5e9")).apply {
                        addView(buttonRow(nearbyToggleButton))
                        addView(spacer(dp(10)))
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Connect keeps the nearby link active. Every linked peer gets the current feed automatically, and each new photo syncs as soon as it is stored."
                                textSize = 12f
                                setTextColor(parseColor("#7f8da7"))
                            },
                        )
                    },
                )
                addView(spacer(dp(10)))
                addView(
                    miniCard(title = "Reset + Cleanup", accent = parseColor("#7c3aed")).apply {
                        addView(buttonRow(clearDataButton, clearLogButton))
                        addView(spacer(dp(10)))
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Clearing demo data removes local and received photos on this phone."
                                textSize = 12f
                                setTextColor(parseColor("#7f8da7"))
                            },
                        )
                    },
                )
                addView(spacer(dp(18)))
                addView(sectionTitle("Proof Log"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Discovery, pairing, transfer, and nhash verification events land here."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(
                    miniCard(title = "Transport", accent = parseColor("#22c55e")).apply {
                        addView(logView)
                    },
                )
            }

        feedPage =
            cardContainer().apply {
                addView(sectionTitle("Photo Feed"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "One timeline. Your photos and nearby photos live together in newest-first order."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(
                    miniCard(title = "Quick Actions", accent = parseColor("#ec4899")).apply {
                        addView(buttonRow(takePhotoButton))
                        addView(spacer(dp(10)))
                        addView(localSummaryFeedView.apply { setPadding(0, 0, 0, dp(4)) })
                        addView(nearbySummaryFeedView)
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "As soon as nearby mode is connected, linked phones receive the current feed automatically. New photos sync right after capture."
                                textSize = 13f
                                setTextColor(parseColor("#d7def0"))
                                setPadding(0, dp(10), 0, 0)
                            },
                        )
                    },
                )
                addView(spacer(dp(14)))
                addView(feedContainer)
                addView(feedEmptyView)
            }

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
                addView(headerCard)
                addView(spacer(sectionSpacing))
                addView(feedPage)
                addView(spacer(sectionSpacing))
                addView(configPage)
                addView(spacer(dp(24)))
            }

        return ScrollView(this).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        parseColor("#1a120e"),
                        parseColor("#0d1220"),
                        parseColor("#05070c"),
                    ),
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
        val shouldSchedule =
            synchronized(renderStateLock) {
                pendingViewState = viewState
                if (renderScheduled) {
                    false
                } else {
                    renderScheduled = true
                    true
                }
            }
        if (shouldSchedule) {
            mainHandler.postDelayed(renderRunnable, renderDebounceMs)
        }
    }

    private fun renderView(viewState: ViewState) {
        statusView.text = viewState.statusText
        modeView.text = viewState.modeText
        transportView.text = viewState.linkText
        storageView.text = viewState.storageText
        localSummaryFeedView.text = viewState.localSummaryText
        nearbySummaryFeedView.text = viewState.nearbySummaryText
        localSummarySettingsView.text = viewState.localSummaryText
        nearbySummarySettingsView.text = viewState.nearbySummaryText

        val controls = viewState.controlsEnabled
        nearbyToggleButton.text =
            when {
                viewState.statusText.contains("Starting nearby") -> "Connecting..."
                viewState.modeText.contains("Nearby") -> "Disconnect"
                else -> "Connect"
            }
        applyControls(controls)
        applyPage(viewState.page)
        applyFeed(viewState.feedItems)
        applyLogs(viewState.logLines)
    }

    private fun applyControls(controls: ControlsEnabled) {
        setButtonState(takePhotoButton, controls.takePhoto)
        setButtonState(nearbyToggleButton, controls.toggleNearby)
        setButtonState(clearDataButton, controls.clearDemoData)
        setButtonState(clearLogButton, controls.clearLog)
    }

    private fun applyPage(page: UiPage) {
        configPage.visibility = if (page == UiPage.SETTINGS) View.VISIBLE else View.GONE
        feedPage.visibility = if (page == UiPage.FEED) View.VISIBLE else View.GONE
        setButtonState(pageConfigButton, page != UiPage.SETTINGS)
    }

    private fun applyFeed(feedItems: List<FeedItem>) {
        if (feedItems.isEmpty()) {
            renderedFeedSignature = ""
            feedContainer.removeAllViews()
            feedEmptyView.visibility = View.VISIBLE
            feedEmptyView.text = "No photos yet. Take a photo and it will be stored in your local feed immediately."
            return
        }

        val feedSignature =
            feedItems.joinToString("|") { item ->
                "${item.renderCachePath}:${item.createdAtMs}:${item.photoCid}:${item.sourceLabel}:${item.isLocal}"
            }
        if (feedSignature == renderedFeedSignature) {
            feedEmptyView.visibility = View.GONE
            return
        }

        renderedFeedSignature = feedSignature
        feedContainer.removeAllViews()
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

                rememberPendingCapture(CaptureRequest(outputPath, uri))
                startActivityForResult(intent, cameraRequestCode)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to launch camera", e)
                clearPendingCaptureRequest()
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
                val serverSocket = ServerSocket(0)
                responderServerSockets[command.connectionId] = serverSocket
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

                val responderNetworkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            dispatchAndroidEvent(AndroidEvent.ResponderNetworkAvailable(command.connectionId))
                        }

                        override fun onLost(network: Network) {
                            dispatchAndroidEvent(AndroidEvent.ResponderNetworkLost(command.connectionId))
                        }

                        override fun onUnavailable() {
                            dispatchAndroidEvent(AndroidEvent.ResponderNetworkLost(command.connectionId))
                        }
                    }
                responderNetworkCallbacks[command.connectionId] = responderNetworkCallback
                connectivityManager.requestNetwork(request, responderNetworkCallback)
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

            val initiatorNetworkCallback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        initiatorNetworks[command.connectionId] = network
                        dispatchAndroidEvent(AndroidEvent.InitiatorNetworkAvailable(command.connectionId))
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                        dispatchAndroidEvent(
                            AndroidEvent.InitiatorCapabilities(
                                command.connectionId,
                                awareInfo.port,
                                awareInfo.peerIpv6Addr?.hostAddress,
                            ),
                        )
                    }

                    override fun onLost(network: Network) {
                        dispatchAndroidEvent(AndroidEvent.InitiatorNetworkLost(command.connectionId))
                    }

                    override fun onUnavailable() {
                        dispatchAndroidEvent(AndroidEvent.InitiatorNetworkLost(command.connectionId))
                    }
                }
            initiatorNetworkCallbacks[command.connectionId] = initiatorNetworkCallback
            connectivityManager.requestNetwork(request, initiatorNetworkCallback)
        }
    }

    private fun executeConnectInitiatorSocket(command: AndroidCommand.ConnectInitiatorSocket) {
        ioExecutor.execute {
            try {
                val network = initiatorNetworks[command.connectionId] ?: throw IOException("Initiator network unavailable")
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
        socketWriters.put(connectionId, Executors.newSingleThreadExecutor())?.shutdownNow()
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
        val writer = socketWriters[command.connectionId] ?: return
        writer.execute {
            val resource = sockets[command.connectionId] ?: return@execute
            try {
                synchronized(resource.output) {
                    resource.output.write(command.bytes)
                    resource.output.flush()
                }
            } catch (e: IOException) {
                dispatchAndroidEvent(
                    AndroidEvent.SocketError(command.connectionId, e.message ?: "Socket write failed"),
                )
                closeSocket(command.connectionId)
            }
        }
    }

    private fun closeSocket(connectionId: Long) {
        var hadResources = false

        socketWriters.remove(connectionId)?.let {
            hadResources = true
            it.shutdownNow()
        }

        sockets.remove(connectionId)?.let { resource ->
            hadResources = true
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
        }

        responderServerSockets.remove(connectionId)?.let {
            hadResources = true
            try {
                it.close()
            } catch (_: Exception) {
            }
        }

        responderNetworkCallbacks.remove(connectionId)?.let {
            hadResources = true
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }

        initiatorNetworkCallbacks.remove(connectionId)?.let {
            hadResources = true
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }

        initiatorNetworks.remove(connectionId)?.let {
            hadResources = true
        }

        if (hadResources) {
            dispatchAndroidEvent(AndroidEvent.SocketClosed(connectionId))
        }
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
        }
        responderNetworkCallbacks.values.forEach {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        initiatorNetworkCallbacks.values.forEach {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        responderNetworkCallbacks.clear()
        initiatorNetworkCallbacks.clear()
        initiatorNetworks.clear()
        responderServerSockets.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        responderServerSockets.clear()
        sockets.keys.toList().forEach(::closeSocket)
    }

    private fun requiredRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun createPhotoCard(item: FeedItem): View {
        val sourceAccent =
            if (item.isLocal) {
                parseColor("#fb923c")
            } else {
                parseColor("#38bdf8")
            }
        val image =
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedFill(parseColor("#0d1422"), parseColor("#26344a"), dp(24))
                setImageBitmap(loadPreviewBitmap(File(item.renderCachePath), 1080, 720))
                clipToOutline = true
            }

        return cardContainer(
            colors = intArrayOf(parseColor("#15111e"), parseColor("#0d1321")),
            strokeColor = parseColor("#2a3550"),
            radiusDp = 28,
        ).apply {
            addView(
                buildTag(item.sourceLabel.uppercase(Locale.US), "#111827", colorToHex(sourceAccent)),
            )
            addView(spacer(dp(12)))
            addView(image)
            addView(spacer(dp(12)))
            addView(
                TextView(this@MainActivity).apply {
                    text = if (item.isLocal) "Your Photo" else "Nearby Photo"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.SERIF, Typeface.BOLD)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "${formatTimestamp(item.createdAtMs)}  ·  ${formatByteCount(item.sizeBytes.toLong())}"
                    textSize = 13f
                    setTextColor(parseColor("#d7def0"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(spacer(dp(10)))
            addView(
                tagRow(
                    buildTag(item.sourceLabel.uppercase(Locale.US), "#0f172a", "#334155"),
                    buildTag("cid ${item.photoCid.takeLast(12)}", "#0f172a", "#3b82f6"),
                ),
            )
        }
    }

    private fun rememberPendingCapture(captureRequest: CaptureRequest) {
        currentCaptureRequest = captureRequest
        persistPendingCaptureState(captureRequest.outputPath)
    }

    private fun clearPendingCaptureRequest() {
        currentCaptureRequest = null
        clearPendingCaptureState()
    }

    private fun restorePendingCaptureRequest(): CaptureRequest? {
        val pendingPath =
            readPendingCaptureState()?.takeIf { it.isNotBlank() }
                ?: return null
        return try {
            val outputFile = File(pendingPath)
            CaptureRequest(
                outputPath = pendingPath,
                uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile),
            )
        } catch (e: Exception) {
            Log.w(logTag, "Failed to restore pending capture request", e)
            clearPendingCaptureState()
            null
        }
    }

    private fun persistPendingCaptureState(outputPath: String) {
        try {
            pendingCaptureStateFile().parentFile?.mkdirs()
            pendingCaptureStateFile().writeText(outputPath)
        } catch (e: IOException) {
            Log.w(logTag, "Failed to persist pending capture state", e)
        }
    }

    private fun readPendingCaptureState(): String? =
        try {
            pendingCaptureStateFile()
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
        } catch (e: IOException) {
            Log.w(logTag, "Failed to read pending capture state", e)
            null
        }

    private fun clearPendingCaptureState() {
        try {
            pendingCaptureStateFile().delete()
        } catch (e: Exception) {
            Log.w(logTag, "Failed to clear pending capture state", e)
        }
    }

    private fun pendingCaptureStateFile(): File = File(filesDir, "hashtree/captures/pending-capture.txt")

    private fun revokeCaptureUri(captureRequest: CaptureRequest) {
        runOnMain {
            try {
                revokeUriPermission(
                    captureRequest.uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(logTag, "Failed to revoke camera URI permission", e)
            }
        }
    }

    private fun waitForCapturedFile(outputFile: File): Boolean {
        val deadline = SystemClock.elapsedRealtime() + captureReadyTimeoutMs
        var lastLength = -1L
        var stableReads = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            val length = if (outputFile.exists()) outputFile.length() else 0L
            if (length > 0L) {
                stableReads =
                    if (length == lastLength) {
                        stableReads + 1
                    } else {
                        0
                    }
                lastLength = length
                if (stableReads >= 2) {
                    return true
                }
            }
            Thread.sleep(captureReadyPollMs)
        }

        return outputFile.exists() && outputFile.length() > 0L
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

    private fun loadPreviewBitmap(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:$targetWidth:$targetHeight"
        previewBitmapCache.get(cacheKey)?.let { return it }
        val bitmap = decodePreviewBitmap(file, targetWidth, targetHeight)
        if (bitmap != null) {
            previewBitmapCache.put(cacheKey, bitmap)
        }
        return bitmap
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
            background = roundedFill(parseColor("#111827"), parseColor("#233047"), dp(20))
            setPadding(dp(14), dp(12), dp(14), dp(12))
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
        radiusDp: Int = 30,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    colors,
                ).apply {
                    cornerRadius = dp(radiusDp).toFloat()
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
                    cornerRadius = dp(22).toFloat()
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
            background = roundedFill(parseColor("#141d2d"), parseColor("#2d3a55"), dp(22))
            minimumHeight = dp(48)
        }

    private fun buildGhostButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(parseColor("#d7def0"))
            textSize = 15f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedFill(parseColor("#0d1422"), parseColor("#26344a"), dp(22))
            minimumHeight = dp(48)
        }

    private fun roundedFill(
        fillColor: Int,
        strokeColor: Int,
        cornerRadiusPx: Int = dp(20),
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx.toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }

    private fun buildTag(
        text: String,
        fillHex: String,
        strokeHex: String,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = roundedFill(parseColor(fillHex), parseColor(strokeHex), dp(18))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

    private fun tagRow(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            views.forEachIndexed { index, view ->
                addView(view)
                if (index != views.lastIndex) {
                    addView(spacerWidth(dp(8)))
                }
            }
        }

    private fun miniCard(
        title: String,
        accent: Int,
    ): LinearLayout =
        cardContainer(
            colors = intArrayOf(parseColor("#0f1724"), parseColor("#101827")),
            strokeColor = parseColor("#243247"),
            radiusDp = 24,
        ).apply {
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(44), dp(3)).apply {
                            topMargin = dp(8)
                            bottomMargin = dp(12)
                        }
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(2).toFloat()
                            setColor(accent)
                        }
                },
            )
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

    private fun spacerWidth(widthPx: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        }

    private fun setButtonState(
        button: Button,
        enabled: Boolean,
    ) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun stylePageButton(
        button: Button,
        selected: Boolean,
    ) {
        button.alpha = 1f
        button.setTextColor(if (selected) Color.WHITE else parseColor("#b3c0d9"))
        button.background =
            if (selected) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(parseColor("#f97316"), parseColor("#fb7185")),
                ).apply {
                    cornerRadius = dp(18).toFloat()
                }
            } else {
                roundedFill(parseColor("#0f1724"), parseColor("#223047"), dp(18))
            }
    }

    private fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

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
