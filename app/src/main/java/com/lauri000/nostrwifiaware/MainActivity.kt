package com.lauri000.nostrwifiaware

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Role {
        IDLE,
        PEER,
    }

    private enum class DataPathRole {
        NONE,
        RESPONDER,
        INITIATOR,
    }

    private data class TrackAck(
        val success: Boolean,
        val actualNhash: String?,
        val alreadyPresent: Boolean,
        val message: String,
    )

    companion object {
        private const val serviceName = "nostr-wifi-aware"
        private const val permissionRequestCode = 1001
        private const val logTag = "NostrWifiAware"
        private const val securePassphrase = "awarebenchpass123"
        private const val tcpProtocol = 6
        private const val ioBufferSize = 256 * 1024
        private const val commandSet = "SET"
        private const val commandTrack = "TRACK"
        private const val commandDone = "DONE"
        private const val commandAck = "ACK"
        private const val fetchMessagePrefix = "fetch:"
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
    private lateinit var storageView: TextView
    private lateinit var localSummaryView: TextView
    private lateinit var receivedSummaryView: TextView
    private lateinit var heroArtView: TextView
    private lateinit var heroEyebrowView: TextView
    private lateinit var heroTitleView: TextView
    private lateinit var heroMetaView: TextView
    private lateinit var heroDetailView: TextView
    private lateinit var heroActionButton: Button
    private lateinit var searchInput: EditText
    private lateinit var localTracksContainer: LinearLayout
    private lateinit var receivedTracksContainer: LinearLayout
    private lateinit var localEmptyView: TextView
    private lateinit var receivedEmptyView: TextView
    private lateinit var logView: TextView
    private lateinit var seedSetAButton: Button
    private lateinit var seedSetBButton: Button
    private lateinit var startNearbyButton: Button
    private lateinit var fetchPeerButton: Button
    private lateinit var shareNowButton: Button
    private lateinit var clearDataButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearLogButton: Button

    private var role = Role.IDLE
    private var pendingRole: Role? = null
    private var dataPathRole = DataPathRole.NONE
    private var transportStatus = "Idle"
    private var searchQuery = ""
    private var selectedTrackNhash: String? = null
    private var playingTrackNhash: String? = null

    private var localTracks: List<AudioTrackInfo> = emptyList()
    private var receivedTracks: List<AudioTrackInfo> = emptyList()

    private var mediaPlayer: MediaPlayer? = null

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var nextMessageId = 1
    private var publishPeerHandle: PeerHandle? = null
    private var subscribePeerHandle: PeerHandle? = null
    private var remotePeerInstance: String? = null
    private var helloSent = false

    private var hostNetwork: Network? = null
    private var clientNetwork: Network? = null
    private var hostNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var clientNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var hostServerSocket: ServerSocket? = null
    private var hostSocket: Socket? = null

    private var clientSocket: Socket? = null
    private var connectedSocket: Socket? = null
    private var connectedInput: DataInputStream? = null
    private var connectedOutput: DataOutputStream? = null
    private var clientPeerIpv6: Inet6Address? = null
    private var clientPeerPort = 0
    private val pendingTrackAcks = LinkedBlockingQueue<TrackAck>()
    private val socketWriteLock = Any()

    @Volatile
    private var clientSocketConnecting = false

    @Volatile
    private var transferInFlight = false

    @Volatile
    private var pendingRemoteFetchRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        demoStore = HashtreeDemoStore(this)
        reloadTrackState()

        setContentView(buildUi())
        setStatus("Idle")
        updateRoleView()
        updateTransportView()
        updateTrackViews()
        updateControls()
        appendLog(
            "Audio-first nearby demo loaded. Seed a local set, attach two phones over Wi-Fi Aware, then fetch or share the set over the data path.",
        )
    }

    override fun onDestroy() {
        stopAll("Activity destroyed")
        releasePlayer()
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

    private fun buildUi(): ScrollView {
        val contentPadding = dp(20)
        val sectionSpacing = dp(18)

        statusView = buildStatPill()
        roleView = buildStatPill()
        transportView = buildStatPill()
        storageView = buildStatPill()
        localSummaryView = TextView(this)
        receivedSummaryView = TextView(this)

        heroArtView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
        }
        heroEyebrowView = TextView(this).apply {
            textSize = 12f
            setTextColor(parseColor("#9fb2ce"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        heroTitleView = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        heroMetaView = TextView(this).apply {
            textSize = 15f
            setTextColor(parseColor("#d7def0"))
        }
        heroDetailView = TextView(this).apply {
            textSize = 13f
            setTextColor(parseColor("#9fb2ce"))
        }
        heroActionButton = buildAccentButton("Play", "#f59e0b", "#f97316").apply {
            setOnClickListener { selectedDisplayTrack()?.let(::togglePlayback) }
        }

        searchInput = EditText(this).apply {
            hint = "Search title, artist, or album"
            setHintTextColor(parseColor("#7f8aa3"))
            setTextColor(Color.WHITE)
            background = roundedFill(parseColor("#0d1422"), parseColor("#26344a"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        searchQuery = s?.toString().orEmpty()
                        updateTrackViews()
                    }
                },
            )
        }

        seedSetAButton = buildAccentButton("Seed Set A", "#ff784f", "#ffd166").apply {
            setOnClickListener { seedAudioSet(AudioSetId.SET_A) }
        }
        seedSetBButton = buildAccentButton("Seed Set B", "#ec4899", "#8b5cf6").apply {
            setOnClickListener { seedAudioSet(AudioSetId.SET_B) }
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
        shareNowButton = buildMutedButton("Share Available Shelf").apply {
            setOnClickListener { sendSeededSet("manual share") }
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

        localTracksContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        receivedTracksContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localEmptyView = buildEmptyText()
        receivedEmptyView = buildEmptyText()

        logView = TextView(this).apply {
            setTextColor(parseColor("#b7c4da"))
            textSize = 12f
            setTextIsSelectable(true)
            setTypeface(Typeface.MONOSPACE)
        }

        val headerCard =
            cardContainer().apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Nearby Audio"
                                textSize = 30f
                                setTextColor(Color.WHITE)
                                setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                            },
                        )
                        addView(
                            TextView(this@MainActivity).apply {
                                text =
                                    "A Wi-Fi Aware music shelf for deterministic hashtree-addressed audio."
                                textSize = 14f
                                setTextColor(parseColor("#9fb2ce"))
                                setPadding(0, dp(6), 0, dp(14))
                            },
                        )
                        addView(statRow(statusView, roleView))
                        addView(
                            spacer(dp(10)),
                        )
                        addView(statRow(transportView, storageView))
                    },
                )
            }

        val heroCard =
            cardContainer(
                colors = intArrayOf(parseColor("#171d31"), parseColor("#0d1220")),
                strokeColor = parseColor("#2f4568"),
            ).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(heroArtView)
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(16), 0, 0, 0)
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    )
                                addView(heroEyebrowView)
                                addView(
                                    heroTitleView.apply {
                                        setPadding(0, dp(6), 0, 0)
                                    },
                                )
                                addView(
                                    heroMetaView.apply {
                                        setPadding(0, dp(6), 0, 0)
                                    },
                                )
                                addView(
                                    heroDetailView.apply {
                                        setPadding(0, dp(8), 0, 0)
                                    },
                                )
                            },
                        )
                    },
                )
                addView(spacer(dp(16)))
                addView(heroActionButton)
            }

        val actionCard =
            cardContainer().apply {
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Transport Controls"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text =
                            "Seed one or both sets on either phone, then tap Start Nearby on both. The app picks who initiates the Wi-Fi Aware data path underneath, so you can fetch or share without choosing host/client roles."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(14))
                    },
                )
                addView(localSummaryView)
                addView(receivedSummaryView.apply { setPadding(0, dp(6), 0, dp(14)) })
                addView(buttonRow(seedSetAButton, seedSetBButton))
                addView(spacer(dp(10)))
                addView(buttonRow(startNearbyButton, stopButton))
                addView(spacer(dp(10)))
                addView(buttonRow(fetchPeerButton, shareNowButton))
                addView(spacer(dp(10)))
                addView(buttonRow(clearDataButton, clearLogButton))
            }

        val searchCard =
            cardContainer().apply {
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Search"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Filter both local and fetched shelves instantly."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(searchInput)
            }

        val localSection =
            cardContainer().apply {
                addView(sectionTitle("Seeded On This Phone"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "This phone can share anything it has seeded or already fetched from another nearby peer."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(localTracksContainer)
                addView(localEmptyView)
            }

        val receivedSection =
            cardContainer().apply {
                addView(sectionTitle("Fetched Nearby"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Every imported file is recomputed and accepted only if the hashtree nhash matches."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(receivedTracksContainer)
                addView(receivedEmptyView)
            }

        val logSection =
            cardContainer(
                colors = intArrayOf(parseColor("#0a0f18"), parseColor("#05080d")),
                strokeColor = parseColor("#233047"),
            ).apply {
                addView(sectionTitle("Transport Log"))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "This is the ground truth when you want to verify the transfer really crossed the Wi-Fi Aware data path."
                        textSize = 13f
                        setTextColor(parseColor("#9fb2ce"))
                        setPadding(0, dp(6), 0, dp(12))
                    },
                )
                addView(logView)
            }

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
                addView(headerCard)
                addView(spacer(sectionSpacing))
                addView(heroCard)
                addView(spacer(sectionSpacing))
                addView(actionCard)
                addView(spacer(sectionSpacing))
                addView(searchCard)
                addView(spacer(sectionSpacing))
                addView(localSection)
                addView(spacer(sectionSpacing))
                addView(receivedSection)
                addView(spacer(sectionSpacing))
                addView(logSection)
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

    private fun seedAudioSet(setId: AudioSetId) {
        if (role != Role.IDLE) {
            appendLog("Stop Wi-Fi Aware before reseeding audio.")
            return
        }

        try {
            localTracks = demoStore.seedLocalSet(setId)
            selectedTrackNhash =
                localTracks.firstOrNull { it.setLabel == setId.label }?.nhash
                    ?: localTracks.firstOrNull()?.nhash
            updateTrackViews()
            updateControls()
            appendLog("Added ${setId.label} to the local shelf. Local shelf now has ${localTracks.size} tracks.")
            AudioDemoCatalog.tracksForSet(setId).forEach { spec ->
                val track = localTracks.firstOrNull { it.id == spec.id } ?: return@forEach
                appendLog(
                    "Seeded ${track.id} (${track.title}) nhash=${track.nhash} size=${formatByteCount(track.sizeBytes)}",
                )
            }
        } catch (e: Exception) {
            appendLog("Failed to seed ${setId.label}: ${e.message}")
        }
    }

    private fun clearDemoData() {
        if (role != Role.IDLE) {
            stopAll("Stopped for demo data clear")
        }

        releasePlayer()
        demoStore.clearAll()
        selectedTrackNhash = null
        reloadTrackState()
        updateTrackViews()
        updateControls()
        appendLog("Cleared all demo audio data.")
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
            appendLog(
                "Wi-Fi Aware is unavailable. Check Wi-Fi, location services, or hotspot/tethering state.",
            )
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
        pendingTrackAcks.clear()
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
                        Role.PEER -> {
                            startPublishPeer(session)
                            startSubscribePeer(session)
                        }
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
                        appendLog("Nearby peer requested this phone's seeded shelf.")
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
                        appendLog("Discovered ${peerLabel(peerHandle)}, but it did not advertise an app instance. Waiting for an incoming hello.")
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
                        pendingRemoteFetchRequest = true
                        rememberPeer(peerHandle, remotePeerInstance, isPublishHandle = false)
                        appendLog("Nearby peer requested this phone's seeded shelf.")
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
        if (!text.startsWith("peer:")) {
            return null
        }
        return text.removePrefix("peer:").takeIf { it.isNotBlank() }
    }

    private fun shouldInitiateDataPath(remoteInstance: String): Boolean {
        return appInstance < remoteInstance
    }

    private fun maybeSendHello(
        peerHandle: PeerHandle,
        remoteInstance: String,
    ) {
        if (helloSent || hostNetworkCallback != null || clientNetworkCallback != null || connectedSocket != null || clientSocketConnecting) {
            return
        }

        helloSent = true
        dataPathRole = DataPathRole.INITIATOR
        setTransportStatus("Initiating Wi-Fi Aware data path")
        appendLog("Tie-break selected this phone to initiate the Wi-Fi Aware data path with $remoteInstance.")
        sendMessage(
            session = subscribeSession,
            peerHandle = peerHandle,
            payload = "hello:$appInstance",
            reason = "peer hello",
        )
    }

    private fun handleResponderHello(
        peerHandle: PeerHandle,
        remoteInstance: String?,
    ) {
        rememberPeer(peerHandle, remoteInstance, isPublishHandle = true)
        if (hostNetworkCallback != null) {
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
            closeHostSockets()
            val serverSocket = ServerSocket(0)
            hostServerSocket = serverSocket
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
                        hostNetwork = network
                        setTransportStatus("Wi-Fi Aware data path available")
                        appendLog("Responder Wi-Fi Aware data path available.")
                    }

                    override fun onLost(network: Network) {
                        if (network == hostNetwork) {
                            appendLog("Responder data path lost.")
                            setTransportStatus("Wi-Fi Aware data path lost")
                            hostNetwork = null
                            clearHostNetworkRequest()
                            closeHostSockets()
                        }
                    }

                    override fun onUnavailable() {
                        appendLog("Responder data path unavailable.")
                        setTransportStatus("Wi-Fi Aware data path unavailable")
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
                reason = "responder requested data path",
            )
        } catch (e: IOException) {
            appendLog("Responder setup failed: ${e.message}")
            setTransportStatus("Wi-Fi Aware setup failed")
            clearHostNetworkRequest()
            closeHostSockets()
        }
    }

    private fun acceptResponderConnections(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed && role == Role.PEER) {
            try {
                val socket = serverSocket.accept()
                hostSocket?.close()
                hostSocket = socket
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
        connectionLabel: String,
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
            pendingTrackAcks.clear()
            setTransportStatus("Wi-Fi Aware peer socket connected")
            appendLog("$connectionLabel Wi-Fi Aware TCP socket connected.")
            updateControls()
            ioExecutor.execute { readConnectedSocket(socket, input, output, connectionLabel) }
        }
        maybeStartPendingFetch()
    }

    private fun readConnectedSocket(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        connectionLabel: String,
    ) {
        try {
            var activeSetLabel = "Unknown Set"

            while (!socket.isClosed && role == Role.PEER) {
                val command =
                    try {
                        input.readUTF()
                    } catch (_: EOFException) {
                        appendLog("Peer audio stream closed.")
                        break
                    }

                when (command) {
                    commandSet -> {
                        activeSetLabel = input.readUTF()
                        val trackCount = input.readInt()
                        appendLog("Receiving Wi-Fi Aware audio $activeSetLabel with $trackCount tracks.")
                    }

                    commandTrack -> {
                        val trackId = input.readUTF()
                        val trackSetLabel = input.readUTF()
                        val announcedNhash = input.readUTF()
                        val expectedBytes = input.readLong()
                        if (expectedBytes <= 0L) {
                            appendLog("Received invalid track size $expectedBytes for $trackId.")
                            break
                        }

                        appendLog(
                            "Receiving track $trackId from $trackSetLabel inside $activeSetLabel nhash=$announcedNhash (${formatByteCount(expectedBytes)}) over Wi-Fi Aware",
                        )
                        val tempFile = demoStore.createIncomingTempFile(trackId)
                        var remaining = expectedBytes

                        FileOutputStream(tempFile).use { fileOutput ->
                            while (remaining > 0) {
                                val chunk = min(ioBuffer.size.toLong(), remaining).toInt()
                                val read = input.read(ioBuffer, 0, chunk)
                                if (read < 0) {
                                    throw EOFException("Peer disconnected mid-track transfer.")
                                }
                                fileOutput.write(ioBuffer, 0, read)
                                remaining -= read.toLong()
                            }
                        }

                        val result =
                            try {
                                demoStore.verifyAndStoreReceivedTrack(
                                    tempFile = tempFile,
                                    trackId = trackId,
                                    announcedNhash = announcedNhash,
                                    setLabel = trackSetLabel,
                                )
                            } catch (e: Exception) {
                                tempFile.delete()
                                StoredTrackResult(
                                    success = false,
                                    actualNhash = null,
                                    track = null,
                                    alreadyPresent = false,
                                    message = "Failed to verify received track $trackId: ${e.message}",
                                )
                            }

                        if (result.success) {
                            receivedTracks = demoStore.receivedTracks()
                            if (selectedTrackNhash == null) {
                                selectedTrackNhash = result.track?.nhash
                            }
                            updateTrackViews()
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
                        val setLabel = input.readUTF()
                        val trackCount = input.readInt()
                        appendLog("Finished receiving Wi-Fi Aware audio $setLabel ($trackCount tracks).")
                    }

                    commandAck -> {
                        pendingTrackAcks.offer(
                            TrackAck(
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
                appendLog("$connectionLabel socket error: ${e.message}")
                setTransportStatus("Wi-Fi Aware socket error")
            }
        } finally {
            safeClose(socket)
            if (hostSocket === socket) {
                hostSocket = null
            }
            if (clientSocket === socket) {
                clientSocket = null
            }
            if (connectedSocket === socket) {
                connectedSocket = null
                connectedInput = null
                connectedOutput = null
            }
            appendLog("$connectionLabel TCP socket closed.")
            updateControls()
        }
    }

    private fun handleInitiatorReady(peerHandle: PeerHandle) {
        if (clientNetworkCallback != null || clientSocket != null) {
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
                    clientNetwork = network
                    setTransportStatus("Wi-Fi Aware data path available")
                    appendLog("Initiator Wi-Fi Aware data path available.")
                    maybeConnectClientSocket()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val awareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                    clientPeerIpv6 = awareInfo.peerIpv6Addr
                    clientPeerPort = awareInfo.port
                    appendLog(
                        "Initiator learned responder endpoint port=${awareInfo.port} ipv6=${awareInfo.peerIpv6Addr?.hostAddress ?: "null"}",
                    )
                    maybeConnectClientSocket()
                }

                override fun onLost(network: Network) {
                    if (network == clientNetwork) {
                        appendLog("Initiator data path lost.")
                        setTransportStatus("Wi-Fi Aware data path lost")
                        clientNetwork = null
                        clientPeerIpv6 = null
                        clientPeerPort = 0
                        clearClientNetworkRequest()
                        closeClientSocket()
                    }
                }

                override fun onUnavailable() {
                    appendLog("Initiator data path unavailable.")
                    setTransportStatus("Wi-Fi Aware data path unavailable")
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
                appendLog("Initiator opening Wi-Fi Aware TCP socket to [${peerIpv6.hostAddress}]:$peerPort")
                val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
                clientSocket = socket
                clientSocketConnecting = false
                bindConnectedSocket(socket, "Initiator")
            } catch (e: IOException) {
                clientSocketConnecting = false
                appendLog("Initiator socket connect failed: ${e.message}")
                setTransportStatus("Wi-Fi Aware socket connect failed")
                closeClientSocket()
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

        if (!sendMessageToActivePeer("$fetchMessagePrefix$appInstance", "peer requested seeded set")) {
            appendLog("No nearby peer discovered yet.")
            return
        }
        appendLog("Requested the nearby peer's available shelf.")
    }

    private fun maybeStartPendingFetch() {
        if (
            role == Role.PEER &&
            pendingRemoteFetchRequest &&
            connectedSocket != null &&
            !clientSocketConnecting &&
            !transferInFlight
        ) {
            pendingRemoteFetchRequest = false
            sendSeededSet("nearby fetch request")
        }
    }

    private fun sendSeededSet(trigger: String) {
        if (role != Role.PEER) {
            appendLog("Start Nearby first.")
            return
        }

        if (transferInFlight) {
            appendLog("A set transfer is already in flight.")
            return
        }

        localTracks = demoStore.currentLocalTracks()
        receivedTracks = demoStore.receivedTracks()
        val availableTracks = shareableTracks()
        if (availableTracks.isEmpty()) {
            appendLog("Seed Set A or Set B on this phone first, or fetch tracks from a nearby peer.")
            updateTrackViews()
            return
        }

        val output = connectedOutput
        if (connectedSocket == null || output == null) {
            appendLog("The Wi-Fi Aware peer socket is not connected yet.")
            return
        }

        val setLabel = shelfLabel(availableTracks)
        transferInFlight = true
        pendingTrackAcks.clear()
        updateControls()

        ioExecutor.execute {
            try {
                appendLog("Sending Wi-Fi Aware audio $setLabel with ${availableTracks.size} tracks ($trigger).")
                synchronized(socketWriteLock) {
                    output.writeUTF(commandSet)
                    output.writeUTF(setLabel)
                    output.writeInt(availableTracks.size)
                    output.flush()
                }

                for (track in availableTracks.sortedBy { it.id }) {
                    appendLog(
                        "Sending ${track.id} (${track.title}) nhash=${track.nhash} size=${formatByteCount(track.sizeBytes)} over Wi-Fi Aware",
                    )
                    synchronized(socketWriteLock) {
                        output.writeUTF(commandTrack)
                        output.writeUTF(track.id)
                        output.writeUTF(track.setLabel)
                        output.writeUTF(track.nhash)
                        output.writeLong(track.sizeBytes)

                        FileInputStream(track.file).use { fileInput ->
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
                        pendingTrackAcks.poll(20, TimeUnit.SECONDS)
                            ?: throw IOException("Timed out waiting for peer ACK for ${track.id}.")
                    appendLog(
                        "Peer reply for ${track.id}: success=${ack.success} actualNhash=${ack.actualNhash ?: "n/a"} alreadyPresent=${ack.alreadyPresent}",
                    )
                    appendLog(ack.message)
                    if (!ack.success) {
                        appendLog("Stopping set transfer because the peer rejected ${track.id}.")
                        break
                    }
                }

                synchronized(socketWriteLock) {
                    output.writeUTF(commandDone)
                    output.writeUTF(setLabel)
                    output.writeInt(availableTracks.size)
                    output.flush()
                }
                appendLog("Finished sending Wi-Fi Aware audio $setLabel.")
            } catch (e: IOException) {
                appendLog("Set transfer failed: ${e.message}")
                setTransportStatus("Set transfer failed")
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
        pendingTrackAcks.clear()

        clearClientNetworkRequest()
        clearHostNetworkRequest()
        closeConnectedSocket()
        closeClientSocket()
        closeHostSockets()

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
        safeClose(clientSocket)
        clientSocket = null
        clientSocketConnecting = false
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

    private fun setStatus(status: String) {
        onMain {
            statusView.text = "STATUS  $status"
        }
    }

    private fun setTransportStatus(status: String) {
        transportStatus = status
        updateTransportView()
    }

    private fun updateRoleView() {
        onMain {
            roleView.text = "MODE  ${roleLabel(role)}"
        }
    }

    private fun updateTransportView() {
        onMain {
            transportView.text = "LINK  $transportStatus"
        }
    }

    private fun updateTrackViews() {
        onMain {
            val filteredLocal = filteredTracks(localTracks)
            val filteredReceived = filteredTracks(receivedTracks)
            val activeTrack = selectedDisplayTrack(filteredLocal, filteredReceived)

            val localSetLabel = shelfLabel(localTracks)
            val receivedSetLabel = shelfLabel(receivedTracks)
            localSummaryView.text = "Local shelf: ${localTracks.size} tracks  ·  $localSetLabel"
            localSummaryView.setTextColor(parseColor("#d7def0"))
            localSummaryView.textSize = 14f
            receivedSummaryView.text =
                "Nearby shelf: ${receivedTracks.size} fetched  ·  $receivedSetLabel  ·  ${formatByteCount(demoStore.totalStorageBytes())}"
            receivedSummaryView.setTextColor(parseColor("#d7def0"))
            receivedSummaryView.textSize = 14f
            storageView.text = "STORAGE  ${formatByteCount(demoStore.totalStorageBytes())}"

            bindHero(activeTrack)
            renderTrackSection(
                container = localTracksContainer,
                emptyView = localEmptyView,
                tracks = filteredLocal,
                emptyMessage = if (localTracks.isEmpty()) {
                    "No seeded shelf yet. Use Seed Set A or Seed Set B to create local audio."
                } else {
                    "No local tracks match \"$searchQuery\"."
                },
                sourceLabel = "Local",
            )
            renderTrackSection(
                container = receivedTracksContainer,
                emptyView = receivedEmptyView,
                tracks = filteredReceived,
                emptyMessage = if (receivedTracks.isEmpty()) {
                    "Nothing fetched yet. Tap Start Nearby on both phones, wait for the Wi-Fi Aware peer socket, then tap Fetch From Peer."
                } else {
                    "No fetched tracks match \"$searchQuery\"."
                },
                sourceLabel = "Nearby",
            )
        }
    }

    private fun updateControls() {
        onMain {
            seedSetAButton.isEnabled = role == Role.IDLE
            seedSetBButton.isEnabled = role == Role.IDLE
            clearDataButton.isEnabled = !transferInFlight
            startNearbyButton.isEnabled = role == Role.IDLE
            stopButton.isEnabled = role != Role.IDLE
            shareNowButton.isEnabled =
                role == Role.PEER &&
                connectedSocket != null &&
                !clientSocketConnecting &&
                !transferInFlight &&
                shareableTracks().isNotEmpty()
            fetchPeerButton.isEnabled =
                role == Role.PEER &&
                connectedSocket != null &&
                (publishPeerHandle != null || subscribePeerHandle != null) &&
                !transferInFlight
            heroActionButton.isEnabled = selectedDisplayTrack() != null
        }
    }

    private fun reloadTrackState() {
        localTracks = demoStore.currentLocalTracks()
        receivedTracks = demoStore.receivedTracks()
    }

    private fun filteredTracks(tracks: List<AudioTrackInfo>): List<AudioTrackInfo> {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            return tracks.sortedBy { it.title.lowercase(Locale.US) }
        }
        val needle = trimmed.lowercase(Locale.US)
        return tracks.filter { track ->
            track.title.lowercase(Locale.US).contains(needle) ||
                track.artist.lowercase(Locale.US).contains(needle) ||
                track.album.lowercase(Locale.US).contains(needle)
        }.sortedBy { it.title.lowercase(Locale.US) }
    }

    private fun shareableTracks(): List<AudioTrackInfo> {
        val deduped = LinkedHashMap<String, AudioTrackInfo>()
        (localTracks + receivedTracks).forEach { track ->
            if (!deduped.containsKey(track.nhash)) {
                deduped[track.nhash] = track
            }
        }
        return deduped.values.sortedBy { it.id }
    }

    private fun selectedDisplayTrack(
        filteredLocal: List<AudioTrackInfo> = filteredTracks(localTracks),
        filteredReceived: List<AudioTrackInfo> = filteredTracks(receivedTracks),
    ): AudioTrackInfo? {
        val candidates = filteredLocal + filteredReceived
        val selected = selectedTrackNhash?.let { nhash -> candidates.firstOrNull { it.nhash == nhash } }
        if (selected != null) {
            return selected
        }
        val fallback = candidates.firstOrNull()
        selectedTrackNhash = fallback?.nhash
        return fallback
    }

    private fun bindHero(track: AudioTrackInfo?) {
        if (track == null) {
            heroArtView.text = "--"
            heroArtView.background = roundedFill(parseColor("#111827"), parseColor("#24324a"))
            heroEyebrowView.text = "Seed a shelf or fetch nearby"
            heroTitleView.text = "No active track"
            heroMetaView.text = "This screen turns into a player as soon as you have local or fetched audio."
            heroDetailView.text = "Both phones can start nearby mode, connect as peers, and fetch or share over Wi-Fi Aware."
            heroActionButton.text = "Play"
            heroActionButton.isEnabled = false
            return
        }

        val spec = AudioDemoCatalog.specForId(track.id)
        heroArtView.text = spec?.coverSeed ?: track.id.take(2).uppercase(Locale.US)
        heroArtView.background =
            GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(
                    parseColor(spec?.accentHex ?: "#334155"),
                    parseColor(spec?.secondaryAccentHex ?: "#64748b"),
                ),
            ).apply {
                cornerRadius = dp(28).toFloat()
            }
        heroEyebrowView.text =
            if (receivedTracks.any { it.nhash == track.nhash }) {
                "Fetched via Wi-Fi Aware"
            } else {
                "Seeded on this phone"
            }
        heroTitleView.text = track.title
        heroMetaView.text = "${track.artist}  ·  ${track.album}"
        heroDetailView.text =
            buildString {
                append(spec?.genre ?: "Audio demo")
                append("  ·  ")
                append(track.setLabel)
                append("  ·  ")
                append(track.nhash.takeLast(10))
            }
        heroActionButton.text =
            if (playingTrackNhash == track.nhash) {
                "Stop Playback"
            } else {
                "Play Track"
            }
        heroActionButton.isEnabled = true
    }

    private fun renderTrackSection(
        container: LinearLayout,
        emptyView: TextView,
        tracks: List<AudioTrackInfo>,
        emptyMessage: String,
        sourceLabel: String,
    ) {
        container.removeAllViews()
        if (tracks.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = emptyMessage
            return
        }

        emptyView.visibility = View.GONE
        tracks.forEachIndexed { index, track ->
            container.addView(createTrackRow(track, sourceLabel))
            if (index != tracks.lastIndex) {
                container.addView(spacer(dp(10)))
            }
        }
    }

    private fun createTrackRow(
        track: AudioTrackInfo,
        sourceLabel: String,
    ): View {
        val spec = AudioDemoCatalog.specForId(track.id)
        val accent = parseColor(spec?.accentHex ?: "#334155")
        val secondary = parseColor(spec?.secondaryAccentHex ?: "#64748b")
        val isSelected = selectedTrackNhash == track.nhash
        val isPlaying = playingTrackNhash == track.nhash

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            adjustAlpha(accent, if (isSelected) 0.26f else 0.16f),
                            adjustAlpha(secondary, if (isSelected) 0.16f else 0.08f),
                        ),
                    ).apply {
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(if (isSelected) 2 else 1), adjustAlpha(secondary, 0.55f))
                    }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener {
                    selectedTrackNhash = track.nhash
                    updateTrackViews()
                }
            }

        row.addView(
            TextView(this).apply {
                text = spec?.coverSeed ?: track.id.take(2).uppercase(Locale.US)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.BL_TR,
                        intArrayOf(accent, secondary),
                    ).apply {
                        cornerRadius = dp(18).toFloat()
                    }
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            },
        )

        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                addView(
                    TextView(this@MainActivity).apply {
                        text = track.title
                        textSize = 17f
                        setTextColor(Color.WHITE)
                        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "${track.artist}  ·  ${track.album}"
                        textSize = 13f
                        setTextColor(parseColor("#d7def0"))
                        setPadding(0, dp(4), 0, 0)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "$sourceLabel  ·  ${track.setLabel}  ·  ${track.nhash.takeLast(10)}"
                        textSize = 11f
                        setTextColor(parseColor("#9fb2ce"))
                        setTypeface(Typeface.MONOSPACE)
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            },
        )

        row.addView(
            buildAccentButton(
                if (isPlaying) {
                    "Stop"
                } else {
                    "Play"
                },
                spec?.accentHex ?: "#334155",
                spec?.secondaryAccentHex ?: "#64748b",
            ).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    selectedTrackNhash = track.nhash
                    togglePlayback(track)
                }
            },
        )

        return row
    }

    private fun togglePlayback(track: AudioTrackInfo) {
        if (playingTrackNhash == track.nhash) {
            stopPlayback("Stopped playback for ${track.title}.")
            return
        }

        try {
            releasePlayer()
            val player =
                MediaPlayer().apply {
                    setDataSource(track.file.absolutePath)
                    setOnCompletionListener {
                        releasePlayer()
                        appendLog("Playback finished for ${track.title}.")
                        updateTrackViews()
                    }
                    prepare()
                    start()
                }
            mediaPlayer = player
            playingTrackNhash = track.nhash
            selectedTrackNhash = track.nhash
            appendLog("Playing ${track.title} from ${track.file.absolutePath}.")
            updateTrackViews()
        } catch (e: Exception) {
            releasePlayer()
            appendLog("Playback failed for ${track.title}: ${e.message}")
            updateTrackViews()
        }
    }

    private fun stopPlayback(logMessage: String? = null) {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        releasePlayer()
        if (logMessage != null) {
            appendLog(logMessage)
        }
        updateTrackViews()
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingTrackNhash = null
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
            Role.PEER -> "Nearby"
        }
    }

    private fun shelfLabel(tracks: List<AudioTrackInfo>): String {
        if (tracks.isEmpty()) {
            return "none"
        }
        return tracks
            .map { it.setLabel }
            .distinct()
            .sorted()
            .joinToString(" + ")
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

    private fun buildStatPill(): TextView {
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = roundedFill(parseColor("#111827"), parseColor("#233047"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun cardContainer(
        colors: IntArray = intArrayOf(parseColor("#121929"), parseColor("#0d1321")),
        strokeColor: Int = parseColor("#233047"),
    ): LinearLayout {
        return LinearLayout(this).apply {
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
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    private fun buildAccentButton(
        text: String,
        startHex: String,
        endHex: String,
    ): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 14f
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(parseColor(startHex), parseColor(endHex)),
                ).apply {
                    cornerRadius = dp(20).toFloat()
                }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
    }

    private fun buildMutedButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 14f
            background = roundedFill(parseColor("#192235"), parseColor("#31435e"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
    }

    private fun buildGhostButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(parseColor("#d7def0"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 13f
            background = roundedFill(adjustAlpha(parseColor("#0b1220"), 0.8f), parseColor("#233047"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
    }

    private fun buildEmptyText(): TextView {
        return TextView(this).apply {
            textSize = 13f
            setTextColor(parseColor("#9fb2ce"))
            setPadding(0, dp(4), 0, 0)
        }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            buttons.forEachIndexed { index, button ->
                val params =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                if (index > 0) {
                    params.marginStart = dp(10)
                }
                addView(button, params)
            }
        }
    }

    private fun statRow(
        left: TextView,
        right: TextView,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                left,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                },
            )
            addView(
                right,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                },
            )
        }
    }

    private fun roundedFill(
        fillColor: Int,
        strokeColor: Int,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun spacer(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun parseColor(value: String): Int = Color.parseColor(value)

    private fun adjustAlpha(
        color: Int,
        factor: Float,
    ): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
