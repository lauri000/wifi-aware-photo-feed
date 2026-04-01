package com.lauri000.nostrwifiaware

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val serviceName = "nostr-wifi-aware"
        private const val permissionRequestCode = 1001
        private const val logTag = "NostrWifiAware"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val pingedPeers = mutableSetOf<Int>()
    private val appInstance = "${Build.MODEL}-${System.currentTimeMillis().toString(16).takeLast(6)}"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var nextMessageId = 1
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiAwareManager = getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        setContentView(buildUi())

        setStatus("Idle")
        appendLog("Open the app on two Wi-Fi Aware-capable phones, then tap Start.")
    }

    override fun onDestroy() {
        stopAware("Activity destroyed")
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
            startAware()
        } else {
            setStatus("Permission denied")
            appendLog("Missing runtime permission. Wi-Fi Aware cannot start.")
        }
    }

    private fun buildUi(): LinearLayout {
        val padding = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            textSize = 18f
        }

        val hintView = TextView(this).apply {
            text = "Both phones must keep this app open. Wi-Fi and location services must be enabled."
        }

        toggleButton = Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (running) {
                    stopAware("Stopped manually")
                } else {
                    ensurePermissionsAndStart()
                }
            }
        }

        val clearButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener {
                logLines.clear()
                renderLog()
            }
        }

        logView = TextView(this).apply {
            setTextIsSelectable(true)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(toggleButton)
            addView(clearButton)
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
            addView(hintView)
            addView(buttonRow)
            addView(scrollView)
        }
    }

    private fun ensurePermissionsAndStart() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            setStatus("Unsupported")
            appendLog("This device does not report Wi-Fi Aware support.")
            return
        }

        val missingPermissions = requiredRuntimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            appendLog("Requesting permission: ${missingPermissions.joinToString()}")
            requestPermissions(missingPermissions.toTypedArray(), permissionRequestCode)
            return
        }

        startAware()
    }

    private fun requiredRuntimePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startAware() {
        if (running) {
            return
        }

        if (!wifiAwareManager.isAvailable) {
            setStatus("Unavailable")
            appendLog("Wi-Fi Aware is currently unavailable. Check Wi-Fi, location services, or hotspot/tethering state.")
            return
        }

        setStatus("Attaching")
        appendLog("Attaching to Wi-Fi Aware as $appInstance")

        wifiAwareManager.attach(
            object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    awareSession = session
                    running = true
                    pingedPeers.clear()
                    nextMessageId = 1
                    toggleButton.text = "Stop"
                    setStatus("Running")
                    appendLog("Attach succeeded.")
                    startPublish(session)
                    startSubscribe(session)
                }

                override fun onAttachFailed() {
                    setStatus("Attach failed")
                    appendLog("Attach failed.")
                }
            },
            mainHandler,
        )
    }

    private fun startPublish(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(serviceName)
            .build()

        session.publish(
            config,
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    appendLog("Publish started.")
                }

                override fun onSessionConfigFailed() {
                    appendLog("Publish config failed.")
                }

                override fun onSessionTerminated() {
                    publishSession = null
                    appendLog("Publish session terminated.")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    handleIncomingMessage("publish", publishSession, peerHandle, message)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    appendLog("Publish sent message #$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    appendLog("Publish failed to send message #$messageId")
                }
            },
            mainHandler,
        )
    }

    private fun startSubscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .build()

        session.subscribe(
            config,
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    appendLog("Subscribe started.")
                }

                override fun onSessionConfigFailed() {
                    appendLog("Subscribe config failed.")
                }

                override fun onSessionTerminated() {
                    subscribeSession = null
                    appendLog("Subscribe session terminated.")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>,
                ) {
                    appendLog("Discovered peer ${peerLabel(peerHandle)}")
                    val peerKey = peerHandle.hashCode()
                    if (pingedPeers.add(peerKey)) {
                        sendMessage(
                            session = subscribeSession,
                            peerHandle = peerHandle,
                            payload = "ping:$appInstance",
                            reason = "initial ping",
                        )
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    handleIncomingMessage("subscribe", subscribeSession, peerHandle, message)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    appendLog("Subscribe sent message #$messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    appendLog("Subscribe failed to send message #$messageId")
                }
            },
            mainHandler,
        )
    }

    private fun handleIncomingMessage(
        source: String,
        session: DiscoverySession?,
        peerHandle: PeerHandle,
        message: ByteArray,
    ) {
        val text = message.toString(Charsets.UTF_8)
        appendLog("$source received '$text' from ${peerLabel(peerHandle)}")

        if (text.startsWith("ping:")) {
            sendMessage(
                session = session,
                peerHandle = peerHandle,
                payload = "pong:$appInstance",
                reason = "pong reply",
            )
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

    private fun stopAware(reason: String) {
        if (awareSession == null && publishSession == null && subscribeSession == null && !running) {
            return
        }

        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession = null
        pingedPeers.clear()
        running = false
        toggleButton.text = "Start"
        setStatus("Stopped")
        appendLog(reason)
    }

    private fun setStatus(status: String) {
        statusView.text = "Status: $status"
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        Log.d(logTag, message)
        logLines.addLast("[$timestamp] $message")
        while (logLines.size > 200) {
            logLines.removeFirst()
        }
        renderLog()
    }

    private fun renderLog() {
        logView.text = logLines.joinToString("\n")
    }

    private fun peerLabel(peerHandle: PeerHandle): String {
        return "peer-${peerHandle.hashCode().toUInt().toString(16)}"
    }
}
