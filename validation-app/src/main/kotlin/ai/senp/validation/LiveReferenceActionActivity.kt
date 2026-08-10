package ai.senp.validation

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.PhaseTimingClass
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var lastTimingNote: String? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionRequired()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expectedReferenceSha256 = intent.getStringExtra(EXTRA_REFERENCE_SHA256)
        preparedReference = expectedReferenceSha256?.let(ReferenceActionProfileStore::get)
        setContentView(buildUi())

        val prepared = preparedReference
        if (prepared == null) {
            showFatal(
                "Reference profile unavailable",
                "The prepared reference no longer matches this live session. Return to Senp.ai and reopen live comparison from the current REFERENCE READY card.",
            )
            return
        }
        processor = LiveReferenceActionProcessor(
            profile = prepared.profile,
            analysisFramesPerSecond = prepared.analysisFramesPerSecond,
        )
        phaseView.text = "READY • MOVE LIKE YOUR REFERENCE"
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
            text = "LIVE COACH"
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
            text = "Move naturally. Coaching appears when a difference from this reference persists."
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
                lastTimingNote = null
                repsView.text = "0"
                primaryCueView.text = "Waiting for the reference action"
                secondaryCueView.text = ""
                detailView.text = "Move naturally. Coaching appears when a difference from this reference persists."
            }
        }
        bottom.addView(label)
        bottom.addView(primaryCueView)
        bottom.addView(secondaryCueView)
        bottom.addView(detailView)
        bottom.addView(resetButton, LinearLayout.LayoutParams(MATCH, dp(50)))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(dp(20) + bars.left, dp(18) + bars.top, dp(20) + bars.right, dp(14))
            progressLayoutParams.topMargin = dp(78) + bars.top
            progress.layoutParams = progressLayoutParams
            bottom.setPadding(dp(20) + bars.left, dp(15), dp(20) + bars.right, dp(22) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return root
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
                    render(output)
                    overlayView.setPose(
                        pose = pose,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        cueKey = output.feedback.primary?.stableKey,
                    )
                }
            } finally {
                upright.recycle()
            }
        } catch (error: Throwable) {
            runOnUiThread {
                primaryCueView.text = "Hold steady — reacquiring pose"
                secondaryCueView.text = ""
                detailView.text = "Keep your full body in view while tracking recovers."
            }
        } finally {
            frameBusy.set(false)
            image.close()
        }
    }

    private fun render(output: LiveReferenceActionOutput) {
        preparedReference ?: return
        val estimate = output.estimate
        repsView.text = estimate.completedRepetitions.toString()
        estimate.timing?.let { timing ->
            if (timing.confidence >= 0.45) {
                lastTimingNote = when (timing.classification) {
                    PhaseTimingClass.FASTER -> "Pace: a little faster than your reference"
                    PhaseTimingClass.WITHIN_REFERENCE_RANGE -> "Pace: matched this part of your reference"
                    PhaseTimingClass.SLOWER -> "Pace: a little slower than your reference"
                }
            }
        }
        phaseView.text = when (estimate.status) {
            ActionTrackingStatus.NO_ACTION -> "READY • START THE MOVEMENT"
            ActionTrackingStatus.POSSIBLE_ENTRY -> "FOUND IT • KEEP MOVING"
            ActionTrackingStatus.TRACKING -> "TRACKING YOUR MOVEMENT"
            ActionTrackingStatus.LOST -> "MOVEMENT LOST • KEEP GOING"
            ActionTrackingStatus.COMPLETED -> "MOVEMENT COMPLETE"
        }

        if (output.poseConfidence < 0.35) {
            primaryCueView.text = "Move into view — finding pose"
            secondaryCueView.text = ""
            detailView.text = "Step back until your full body is visible."
        } else {
            when (estimate.status) {
                ActionTrackingStatus.NO_ACTION -> {
                    primaryCueView.text = "Waiting for the reference action"
                    secondaryCueView.text = "Start naturally when you are ready."
                    detailView.text = "Coaching is relative to the movement you selected as the reference."
                }
                ActionTrackingStatus.POSSIBLE_ENTRY -> {
                    primaryCueView.text = "Keep moving"
                    secondaryCueView.text = "I found the movement and am locking onto it."
                    detailView.text = lastTimingNote ?: "Coaching appears after the movement is confidently tracked."
                }
                ActionTrackingStatus.LOST -> {
                    primaryCueView.text = "Return to the movement"
                    secondaryCueView.text = ""
                    detailView.text = "Keep your full body visible and continue the same motion."
                }
                ActionTrackingStatus.COMPLETED -> renderTrackingCoach(output, completed = true)
                ActionTrackingStatus.TRACKING -> renderTrackingCoach(output, completed = false)
            }
        }
        primaryCueView.setTextColor(
            if (output.feedback.primary != null) Color.rgb(255, 206, 127) else Color.WHITE,
        )
    }

    private fun renderTrackingCoach(output: LiveReferenceActionOutput, completed: Boolean) {
        val cue = output.feedback.primary
        if (cue == null) {
            primaryCueView.text = if (completed) "Movement complete" else "Matched this part — keep moving"
            secondaryCueView.text = ""
            detailView.text = lastTimingNote ?: "No persistent difference is standing out right now."
            return
        }
        primaryCueView.text = cue.label
        secondaryCueView.text = when {
            cue.severity >= 0.70 -> "Large difference from your reference"
            cue.severity >= 0.40 -> "Noticeable difference from your reference"
            else -> "Small difference from your reference"
        }
        detailView.text = lastTimingNote ?: "Focus on this one adjustment, then keep the movement flowing."
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
        const val EXTRA_REFERENCE_SHA256 = "reference_sha256"

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
    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 206, 127)
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
    }
    private val cuePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 206, 127)
        style = Paint.Style.FILL
    }
    private var pose: PoseFrame? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var cueJoint: PoseLandmarkId? = null

    fun setPose(pose: PoseFrame, frameWidth: Int, frameHeight: Int, cueKey: String?) {
        this.pose = pose
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        cueJoint = cueKey?.toCueJoint()
        invalidate()
    }

    fun clearPose() {
        pose = null
        cueJoint = null
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
                if (from == cueJoint || to == cueJoint) cuePaint else skeletonPaint,
            )
        }
        current.landmarks.forEach { landmark ->
            if (!drawable(landmark)) return@forEach
            canvas.drawCircle(
                target.left + (landmark.image.x * target.width()).toFloat(),
                target.top + (landmark.image.y * target.height()).toFloat(),
                if (landmark.id == cueJoint) 9f else 4f,
                if (landmark.id == cueJoint) cuePointPaint else pointPaint,
            )
        }
    }

    private fun String.toCueJoint(): PoseLandmarkId? = when (substringBefore('|').removePrefix("angle.")) {
        "left_shoulder" -> PoseLandmarkId.LEFT_SHOULDER
        "right_shoulder" -> PoseLandmarkId.RIGHT_SHOULDER
        "left_elbow" -> PoseLandmarkId.LEFT_ELBOW
        "right_elbow" -> PoseLandmarkId.RIGHT_ELBOW
        "left_hip" -> PoseLandmarkId.LEFT_HIP
        "right_hip" -> PoseLandmarkId.RIGHT_HIP
        "left_knee" -> PoseLandmarkId.LEFT_KNEE
        "right_knee" -> PoseLandmarkId.RIGHT_KNEE
        "left_ankle" -> PoseLandmarkId.LEFT_ANKLE
        "right_ankle" -> PoseLandmarkId.RIGHT_ANKLE
        else -> null
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
