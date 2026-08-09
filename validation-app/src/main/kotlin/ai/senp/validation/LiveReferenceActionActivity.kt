package ai.senp.validation

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.motion.ActionMirrorMode
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.LiveFeedbackUncertainty
import ai.senp.pose.mediapipe.LiveMediaPipePoseEstimator
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Live generic reference-action comparison. No frame is uploaded or recorded. */
class LiveReferenceActionActivity : ComponentActivity() {
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "senp-live-reference-action").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val frameBusy = AtomicBoolean(false)

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ReferencePoseOverlayView
    private lateinit var phaseView: TextView
    private lateinit var primaryCueView: TextView
    private lateinit var secondaryCueView: TextView
    private lateinit var detailView: TextView
    private lateinit var repsView: TextView
    private lateinit var resetButton: Button

    @Volatile private var estimator: LiveMediaPipePoseEstimator? = null
    @Volatile private var processor: LiveReferenceActionProcessor? = null
    @Volatile private var lastTimestampMs = -1L
    private var preparedReference: PreparedReferenceAction? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionRequired()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preparedReference = ReferenceActionProfileStore.get()
        setContentView(buildUi())

        val prepared = preparedReference
        if (prepared == null) {
            showFatal(
                "Reference profile unavailable",
                "Return to Senp.ai, choose a reference clip, and wait until REFERENCE READY appears.",
            )
            return
        }
        processor = LiveReferenceActionProcessor(
            profile = prepared.profile,
            analysisFramesPerSecond = prepared.analysisFramesPerSecond,
        )
        phaseView.text = "REFERENCE READY • ${prepared.profile.states.size} STATES"
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        estimator?.close()
        estimator = null
        analyzerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6, 12, 25)) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        overlayView = ReferencePoseOverlayView(this)
        root.addView(overlayView, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(Color.argb(178, 4, 10, 22))
        }
        val title = TextView(this).apply {
            text = "REFERENCE LIVE"
            setTextColor(Color.rgb(124, 207, 255))
            textSize = 14f
            letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        phaseView = TextView(this).apply {
            text = "LOADING REFERENCE PROFILE"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(5), 0, 0)
        }
        top.addView(title)
        top.addView(phaseView)
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP })

        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        repsView = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 54f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        val repsLabel = TextView(this).apply {
            text = "COMPLETED"
            setTextColor(Color.rgb(190, 201, 218))
            textSize = 10f
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
        }
        progress.addView(repsView)
        progress.addView(repsLabel)
        val progressLayoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(78)
        }
        root.addView(progress, progressLayoutParams)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(15), dp(20), dp(22))
            setBackgroundColor(Color.argb(228, 5, 11, 24))
        }
        val label = TextView(this).apply {
            text = "REFERENCE DIFFERENCE"
            setTextColor(Color.rgb(124, 207, 255))
            textSize = 10f
            letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        primaryCueView = TextView(this).apply {
            text = "Waiting for the reference action"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, 0)
        }
        secondaryCueView = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(198, 207, 220))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        detailView = TextView(this).apply {
            text = "Move naturally. Feedback appears only after the demonstrated action state is confidently recognized."
            setTextColor(Color.rgb(158, 171, 193))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(13))
        }
        resetButton = Button(this).apply {
            text = "Reset live session"
            textSize = 15f
            minHeight = dp(50)
            isAllCaps = false
            setOnClickListener {
                processor?.reset()
                overlayView.clearPose()
                repsView.text = "0"
                primaryCueView.text = "Waiting for the reference action"
                secondaryCueView.text = ""
                detailView.text = "Move naturally. Feedback appears only after the demonstrated action state is confidently recognized."
            }
        }
        bottom.addView(label)
        bottom.addView(primaryCueView)
        bottom.addView(secondaryCueView)
        bottom.addView(detailView)
        bottom.addView(resetButton, LinearLayout.LayoutParams(MATCH, dp(50)))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })

        root.setOnApplyWindowInsetsListener { _, insets ->
            val (topInset, bottomInset) = systemBarInsets(insets)
            top.setPadding(dp(20), dp(18) + topInset, dp(20), dp(14))
            progressLayoutParams.topMargin = dp(78) + topInset
            progress.layoutParams = progressLayoutParams
            bottom.setPadding(dp(20), dp(15), dp(20), dp(22) + bottomInset)
            insets
        }
        root.requestApplyInsets()
        return root
    }

    @Suppress("DEPRECATION")
    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        bars.top to bars.bottom
    } else {
        insets.systemWindowInsetTop to insets.systemWindowInsetBottom
    }

    private fun startCamera() {
        primaryCueView.text = "Starting on-device pose model…"
        analyzerExecutor.execute {
            try {
                estimator = LiveMediaPipePoseEstimator.create(
                    context = this,
                    expectedModelSha256 = MODEL_SHA256,
                )
            } catch (error: Throwable) {
                runOnUiThread { showFatal("Pose model unavailable", error.message ?: "Unable to initialize MediaPipe") }
                return@execute
            }
            runOnUiThread { bindCamera() }
        }
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor, ::analyzeFrame)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    primaryCueView.text = "Waiting for the reference action"
                } catch (error: Throwable) {
                    showFatal("Camera unavailable", error.message ?: "Unable to bind CameraX")
                }
            },
            { command -> runOnUiThread(command) },
        )
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (!frameBusy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val poseEstimator = estimator ?: return
            val liveProcessor = processor ?: return
            val raw = image.toBitmap()
            val upright = rotate(raw, image.imageInfo.rotationDegrees)
            if (upright !== raw) raw.recycle()
            try {
                val candidate = image.imageInfo.timestamp / 1_000_000L
                val timestampMs = if (candidate > lastTimestampMs) candidate else lastTimestampMs + 1L
                lastTimestampMs = timestampMs
                val pose = poseEstimator.estimate(upright, timestampMs)
                val output = liveProcessor.update(pose)
                val frameWidth = upright.width
                val frameHeight = upright.height
                runOnUiThread {
                    overlayView.setPose(pose, frameWidth, frameHeight)
                    render(output)
                }
            } finally {
                upright.recycle()
            }
        } catch (error: Throwable) {
            runOnUiThread {
                primaryCueView.text = "Hold steady — reacquiring pose"
                secondaryCueView.text = ""
                detailView.text = error.javaClass.simpleName
            }
        } finally {
            frameBusy.set(false)
            image.close()
        }
    }

    private fun render(output: LiveReferenceActionOutput) {
        val prepared = preparedReference ?: return
        val profile = prepared.profile
        val estimate = output.estimate
        repsView.text = estimate.completedRepetitions.toString()
        val stateLabel = estimate.stateIndex?.let { index -> "STATE ${index + 1} / ${profile.states.size}" }
        val mirrorLabel = when (estimate.mirrorMode) {
            ActionMirrorMode.MIRRORED -> " • MIRRORED"
            else -> ""
        }
        phaseView.text = when (estimate.status) {
            ActionTrackingStatus.NO_ACTION -> "WAITING FOR ACTION"
            ActionTrackingStatus.POSSIBLE_ENTRY -> "POSSIBLE ENTRY${stateLabel?.let { " • $it" } ?: ""}"
            ActionTrackingStatus.TRACKING -> "LIVE • ${stateLabel ?: "TRACKING"}$mirrorLabel"
            ActionTrackingStatus.LOST -> "ACTION LOST • REACQUIRING"
            ActionTrackingStatus.COMPLETED -> "ACTION COMPLETE${stateLabel?.let { " • $it" } ?: ""}"
        }

        if (output.poseConfidence < 0.35) {
            primaryCueView.text = "Move into view — finding pose"
            secondaryCueView.text = ""
        } else {
            when (estimate.status) {
                ActionTrackingStatus.NO_ACTION -> {
                    primaryCueView.text = "Waiting for the reference action"
                    secondaryCueView.text = "Move naturally; no comparison is forced before action entry."
                }
                ActionTrackingStatus.POSSIBLE_ENTRY -> {
                    primaryCueView.text = "Action entry detected — keep moving"
                    secondaryCueView.text = "Feedback will appear after the state sequence is confirmed."
                }
                ActionTrackingStatus.LOST -> {
                    primaryCueView.text = "Reference action lost — return to the movement"
                    secondaryCueView.text = ""
                }
                ActionTrackingStatus.COMPLETED -> {
                    primaryCueView.text = output.feedback.primary?.label ?: "Reference action complete"
                    secondaryCueView.text = output.feedback.secondary?.label.orEmpty()
                }
                ActionTrackingStatus.TRACKING -> {
                    primaryCueView.text = output.feedback.primary?.label ?: "Following the reference action"
                    secondaryCueView.text = output.feedback.secondary?.label.orEmpty()
                }
            }
        }
        val uncertainty = when (output.feedback.uncertainty) {
            LiveFeedbackUncertainty.LOW -> "low"
            LiveFeedbackUncertainty.ELEVATED -> "elevated"
            LiveFeedbackUncertainty.HIGH -> "high"
        }
        detailView.text = String.format(
            Locale.ROOT,
            "Action %.0f%% • pose %.0f%% • %s uncertainty • differences are relative to this reference only",
            estimate.confidence * 100.0,
            output.poseConfidence * 100.0,
            uncertainty,
        )
        primaryCueView.setTextColor(
            if (output.feedback.primary != null) Color.rgb(255, 206, 127) else Color.WHITE,
        )
    }

    private fun showPermissionRequired() {
        primaryCueView.text = "Camera permission is required for live reference comparison"
        secondaryCueView.text = ""
        detailView.text = "No video is recorded or uploaded."
        resetButton.isEnabled = false
    }

    private fun showFatal(title: String, detail: String) {
        primaryCueView.text = title
        secondaryCueView.text = ""
        detailView.text = detail.take(220)
        resetButton.isEnabled = false
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
    }
}

