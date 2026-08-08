package ai.senp.validation

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.motion.PushUpCue
import ai.senp.motion.PushUpLiveEvaluator
import ai.senp.motion.PushUpLiveFeedback
import ai.senp.motion.PushUpPhase
import ai.senp.pose.mediapipe.LiveMediaPipePoseEstimator
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/**
 * A deliberately isolated phone surface for real-time push-up feedback.
 * Offline comparison and validation remain available through [ValidationActivity].
 */
class LivePushUpActivity : ComponentActivity() {
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "senp-live-pushup").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val evaluator = PushUpLiveEvaluator()
    private val frameBusy = AtomicBoolean(false)

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: LivePoseOverlayView
    private lateinit var repsView: TextView
    private lateinit var cueView: TextView
    private lateinit var phaseView: TextView
    private lateinit var metricsView: TextView
    private lateinit var rejectedView: TextView
    private lateinit var resetButton: Button

    @Volatile private var estimator: LiveMediaPipePoseEstimator? = null
    @Volatile private var lastTimestampMs = -1L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionRequired()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
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
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 10, 14))
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        overlayView = LivePoseOverlayView(this)
        root.addView(overlayView, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(Color.argb(150, 5, 7, 10))
        }
        val title = TextView(this).apply {
            text = "PUSH-UP ARENA"
            setTextColor(Color.WHITE)
            textSize = 15f
            letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        phaseView = TextView(this).apply {
            text = "SIDE VIEW • GET READY"
            setTextColor(Color.rgb(184, 224, 255))
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        top.addView(title)
        top.addView(phaseView)
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP })

        val counterPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        repsView = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 80f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        val repsLabel = TextView(this).apply {
            text = "VALID REPS"
            setTextColor(Color.rgb(203, 211, 222))
            textSize = 13f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
        }
        rejectedView = TextView(this).apply {
            text = "0 attempts rejected"
            setTextColor(Color.rgb(255, 198, 130))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        counterPanel.addView(repsView)
        counterPanel.addView(repsLabel)
        counterPanel.addView(rejectedView)
        root.addView(counterPanel, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(82)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(22))
            setBackgroundColor(Color.argb(210, 7, 10, 15))
        }
        cueView = TextView(this).apply {
            text = "Stand sideways and fit your whole body"
            setTextColor(Color.WHITE)
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        metricsView = TextView(this).apply {
            text = "Elbow —   Body —   Tracking —"
            setTextColor(Color.rgb(185, 195, 208))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(14))
        }
        resetButton = Button(this).apply {
            text = "Reset set"
            textSize = 16f
            minHeight = dp(52)
            isAllCaps = false
            setOnClickListener {
                evaluator.reset()
                overlayView.clearPose()
                render(
                    PushUpLiveFeedback(
                        timestampMs = SystemClock.elapsedRealtime(),
                        correctReps = 0,
                        rejectedAttempts = 0,
                        phase = PushUpPhase.SEARCHING,
                        cue = PushUpCue.GET_IN_START_POSITION,
                        selectedSide = null,
                        elbowDegrees = null,
                        bodyLineDegrees = null,
                        sideViewScore = 0.0,
                        trackingConfidence = 0.0,
                        fullBodyInFrame = false,
                    ),
                )
            }
        }
        bottom.addView(cueView)
        bottom.addView(metricsView)
        bottom.addView(resetButton, LinearLayout.LayoutParams(MATCH, dp(52)))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })
        return root
    }

    private fun startCamera() {
        cueView.text = "Starting on-device pose model…"
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
                    cueView.text = "Stand sideways and fit your whole body"
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
            val raw = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = rotate(raw, rotation)
            if (upright !== raw) raw.recycle()
            try {
                val candidate = image.imageInfo.timestamp / 1_000_000L
                val timestampMs = if (candidate > lastTimestampMs) candidate else lastTimestampMs + 1L
                lastTimestampMs = timestampMs
                val pose = poseEstimator.estimate(upright, timestampMs)
                val feedback = evaluator.update(pose)
                val frameWidth = upright.width
                val frameHeight = upright.height
                runOnUiThread {
                    overlayView.setPose(pose, frameWidth, frameHeight, feedback)
                    render(feedback)
                }
            } finally {
                upright.recycle()
            }
        } catch (error: Throwable) {
            runOnUiThread {
                cueView.text = "Hold steady — reacquiring pose"
                metricsView.text = error.javaClass.simpleName
            }
        } finally {
            frameBusy.set(false)
            image.close()
        }
    }

    private fun render(feedback: PushUpLiveFeedback) {
        repsView.text = feedback.correctReps.toString()
        rejectedView.text = "${feedback.rejectedAttempts} attempts rejected"
        cueView.text = feedback.cue.displayText()
        phaseView.text = when {
            feedback.cue == PushUpCue.TURN_SIDEWAYS -> "SIDE VIEW REQUIRED"
            feedback.trackingConfidence < 0.45 -> "FINDING POSE"
            else -> "LIVE • ${feedback.phase.name.replace('_', ' ')}"
        }
        metricsView.text = String.format(
            Locale.ROOT,
            "Elbow %s   Body %s   Tracking %.0f%%",
            feedback.elbowDegrees?.let { "%.0f°".format(Locale.ROOT, it) } ?: "—",
            feedback.bodyLineDegrees?.let { "%.0f°".format(Locale.ROOT, it) } ?: "—",
            feedback.trackingConfidence * 100.0,
        )
        val success = feedback.cue == PushUpCue.GOOD_REP
        val warning = feedback.cue in setOf(
            PushUpCue.GO_LOWER,
            PushUpCue.KEEP_BODY_STRAIGHT,
            PushUpCue.TURN_SIDEWAYS,
            PushUpCue.MOVE_FULL_BODY_IN_FRAME,
        )
        cueView.setTextColor(
            when {
                success -> Color.rgb(105, 240, 174)
                warning -> Color.rgb(255, 199, 118)
                else -> Color.WHITE
            },
        )
    }

    private fun showPermissionRequired() {
        cueView.text = "Camera permission is required for live form checking"
        metricsView.text = "No video is recorded or uploaded"
        resetButton.isEnabled = false
    }

    private fun showFatal(title: String, detail: String) {
        cueView.text = title
        metricsView.text = detail.take(180)
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

private class LivePoseOverlayView(context: android.content.Context) : View(context) {
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 105, 225, 255)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 240, 174)
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private var pose: PoseFrame? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var feedback: PushUpLiveFeedback? = null

    fun setPose(pose: PoseFrame, frameWidth: Int, frameHeight: Int, feedback: PushUpLiveFeedback) {
        this.pose = pose
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        this.feedback = feedback
        invalidate()
    }

    fun clearPose() {
        pose = null
        feedback = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = pose ?: return
        if (current.validity.status in setOf(FrameValidityStatus.BLIND, FrameValidityStatus.CONTINUITY_BREAK)) return
        if (frameWidth <= 0 || frameHeight <= 0) return

        val target = fittedRect(width.toFloat(), height.toFloat(), frameWidth.toFloat(), frameHeight.toFloat())
        val byId = current.landmarks.associateBy { it.id }
        val selectedIds = when (feedback?.selectedSide?.name) {
            "LEFT" -> setOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_ANKLE)
            "RIGHT" -> setOf(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_ANKLE)
            else -> emptySet()
        }
        CONNECTIONS.forEach { (from, to) ->
            val a = byId.getValue(from)
            val b = byId.getValue(to)
            if (!drawable(a) || !drawable(b)) return@forEach
            val paint = if (from in selectedIds && to in selectedIds) selectedPaint else skeletonPaint
            canvas.drawLine(
                target.left + (a.image.x * target.width()).toFloat(),
                target.top + (a.image.y * target.height()).toFloat(),
                target.left + (b.image.x * target.width()).toFloat(),
                target.top + (b.image.y * target.height()).toFloat(),
                paint,
            )
        }
        current.landmarks.forEach { landmark ->
            if (!drawable(landmark)) return@forEach
            canvas.drawCircle(
                target.left + (landmark.image.x * target.width()).toFloat(),
                target.top + (landmark.image.y * target.height()).toFloat(),
                if (landmark.id in selectedIds) 6f else 4f,
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
        val width = contentWidth * scale
        val height = contentHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
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

private fun PushUpCue.displayText(): String = when (this) {
    PushUpCue.NO_POSE -> "Move into view"
    PushUpCue.TURN_SIDEWAYS -> "Turn sideways to the camera"
    PushUpCue.MOVE_FULL_BODY_IN_FRAME -> "Fit shoulders, hands and feet in frame"
    PushUpCue.HOLD_STEADY -> "Hold steady — finding joints"
    PushUpCue.GET_IN_START_POSITION -> "Straight-arm plank to start"
    PushUpCue.LOWER_CHEST -> "Lower with a straight body"
    PushUpCue.GO_LOWER -> "Go lower — reach full depth"
    PushUpCue.KEEP_BODY_STRAIGHT -> "Keep shoulders, hips and ankles aligned"
    PushUpCue.PRESS_UP -> "Press up"
    PushUpCue.LOCK_OUT -> "Straighten your arms to finish"
    PushUpCue.GOOD_REP -> "Good rep"
}
