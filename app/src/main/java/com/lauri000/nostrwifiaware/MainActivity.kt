package com.lauri000.nostrwifiaware

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
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

    private lateinit var headerPrimaryStatsView: TextView
    private lateinit var headerSecondaryStatsView: TextView
    private lateinit var headerErrorView: TextView
    private lateinit var feedTabButton: Button
    private lateinit var settingsTabButton: Button
    private lateinit var feedScrollView: ScrollView
    private lateinit var settingsScrollView: ScrollView
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
        val contentPadding = dp(20)
        val headerTopPadding = dp(32)
        val headerBottomPadding = dp(14)

        headerPrimaryStatsView = buildHeaderStatsText(14f, parseColor("#111827"), Typeface.BOLD)
        headerSecondaryStatsView = buildHeaderStatsText(12f, parseColor("#4b5563"), Typeface.NORMAL)
        headerErrorView =
            buildHeaderStatsText(12f, parseColor("#b91c1c"), Typeface.NORMAL).apply {
                visibility = View.GONE
            }
        logView =
            TextView(this).apply {
                setTextColor(parseColor("#334155"))
                textSize = 12f
                setTextIsSelectable(true)
                setTypeface(Typeface.MONOSPACE)
            }
        feedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        feedEmptyView = buildEmptyText()

        feedTabButton = buildHeaderTabButton("Feed").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.SwitchPage(UiPage.FEED)) }
        }
        settingsTabButton = buildHeaderTabButton("Settings").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.SwitchPage(UiPage.SETTINGS)) }
        }
        takePhotoButton = buildPrimaryButton("Take Photo").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.TakePhotoRequested) }
        }
        nearbyToggleButton = buildPrimaryButton("Connect").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.ToggleNearbyRequested) }
        }
        clearDataButton = buildSecondaryButton("Clear Data").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.ClearDemoDataRequested) }
        }
        clearLogButton = buildSecondaryButton("Clear Log").apply {
            setOnClickListener { controller.dispatchUiAction(UiAction.ClearLogRequested) }
        }

        val headerTabs =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundedFill(parseColor("#eef2f7"), parseColor("#d7dde6"), dp(16))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(
                    feedTabButton,
                    LinearLayout.LayoutParams(0, dp(52), 1f),
                )
                addView(
                    settingsTabButton,
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        marginStart = dp(6)
                    },
                )
            }

        val headerContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(parseColor("#fbfaf7"))
                setPadding(contentPadding, headerTopPadding, contentPadding, headerBottomPadding)
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
                                        text = "LocalGram"
                                        textSize = 28f
                                        setTextColor(parseColor("#111827"))
                                        setTypeface(Typeface.SERIF, Typeface.BOLD)
                                    },
                                )
                            },
                        )
                        addView(headerTabs)
                    },
                )
                addView(spacer(dp(10)))
                addView(headerPrimaryStatsView)
                addView(spacer(dp(4)))
                addView(headerSecondaryStatsView)
                addView(spacer(dp(4)))
                addView(headerErrorView)
                addView(
                    View(this@MainActivity).apply {
                        setBackgroundColor(parseColor("#e5e7eb"))
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(1),
                            ).apply {
                                topMargin = dp(14)
                            }
                    },
                )
            }

        val feedIntroCard =
            surfaceCard().apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Latest photos"
                                        textSize = 22f
                                        setTypeface(Typeface.SERIF, Typeface.BOLD)
                                        setTextColor(parseColor("#111827"))
                                    },
                                )
                                addView(spacer(dp(6)))
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "From me and nearby, newest first."
                                        textSize = 14f
                                        setTextColor(parseColor("#6b7280"))
                                    },
                                )
                            },
                        )
                        addView(spacer(dp(14)))
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                                addView(
                                    nearbyToggleButton,
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ),
                                )
                                addView(horizontalSpacer(dp(10)))
                                addView(
                                    takePhotoButton,
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ),
                                )
                            },
                        )
                    },
                )
            }

        val feedIntroSection =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(contentPadding, 0, contentPadding, 0)
                addView(feedIntroCard)
            }

        feedPage =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(feedIntroSection)
                addView(spacer(dp(12)))
                addView(feedEmptyView)
                addView(feedContainer)
            }

        configPage =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(
                    surfaceCard().apply {
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Maintenance"
                                textSize = 18f
                                setTypeface(Typeface.SERIF, Typeface.BOLD)
                                setTextColor(parseColor("#111827"))
                            },
                        )
                        addView(spacer(dp(10)))
                        addView(clearDataButton)
                        addView(spacer(dp(12)))
                        addView(clearLogButton)
                    },
                )
                addView(spacer(dp(12)))
                addView(
                    surfaceCard().apply {
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "Transport Log"
                                textSize = 18f
                                setTypeface(Typeface.SERIF, Typeface.BOLD)
                                setTextColor(parseColor("#111827"))
                            },
                        )
                        addView(spacer(dp(10)))
                        addView(logView)
                    },
                )
            }

        val feedScrollContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, contentPadding, 0, contentPadding)
                addView(feedPage)
            }

        val settingsScrollContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
                addView(configPage)
            }

        feedScrollView =
            ScrollView(this).apply {
                setBackgroundColor(parseColor("#f5f3ef"))
                isFillViewport = true
                addView(feedScrollContent)
            }

        settingsScrollView =
            ScrollView(this).apply {
                setBackgroundColor(parseColor("#f5f3ef"))
                isFillViewport = true
                visibility = View.GONE
                addView(settingsScrollContent)
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
        shutterButton = buildPrimaryButton("Shutter").apply {
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
                                text = "Capture into LocalGram, store it durably, ingest it into hashtree, then let nearby peers sync missing blocks."
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
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(parseColor("#f5f3ef"))
                    addView(
                        headerContainer,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        FrameLayout(this@MainActivity).apply {
                            addView(
                                feedScrollView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            addView(
                                settingsScrollView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        ),
                    )
                },
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
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
                val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                headerContainer.setPadding(
                    contentPadding,
                    headerTopPadding + systemBars.top,
                    contentPadding,
                    headerBottomPadding,
                )
                windowInsets
            }
            ViewCompat.requestApplyInsets(this)
        }
    }

    private fun applyViewState(viewState: ViewState) {
        headerPrimaryStatsView.text =
            "${viewState.localSummaryText}  ·  ${viewState.nearbySummaryText}  ·  ${viewState.linkText}"
        headerSecondaryStatsView.text =
            "${viewState.statusText}  ·  ${viewState.captureQueueText}  ·  ${viewState.syncStatusText}  ·  ${viewState.storageText}"
        val hasError = viewState.lastSyncErrorText != "No sync errors"
        headerErrorView.text = if (hasError) viewState.lastSyncErrorText else ""
        headerErrorView.visibility = if (hasError) View.VISIBLE else View.GONE

        nearbyToggleButton.text =
            when {
                viewState.statusText.contains("Starting nearby") -> "Connecting..."
                viewState.modeText == "Nearby" -> "Disconnect"
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
        feedScrollView.visibility = if (page == UiPage.FEED) View.VISIBLE else View.GONE
        settingsScrollView.visibility = if (page == UiPage.SETTINGS) View.VISIBLE else View.GONE
        configPage.visibility = if (page == UiPage.SETTINGS) View.VISIBLE else View.GONE
        feedPage.visibility = if (page == UiPage.FEED) View.VISIBLE else View.GONE
        setHeaderTabState(feedTabButton, page == UiPage.FEED)
        setHeaderTabState(settingsTabButton, page == UiPage.SETTINGS)
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
                parseColor("#ea580c")
            } else {
                parseColor("#0f766e")
            }
        val image =
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedFill(parseColor("#f3f4f6"), parseColor("#e5e7eb"), 0)
                setImageBitmap(loadPreviewBitmap(File(item.renderCachePath), 1080, 1600))
                clipToOutline = true
            }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(parseColor("#ffffff"), parseColor("#e5e7eb"), 0)
            addView(image)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(18))
                    addView(buildTag(item.sourceLabel.uppercase(Locale.US), "#fff7ed", colorToHex(sourceAccent), sourceAccent))
                    addView(spacer(dp(12)))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = if (item.isLocal) "Me" else "Received"
                            textSize = 20f
                            setTextColor(parseColor("#111827"))
                            setTypeface(Typeface.SERIF, Typeface.BOLD)
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "${formatTimestamp(item.createdAtMs)}  ·  ${formatByteCount(item.sizeBytes.toLong())}"
                            textSize = 13f
                            setTextColor(parseColor("#6b7280"))
                            setPadding(0, dp(6), 0, 0)
                        },
                    )
                    addView(spacer(dp(10)))
                    addView(
                        tagRow(
                            buildTag(item.sourceLabel.uppercase(Locale.US), "#f8fafc", "#cbd5e1", parseColor("#475569")),
                            buildTag("cid ${item.photoCid.takeLast(12)}", "#f8fafc", "#cbd5e1", parseColor("#475569")),
                        ),
                    )
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

    private fun loadPreviewBitmap(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:$targetWidth:$targetHeight"
        previewBitmapCache.get(cacheKey)?.let { return it }
        val bitmap = decodePreviewBitmap(file, targetWidth, targetHeight)?.let {
            applyExifOrientation(file, it)
        }
        if (bitmap != null) {
            previewBitmapCache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    private fun applyExifOrientation(
        file: File,
        bitmap: Bitmap,
    ): Bitmap {
        val orientation =
            try {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }

        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { rotated ->
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
            bitmap
        }
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

    private fun buildHeaderStatsText(
        textSizeSp: Float,
        color: Int,
        style: Int,
    ): TextView =
        TextView(this).apply {
            setTextColor(color)
            textSize = textSizeSp
            setTypeface(Typeface.SANS_SERIF, style)
        }

    private fun buildBodyText(): TextView =
        TextView(this).apply {
            setTextColor(parseColor("#4b5563"))
            textSize = 14f
        }

    private fun buildEmptyText(): TextView =
        TextView(this).apply {
            setTextColor(parseColor("#6b7280"))
            textSize = 14f
            setPadding(dp(20), dp(8), dp(20), 0)
        }

    private fun surfaceCard(
        fillColor: Int = parseColor("#ffffff"),
        strokeColor: Int = parseColor("#e5e7eb"),
        radiusDp: Int = 24,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(fillColor, strokeColor, radiusDp)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

    private fun buildTag(
        text: String,
        fillHex: String,
        strokeHex: String,
        textColor: Int = Color.WHITE,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(textColor)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = roundedFill(parseColor(fillHex), parseColor(strokeHex), dp(14))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

    private fun buildPrimaryButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            background = roundedFill(parseColor("#111827"), parseColor("#111827"), 22)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

    private fun buildSecondaryButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(parseColor("#111827"))
            textSize = 14f
            background = roundedFill(parseColor("#f3f4f6"), parseColor("#d1d5db"), dp(22))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

    private fun buildGhostButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(parseColor("#111827"))
            textSize = 14f
            background = roundedFill(parseColor("#ffffff"), parseColor("#d1d5db"), dp(22))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

    private fun buildHeaderTabButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            isSingleLine = true
            includeFontPadding = false
            textSize = 14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
        }

    private fun setHeaderTabState(
        button: Button,
        selected: Boolean,
    ) {
        button.setTextColor(if (selected) Color.WHITE else parseColor("#475569"))
        button.background =
            roundedFill(
                if (selected) parseColor("#111827") else parseColor("#eef2f7"),
                if (selected) parseColor("#111827") else parseColor("#eef2f7"),
                12,
            )
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