private class ReferencePoseOverlayView(context: android.content.Context) : View(context) {
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 124, 207, 255)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private var pose: PoseFrame? = null
    private var frameWidth = 0
    private var frameHeight = 0

    fun setPose(pose: PoseFrame, frameWidth: Int, frameHeight: Int) {
        this.pose = pose
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        invalidate()
    }

    fun clearPose() {
        pose = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = pose ?: return
        if (current.validity.status in setOf(FrameValidityStatus.BLIND, FrameValidityStatus.CONTINUITY_BREAK)) return
        if (frameWidth <= 0 || frameHeight <= 0) return

        val target = fittedRect(width.toFloat(), height.toFloat(), frameWidth.toFloat(), frameHeight.toFloat())
        val byId = current.landmarks.associateBy { it.id }
        CONNECTIONS.forEach { (from, to) ->
            val a = byId.getValue(from)
            val b = byId.getValue(to)
            if (!drawable(a) || !drawable(b)) return@forEach
            canvas.drawLine(
                target.left + (a.image.x * target.width()).toFloat(),
                target.top + (a.image.y * target.height()).toFloat(),
                target.left + (b.image.x * target.width()).toFloat(),
                target.top + (b.image.y * target.height()).toFloat(),
                skeletonPaint,
            )
        }
        current.landmarks.forEach { landmark ->
            if (!drawable(landmark)) return@forEach
            canvas.drawCircle(
                target.left + (landmark.image.x * target.width()).toFloat(),
                target.top + (landmark.image.y * target.height()).toFloat(),
                4f,
                pointPaint,
            )
        }
    }

    private fun drawable(landmark: ai.senp.core.contracts.PoseLandmark): Boolean {
        val confidence = minOf(landmark.visibility ?: 0.0, landmark.presence ?: 0.0)
        return confidence >= 0.25 && landmark.image.x in -0.05..1.05 && landmark.image.y in -0.05..1.05
    }

    private fun fittedRect(viewWidth: Float, viewHeight: Float, contentWidth: Float, contentHeight: Float): RectF {
        val scale = minOf(viewWidth / contentWidth, viewHeight / contentHeight)
        val fittedWidth = contentWidth * scale
        val fittedHeight = contentHeight * scale
        val left = (viewWidth - fittedWidth) / 2f
        val top = (viewHeight - fittedHeight) / 2f
        return RectF(left, top, left + fittedWidth, top + fittedHeight)
    }

    companion object {
        private val CONNECTIONS = listOf(
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_ELBOW,
            PoseLandmarkId.LEFT_ELBOW to PoseLandmarkId.LEFT_WRIST,
            PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_HIP,
            PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.LEFT_KNEE,
            PoseLandmarkId.LEFT_KNEE to PoseLandmarkId.LEFT_ANKLE,
            PoseLandmarkId.RIGHT_HIP to PoseLandmarkId.RIGHT_KNEE,
            PoseLandmarkId.RIGHT_KNEE to PoseLandmarkId.RIGHT_ANKLE,
        )
    }
}
