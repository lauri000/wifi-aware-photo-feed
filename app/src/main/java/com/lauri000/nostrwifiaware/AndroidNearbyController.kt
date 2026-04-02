package com.lauri000.nostrwifiaware

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import uniffi.nearby_hashtree_ffi.AndroidCommand
import uniffi.nearby_hashtree_ffi.AndroidEvent
import uniffi.nearby_hashtree_ffi.AppCore
import uniffi.nearby_hashtree_ffi.DiscoveryChannel
import uniffi.nearby_hashtree_ffi.SocketSide
import uniffi.nearby_hashtree_ffi.UiAction
import uniffi.nearby_hashtree_ffi.ViewState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AndroidNearbyController(
    private val app: Application,
) {
    interface Host {
        fun renderViewState(viewState: ViewState)

        fun requestCameraPermission()

        fun requestNearbyPermissions(permissions: Array<String>)

        fun startCameraPreview(outputPath: String)

        fun stopCameraPreview()

        fun capturePhoto(outputPath: String)
    }

    companion object {
        private const val logTag = "NostrWifiAware"
        private const val ioBufferSize = 64 * 1024
    }

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
    private val wifiAwareManager = app.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager
    private val connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val appCore = AppCore(app.filesDir.absolutePath, app.cacheDir.absolutePath, appInstance)

    private val publishHandles = ConcurrentHashMap<Long, PeerHandle>()
    private val subscribeHandles = ConcurrentHashMap<Long, PeerHandle>()
    private val sockets = ConcurrentHashMap<Long, SocketResource>()
    private val socketWriters = ConcurrentHashMap<Long, ExecutorService>()
    private val responderServerSockets = ConcurrentHashMap<Long, ServerSocket>()
    private val responderNetworkCallbacks = ConcurrentHashMap<Long, ConnectivityManager.NetworkCallback>()
    private val initiatorNetworkCallbacks = ConcurrentHashMap<Long, ConnectivityManager.NetworkCallback>()
    private val initiatorNetworks = ConcurrentHashMap<Long, Network>()
    private val reconnectRunnables = ConcurrentHashMap<String, Runnable>()

    @Volatile
    private var host: Host? = null

    @Volatile
    private var latestViewState: ViewState? = null

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var awareStateReceiverRegistered = false

    private val awareStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                dispatchAndroidEvent(AndroidEvent.AwareAvailabilityChanged(isAwareAvailable()))
            }
        }

    init {
        latestViewState = appCore.currentViewState()
    }

    fun attachHost(host: Host) {
        this.host = host
        latestViewState?.let { viewState ->
            mainHandler.post { if (this.host === host) host.renderViewState(viewState) }
        } ?: publishViewState()
    }

    fun detachHost(host: Host) {
        if (this.host === host) {
            this.host = null
        }
    }

    fun dispatchUiAction(action: UiAction) {
        coreExecutor.execute {
            try {
                appCore.onUiAction(action)
                drainCommands()
                publishViewState()
            } catch (t: Throwable) {
                Log.e(logTag, "UI action failed", t)
            }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        dispatchAndroidEvent(
            if (granted) {
                AndroidEvent.CameraPermissionGranted
            } else {
                AndroidEvent.CameraPermissionDenied
            },
        )
    }

    fun onNearbyPermissionResult(granted: Boolean) {
        dispatchAndroidEvent(
            if (granted) {
                AndroidEvent.NearbyPermissionGranted
            } else {
                AndroidEvent.NearbyPermissionDenied
            },
        )
    }

    fun onCameraCaptureSaved(outputPath: String) {
        dispatchAndroidEvent(AndroidEvent.CameraCaptureSaved(outputPath))
    }

    fun onCameraCaptureFailed(message: String) {
        dispatchAndroidEvent(AndroidEvent.CameraCaptureFailed(message))
    }

    private fun dispatchAndroidEvent(event: AndroidEvent) {
        coreExecutor.execute {
            try {
                appCore.onAndroidEvent(event)
                drainCommands()
                publishViewState()
            } catch (t: Throwable) {
                Log.e(logTag, "Android event failed", t)
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
        latestViewState = viewState
        host?.let { activeHost ->
            mainHandler.post {
                if (host === activeHost) {
                    activeHost.renderViewState(viewState)
                }
            }
        }
    }

    private fun executeCommand(command: AndroidCommand) {
        when (command) {
            is AndroidCommand.RequestCameraPermission -> {
                mainHandler.post { host?.requestCameraPermission() }
            }
            is AndroidCommand.RequestNearbyPermission -> {
                mainHandler.post { host?.requestNearbyPermissions(requiredNearbyPermissions()) }
            }
            is AndroidCommand.StartCameraPreview -> {
                mainHandler.post { host?.startCameraPreview(command.outputPath) }
            }
            is AndroidCommand.StopCameraPreview -> {
                mainHandler.post { host?.stopCameraPreview() }
            }
            is AndroidCommand.CapturePhoto -> {
                mainHandler.post { host?.capturePhoto(command.outputPath) }
            }
            is AndroidCommand.StartAwareAttach -> executeStartAwareAttach()
            is AndroidCommand.StartPublish -> executeStartPublish(command.serviceName, command.serviceInfo)
            is AndroidCommand.StartSubscribe -> executeStartSubscribe(command.serviceName)
            is AndroidCommand.SendDiscoveryMessage -> executeSendDiscoveryMessage(command)
            is AndroidCommand.OpenResponder -> executeOpenResponder(command)
            is AndroidCommand.OpenInitiator -> executeOpenInitiator(command)
            is AndroidCommand.ConnectInitiatorSocket -> executeConnectInitiatorSocket(command)
            is AndroidCommand.WriteSocketBytes -> executeWriteSocketBytes(command)
            is AndroidCommand.CloseSocket -> closeSocket(command.connectionId)
            is AndroidCommand.ScheduleReconnect -> scheduleReconnect(command.peerInstance, command.delayMs)
            is AndroidCommand.StopAware -> cleanupAndroidResources()
        }
    }

    private fun requiredNearbyPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun isAwareAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            wifiAwareManager.isAvailable

    private fun registerAwareStateReceiverIfNeeded() {
        if (awareStateReceiverRegistered) {
            return
        }
        app.registerReceiver(
            awareStateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
        )
        awareStateReceiverRegistered = true
    }

    private fun unregisterAwareStateReceiverIfNeeded() {
        if (!awareStateReceiverRegistered) {
            return
        }
        try {
            app.unregisterReceiver(awareStateReceiver)
        } catch (_: Exception) {
        }
        awareStateReceiverRegistered = false
    }

    private fun executeStartAwareAttach() {
        mainHandler.post {
            registerAwareStateReceiverIfNeeded()
            val available = isAwareAvailable()
            dispatchAndroidEvent(AndroidEvent.AwareAvailabilityChanged(available))
            if (!available) {
                dispatchAndroidEvent(AndroidEvent.AwareAttachFailed)
                return@post
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
        mainHandler.post {
            val session = awareSession ?: return@post
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

                    override fun onMessageReceived(
                        peerHandle: PeerHandle,
                        message: ByteArray,
                    ) {
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
        mainHandler.post {
            val session = awareSession ?: return@post
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

                    override fun onMessageReceived(
                        peerHandle: PeerHandle,
                        message: ByteArray,
                    ) {
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
        mainHandler.post {
            val peerHandle =
                when (command.channel) {
                    DiscoveryChannel.PUBLISH -> publishHandles[command.handleId]
                    DiscoveryChannel.SUBSCRIBE -> subscribeHandles[command.handleId]
                } ?: return@post
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
        mainHandler.post {
            val session = publishSession ?: return@post
            val peerHandle = publishHandles[command.handleId] ?: return@post
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
                                AndroidEvent.SocketError(
                                    command.connectionId,
                                    e.message ?: "Responder accept failed",
                                ),
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

                val callback =
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
                responderNetworkCallbacks[command.connectionId] = callback
                connectivityManager.requestNetwork(request, callback)
            } catch (e: Exception) {
                dispatchAndroidEvent(
                    AndroidEvent.SocketError(command.connectionId, e.message ?: "Responder setup failed"),
                )
            }
        }
    }

    private fun executeOpenInitiator(command: AndroidCommand.OpenInitiator) {
        mainHandler.post {
            val session = subscribeSession ?: return@post
            val peerHandle = subscribeHandles[command.handleId] ?: return@post
            val specifier =
                WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                    .setPskPassphrase(command.passphrase)
                    .build()
            val request =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(specifier)
                    .build()

            val callback =
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
            initiatorNetworkCallbacks[command.connectionId] = callback
            connectivityManager.requestNetwork(request, callback)
        }
    }

    private fun executeConnectInitiatorSocket(command: AndroidCommand.ConnectInitiatorSocket) {
        ioExecutor.execute {
            try {
                val network =
                    initiatorNetworks[command.connectionId]
                        ?: throw IOException("Initiator network unavailable")
                val address = Inet6Address.getByName(command.ipv6) as Inet6Address
                val socket = network.socketFactory.createSocket(address, command.port)
                bindSocket(command.connectionId, SocketSide.INITIATOR, socket)
            } catch (e: Exception) {
                dispatchAndroidEvent(
                    AndroidEvent.SocketError(
                        command.connectionId,
                        e.message ?: "Initiator socket connect failed",
                    ),
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

    private fun scheduleReconnect(
        peerInstance: String,
        delayMs: Long,
    ) {
        reconnectRunnables.remove(peerInstance)?.let(mainHandler::removeCallbacks)
        val runnable =
            Runnable {
                reconnectRunnables.remove(peerInstance)
                dispatchAndroidEvent(AndroidEvent.ReconnectPeer(peerInstance))
            }
        reconnectRunnables[peerInstance] = runnable
        mainHandler.postDelayed(runnable, delayMs)
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
        unregisterAwareStateReceiverIfNeeded()
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession = null
        publishHandles.clear()
        subscribeHandles.clear()
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
        reconnectRunnables.values.forEach(mainHandler::removeCallbacks)
        reconnectRunnables.clear()
        sockets.keys.toList().forEach(::closeSocket)
    }

    private fun handleId(peerHandle: PeerHandle): Long = peerHandle.hashCode().toLong()
}
