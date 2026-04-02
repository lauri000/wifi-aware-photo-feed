package com.lauri000.nostrwifiaware

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import uniffi.nearby_hashtree_ffi.ControlsEnabled
import uniffi.nearby_hashtree_ffi.FeedItem
import uniffi.nearby_hashtree_ffi.UiAction
import uniffi.nearby_hashtree_ffi.UiPage
import uniffi.nearby_hashtree_ffi.ViewState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException

class MainActivity : ComponentActivity(), AndroidNearbyController.Host {
    companion object {
        private const val cameraPermissionRequestCode = 1001
        private const val nearbyPermissionRequestCode = 1002
        private const val logTag = "NostrWifiAware"
        private const val renderDebounceMs = 48L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderStateLock = Any()
    private val previewBitmapCache = object : LruCache<String, Bitmap>(24) {}

    private lateinit var controller: AndroidNearbyController

    private lateinit var statusView: TextView
    private lateinit var modeView: TextView
    private lateinit var transportView: TextView
    private lateinit var storageView: TextView
    private lateinit var captureQueueView: TextView
    private lateinit var syncStatusView: TextView
    private lateinit var lastErrorView: TextView
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

    private lateinit var previewOverlay: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var previewHeadline: TextView
    private lateinit var shutterButton: Button
    private lateinit var cancelCaptureButton: Button

    private var latestViewState: ViewState? = null
    private var pendingViewState: ViewState? = null
    private var renderScheduled = false
    private var renderedLogCount = 0
    private var renderedFeedSignature = ""

    private var previewOutputPath: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewVisible = false

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
                    applyViewState(viewState)
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
        controller = (application as NearbyApp).nearbyController
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        controller.attachHost(this)
    }

    override fun onStop() {
        controller.detachHost(this)
        stopCameraPreview()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            cameraPermissionRequestCode -> controller.onCameraPermissionResult(granted)
            nearbyPermissionRequestCode -> controller.onNearbyPermissionResult(granted)
        }
    }

    override fun renderViewState(viewState: ViewState) {
        latestViewState = viewState
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

    override fun requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            controller.onCameraPermissionResult(true)
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermissionRequestCode)
    }

    override fun requestNearbyPermissions(permissions: Array<String>) {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            controller.onNearbyPermissionResult(true)
            return
        }
        requestPermissions(missing.toTypedArray(), nearbyPermissionRequestCode)
    }

    override fun startCameraPreview(outputPath: String) {
        previewOutputPath = outputPath
        previewVisible = true
        previewOverlay.visibility = View.VISIBLE
        previewHeadline.text = "Camera ready"

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview =
                        Preview.Builder()
                            .build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }
                    imageCapture =
                        ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                } catch (e: ExecutionException) {
                    previewVisible = false
                    previewOverlay.visibility = View.GONE
                    controller.onCameraCaptureFailed(e.cause?.message ?: "Failed to bind camera preview")
                } catch (e: Exception) {
                    previewVisible = false
                    previewOverlay.visibility = View.GONE
                    controller.onCameraCaptureFailed(e.message ?: "Failed to bind camera preview")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun stopCameraPreview() {
        previewVisible = false
        previewOutputPath = null
        previewOverlay.visibility = View.GONE
        previewHeadline.text = "Camera ready"
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    override fun capturePhoto(outputPath: String) {
        val capture = imageCapture
        if (capture == null) {
            controller.onCameraCaptureFailed("Camera is not ready yet.")
            return
        }
        previewHeadline.text = "Saving photo"
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    controller.onCameraCaptureSaved(outputPath)
                }

                override fun onError(exception: ImageCaptureException) {
                    previewHeadline.text = "Camera ready"
                    controller.onCameraCaptureFailed(exception.message ?: "Image capture failed")
                }
            },
        )
    }

    private fun buildUi(): View {
        val contentPadding = dp(18)
        val sectionSpacing = dp(18)

        statusView = buildStatPill()
        modeView = buildStatPill()
        transportView = buildStatPill()
        storageView = buildStatPill()
        captureQueueView = buildStatPill()
        syncStatusView = buildStatPill()
        lastErrorView = buildBodyText().apply { setTextColor(parseColor("#fda4af")) }
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
            setOnClickListener { controller.dispatchUiAction(UiAction.SwitchPage(UiPage.SETTINGS)) }
        }
        settingsBackButton = buildGhostButton("Back to Feed").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.SwitchPage(UiPage.FEED)) }
        }
        takePhotoButton = buildAccentButton("Take Photo", "#f97316", "#fb7185").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.TakePhotoRequested) }
        }
        nearbyToggleButton = buildAccentButton("Connect", "#0ea5e9", "#22c55e").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.ToggleNearbyRequested) }
        }
        clearDataButton = buildMutedButton("Clear Data").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.ClearDemoDataRequested) }
        }
        clearLogButton = buildGhostButton("Clear Log").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.ClearLogRequested) }
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
                                addView(spacer(dp(10)))
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Take a photo, keep it in your hashtree feed, and let nearby phones merge it automatically."
                                        textSize = 14f
                                        setTextColor(parseColor("#d8dfec"))
                                    },
                                )
                            },
                        )
                        addView(pageConfigButton)
                    },
                )
            }

        val statusGrid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    statRow(
                        statusView,
                        modeView,
                    ),
                )
                addView(spacer(dp(10)))
                addView(
                    statRow(
                        transportView,
                        storageView,
                    ),
                )
                addView(spacer(dp(10)))
                addView(
                    statRow(
                        captureQueueView,
                        syncStatusView,
                    ),
                )
            }

        val feedHeaderCard =
            cardContainer().apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams =
                                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Your feed"
                                        textSize = 22f
                                        setTypeface(Typeface.SERIF, Typeface.BOLD)
                                        setTextColor(Color.WHITE)
                                    },
                                )
                                addView(spacer(dp(6)))
                                addView(localSummaryFeedView)
                                addView(spacer(dp(4)))
                                addView(nearbySummaryFeedView)
                            },
                        )
                        addView(takePhotoButton)
                    },
                )
            }

        feedPage =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(headerCard)
                addView(spacer(sectionSpacing))
                addView(statusGrid)
                addView(spacer(sectionSpacing))
                addView(feedHeaderCard)
                addView(spacer(dp(12)))
                addView(feedEmptyView)
                addView(feedContainer)
            }

        configPage =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(
                    cardContainer().apply {
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(
                                    LinearLayout(this@MainActivity).apply {
                                        orientation = LinearLayout.VERTICAL
                                        layoutParams =
                                            LinearLayout.LayoutParams(
                                                0,
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                                1f,
                                            )
                                        addView(
                                            TextView(this@MainActivity).apply {
                                                text = "Settings"
                                                textSize = 24f
                                                setTypeface(Typeface.SERIF, Typeface.BOLD)
                                                setTextColor(Color.WHITE)
                                            },
                                        )
                                        addView(spacer(dp(8)))
                                        addView(localSummarySettingsView)
                                        addView(spacer(dp(4)))
                                        addView(nearbySummarySettingsView)
                                        addView(spacer(dp(8)))
                                        addView(lastErrorView)
                                    },
                                )
                                addView(settingsBackButton)
                            },
                        )
                    },
                )
                addView(spacer(dp(12)))
                addView(
                    cardContainer().apply {
                        addView(nearbyToggleButton)
                        addView(spacer(dp(12)))
                        addView(clearDataButton)
                        addView(spacer(dp(12)))
                        addView(clearLogButton)
                    },
                )
                addView(spacer(dp(12)))
                addView(
                    cardContainer().apply {
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Transport log"
                                textSize = 18f
                                setTypeface(Typeface.SERIF, Typeface.BOLD)
                                setTextColor(Color.WHITE)
                            },
                        )
                        addView(spacer(dp(10)))
                        addView(logView)
                    },
                )
            }

        val scrollContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
                addView(feedPage)
                addView(configPage)
            }

        val scrollView =
            ScrollView(this).apply {
                setBackgroundColor(parseColor("#080d16"))
                addView(scrollContent)
            }

        previewView =
            PreviewView(this).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        previewHeadline =
            TextView(this).apply {
                text = "Camera ready"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.SERIF, Typeface.BOLD)
            }
        shutterButton = buildAccentButton("Shutter", "#f97316", "#fb7185").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.CapturePhotoRequested) }
        }
        cancelCaptureButton = buildGhostButton("Cancel").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.CancelCameraRequested) }
        }

        previewOverlay =
            FrameLayout(this).apply {
                visibility = View.GONE
                setBackgroundColor(parseColor("#05070d"))
                addView(
                    previewView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(22), dp(18), dp(22))
                        addView(previewHeadline)
                        addView(spacer(dp(8)))
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "The photo is saved durably first, then ingested into hashtree, then synced by root and block hash."
                                textSize = 13f
                                setTextColor(parseColor("#d8dfec"))
                            },
                        )
                    },
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(cancelCaptureButton)
                        addView(horizontalSpacer(dp(14)))
                        addView(shutterButton)
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM,
                            ).apply {
                                setMargins(dp(18), dp(18), dp(18), dp(24))
                            }
                    },
                )
            }

        return FrameLayout(this).apply {
            addView(
                scrollView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                previewOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun applyViewState(viewState: ViewState) {
        statusView.text = viewState.statusText
        modeView.text = viewState.modeText
        transportView.text = viewState.linkText
        storageView.text = viewState.storageText
        captureQueueView.text = viewState.captureQueueText
        syncStatusView.text = viewState.syncStatusText
        lastErrorView.text = viewState.lastSyncErrorText
        localSummaryFeedView.text = viewState.localSummaryText
        nearbySummaryFeedView.text = viewState.nearbySummaryText
        localSummarySettingsView.text = viewState.localSummaryText
        nearbySummarySettingsView.text = viewState.nearbySummaryText

        nearbyToggleButton.text =
            when {
                viewState.statusText.contains("Starting nearby") -> "Connecting..."
                viewState.modeText.contains("Nearby") -> "Disconnect"
                else -> "Connect"
            }

        applyControls(viewState.controlsEnabled)
        applyPage(viewState.page)
        applyFeed(viewState.feedItems)
        applyLogs(viewState.logLines)
    }

    private fun applyControls(controls: ControlsEnabled) {
        setButtonState(takePhotoButton, controls.takePhoto && !previewVisible)
        setButtonState(nearbyToggleButton, controls.toggleNearby)
        setButtonState(clearDataButton, controls.clearDemoData)
        setButtonState(clearLogButton, controls.clearLog)
        setButtonState(shutterButton, previewVisible)
        setButtonState(cancelCaptureButton, previewVisible)
    }

    private fun applyPage(page: UiPage) {
        configPage.visibility = if (page == UiPage.SETTINGS) View.VISIBLE else View.GONE
        feedPage.visibility = if (page == UiPage.FEED) View.VISIBLE else View.GONE
        setButtonState(pageConfigButton, page != UiPage.SETTINGS && !previewVisible)
    }

    private fun applyFeed(feedItems: List<FeedItem>) {
        if (feedItems.isEmpty()) {
            renderedFeedSignature = ""
            feedContainer.removeAllViews()
            feedEmptyView.visibility = View.VISIBLE
            feedEmptyView.text = "No photos yet. Take one and it will be stored in your local feed immediately."
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
        val newLines =
            if (renderedLogCount <= logLines.size) {
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
            addView(buildTag(item.sourceLabel.uppercase(Locale.US), "#111827", colorToHex(sourceAccent)))
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
        strokeColor: Int = parseColor("#243146"),
        radiusDp: Int = 26,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedGradient(colors, strokeColor, radiusDp)
            setPadding(dp(18), dp(18), dp(18), dp(18))
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
            background = roundedFill(parseColor(fillHex), parseColor(strokeHex), dp(14))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

    private fun buildAccentButton(
        text: String,
        startHex: String,
        endHex: String,
    ): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedGradient(intArrayOf(parseColor(startHex), parseColor(endHex)), parseColor(endHex), 22)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

    private fun buildMutedButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = roundedFill(parseColor("#1f2937"), parseColor("#374151"), dp(22))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

    private fun buildGhostButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(parseColor("#dbe7ff"))
            textSize = 14f
            background = roundedFill(parseColor("#0f172a"), parseColor("#334155"), dp(22))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

    private fun setButtonState(
        button: Button,
        enabled: Boolean,
    ) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun spacer(height: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }

    private fun horizontalSpacer(width: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }

    private fun statRow(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { index, view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) {
                            marginStart = dp(10)
                        }
                    },
                )
            }
        }

    private fun tagRow(vararg tags: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tags.forEachIndexed { index, tag ->
                addView(tag)
                if (index != tags.lastIndex) {
                    addView(
                        View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                        },
                    )
                }
            }
        }

    private fun roundedGradient(
        colors: IntArray,
        strokeColor: Int,
        radiusDp: Int,
    ) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun roundedFill(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int,
    ) = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun parseColor(hex: String): Int = Color.parseColor(hex)

    private fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatTimestamp(createdAtMs: Long): String =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US).format(Date(createdAtMs))

    private fun formatByteCount(bytes: Long): String =
        if (bytes >= 1_000_000L) {
            String.format(Locale.US, "%.2f MB", bytes.toDouble() / 1_000_000.0)
        } else if (bytes >= 1_000L) {
            String.format(Locale.US, "%.2f KB", bytes.toDouble() / 1_000.0)
        } else {
            "$bytes B"
        }
}
