package ai.senp.validation

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.motion.ActionMirrorMode
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.LiveFeedbackUncertainty
import ai.senp.motion.ExerciseTechniqueCatalog
import ai.senp.motion.QualifiedRepConfig
import ai.senp.motion.QualifiedRepReference
import ai.senp.motion.RepObservedSide
import ai.senp.motion.RepPolicyMode
import ai.senp.pose.mediapipe.LiveFrameGate
import ai.senp.pose.mediapipe.LiveMediaPipePoseEstimator
import ai.senp.pose.mediapipe.LivePoseModel
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.Sha256
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Volatile internal var analyzedFrameCount: Long = 0
        private set
    @Volatile internal var activePoseModelSha256: String? = null
        private set
    @Volatile internal var cameraDiagnostics: LiveCameraDiagnostics? = null
    @Volatile private var cameraTimestampSource: Int? = null
    private val frameGate = LiveFrameGate()
    @Volatile private var destroyed = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preparedReference: PreparedReferenceAction? = null

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
        val exerciseId = intent.getStringExtra(EXTRA_EXERCISE_ID) ?: prepared.exerciseId ?: prepared.qualifiedRepReference?.exerciseId
        if (exerciseId != null && ExerciseTechniqueCatalog.builtIn.find(exerciseId) == null) {
            showFatal("Unknown exercise variant", "Choose an exercise ID from the technique catalogue.")
            return
        }
        val enableLearnedQuality = intent.getBooleanExtra(EXTRA_LEARNED_QUALITY, prepared.enableLearnedQuality)
        if (enableLearnedQuality && exerciseId == null) {
            showFatal("Exercise selection required", "Choose an explicit exercise before enabling learned quality diagnostics.")
            return
        }
        val enableQualifiedReps = intent.getBooleanExtra(EXTRA_QUALIFIED_REPS,prepared.qualifiedRepReference != null)
        if (enableQualifiedReps) {
            val identity = prepared.repEvidenceFingerprint ?: prepared.qualifiedRepReference?.fingerprint
            val config = runCatching {
                val defaults = prepared.qualifiedRepReference?.config ?: QualifiedRepConfig()
                defaults.copy(
                    side = intent.getStringExtra(EXTRA_REP_SIDE)?.let(RepObservedSide::valueOf) ?: defaults.side,
                    mode = intent.getStringExtra(EXTRA_REP_POLICY)?.let(RepPolicyMode::valueOf) ?: defaults.mode,
                )
            }.getOrNull()
            if (exerciseId == null || identity == null || config == null) {
                showFatal("Rep counting unavailable", "An explicit exercise, side and compatible reference provenance are required.")
                return
            }
            phaseView.text = "PREPARING REP COMPARISON"
            lifecycleScope.launch {
                try {
                    val candidate = withContext(Dispatchers.Default) {
                        val retained = prepared.qualifiedRepReference?.takeIf { it.config == config && it.exerciseId == exerciseId }
                        if (retained != null && prepared.repSource != null) {
                            QualifiedReferencePreparation.Ready(retained,prepared.repSource,prepared.repPreparationRequest,emptyList())
                        } else prepared.repSource?.let {
                            val model = prepared.repPreparationRequest?.model
                            EngineComposition(applicationContext).referenceActionSession.prepareQualifiedReference(
                                it,identity,prepared.repPreparationRequest,exerciseId,config,
                                if (identity.poseModelSha256 == LivePoseModel.FULL.sha256) PoseModelConfiguration(
                                    Sha256(LivePoseModel.HEAVY.sha256),"pose-landmarker-heavy",model?.thresholds ?: ai.senp.core.contracts.PoseThresholds()) else null,
                            )
                        }
                    }
                    if (destroyed) return@launch
                    when (candidate) {
                        is QualifiedReferencePreparation.Ready -> {
                            val selected = prepared.copy(profile = candidate.reference.profile,
                                repEvidenceFingerprint = candidate.reference.fingerprint,qualifiedRepReference = candidate.reference,
                                repSource = candidate.extraction,repPreparationRequest = candidate.request)
                            configureProcessor(selected,exerciseId,enableLearnedQuality,candidate.reference)
                        }
                        is QualifiedReferencePreparation.Unavailable -> showFatal("Rep counting unavailable",candidate.detail)
                        null -> showFatal("Reference evidence unavailable","Reopen the uploaded reference to prepare rep counting.")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!destroyed) showFatal("Rep counting unavailable",error.message ?: "This reference could not be prepared for rep counting.")
                }
            }
            return
        }
        configureProcessor(prepared,exerciseId,enableLearnedQuality,null)
    }

    private fun configureProcessor(prepared: PreparedReferenceAction, exerciseId: String?, enableLearnedQuality: Boolean,
        qualifiedReference: QualifiedRepReference?) {
        preparedReference = prepared
        processor = LiveReferenceActionProcessor(
            profile = prepared.profile,
            analysisFramesPerSecond = prepared.analysisFramesPerSecond,
            precisionConfig = prepared.precisionConfig,
            exerciseId = exerciseId,
            enableSampleClassifier = intent.getBooleanExtra(EXTRA_SAMPLE_CLASSIFIER, prepared.enableSampleClassifier),
            enableLearnedQuality = enableLearnedQuality,
            qualifiedRepReference = qualifiedReference,
            repEvidenceFingerprint = prepared.repEvidenceFingerprint,
        )
        phaseView.text = "REFERENCE READY • ${prepared.profile.states.size} STATES • ${(prepared.profile.confidence * 100.0).toInt()}% PROFILE"
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        destroyed = true
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        analyzerExecutor.execute {
            estimator?.close()
            estimator = null
        }
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 9, 12)) }
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
            setBackgroundColor(Color.argb(232, 13, 15, 20))
        }
        val backButton = TextView(this).apply {
            text = "←"
            setTextColor(Color.rgb(140, 99, 255))
            textSize = 26f
            isClickable = true
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        top.addView(backButton, LinearLayout.LayoutParams(WRAP, dp(34)))
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
        root.addView(progress, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(78)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(15), dp(20), dp(22))
            setBackgroundColor(Color.argb(238, 13, 15, 20))
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
        return root
    }

    private fun startCamera() {
        primaryCueView.text = "Starting on-device pose model…"
        analyzerExecutor.execute {
            try {
                estimator = LiveMediaPipePoseEstimator.create(
                    context = this,
                    model = requireNotNull(LivePoseModel.entries.singleOrNull {
                        it.sha256 == (preparedReference?.repEvidenceFingerprint?.poseModelSha256 ?: LivePoseModel.FULL.sha256)
                    }) { "The reference uses an unavailable pose model" },
                    thresholds = preparedReference?.repPreparationRequest?.model?.thresholds ?: ai.senp.core.contracts.PoseThresholds(),
                )
            } catch (error: Throwable) {
                runOnUiThread { showFatal("Pose model unavailable", error.message ?: "Unable to initialize MediaPipe") }
                return@execute
            }
            runOnUiThread { if (!destroyed) bindCamera() }
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    if (destroyed) return@addListener
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val selector = LiveCameraFrame.camera(provider)
                    val frontCamera = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                    overlayView.mirrored = frontCamera
                    val resolution = LiveCameraFrame.resolutionSelector()
                    val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                    val preview = Preview.Builder().setResolutionSelector(resolution)
                        .setTargetRotation(rotation).build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    imageAnalysis = analysis
                    analysis.setAnalyzer(analyzerExecutor, ::analyzeFrame)
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(this, selector, preview, analysis)
                    cameraTimestampSource = runCatching { Camera2CameraInfo.from(camera.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) }.getOrNull()
                    primaryCueView.text = "Waiting for the reference action"
                } catch (error: Throwable) {
                    showFatal("Camera unavailable", error.message ?: "Unable to bind CameraX")
                }
            },
            { command -> runOnUiThread(command) },
        )
    }

    private fun analyzeFrame(image: ImageProxy) {
        val diagnostics = cameraDiagnostics
        val diagnosticArrival = if (diagnostics != null) SystemClock.elapsedRealtimeNanos() else 0L
        if (!frameBusy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val poseEstimator = estimator ?: return
            val liveProcessor = processor ?: return
            if (destroyed) return
            val timestampMs = frameGate.accept(image.imageInfo.timestamp) ?: return
            LiveCameraFrame.withUprightBitmap(image) { upright ->
                val estimate = poseEstimator.estimateDetailed(upright, timestampMs)
                val pose = estimate.frame
                val output = liveProcessor.update(pose, upright.width, upright.height, estimate.modelSha256,estimate.rawFrame)
                activePoseModelSha256 = estimate.modelSha256
                analyzedFrameCount++
                diagnostics?.onFrame(LiveCameraSample(image.imageInfo.timestamp,diagnosticArrival,
                    SystemClock.elapsedRealtimeNanos(),estimate.inferenceNanos,cameraTimestampSource,
                    estimate.modelSha256,estimate.trackerResetCount,frameGate.droppedFrames,estimate.nativeTrackerRecreationCount),upright,estimate.rawFrame)
                val frameWidth = upright.width
                val frameHeight = upright.height
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    overlayView.setPose(pose, frameWidth, frameHeight)
                    render(output)
                }
            }
        } catch (error: Throwable) {
            diagnostics?.onError(error)
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
        repsView.text = (output.qualifiedReps?.counts?.qualified ?: estimate.completedRepetitions).toString()
        repsView.contentDescription = if (output.qualifiedReps != null) "Experimental reference-accepted visible-side repetitions" else "Recognized movement repetitions"
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
        output.qualifiedReps?.let { rep ->
            phaseView.text = "${rep.observedSide.name} SIDE • REFERENCE REPS • ${rep.stage.name.replace('_',' ')}"
            when {
                rep.activeDisqualified -> {
                    primaryCueView.text = "This rep will not count"
                    secondaryCueView.text = "Complete the movement, then restart from the reference position."
                }
                rep.unavailableReason != null -> {
                    primaryCueView.text = "Rep could not be assessed"
                    secondaryCueView.text = "Keep the required body points in view and return to the start position."
                }
                rep.finalizedAttempts.lastOrNull()?.verdict == ai.senp.motion.RepVerdict.REJECTED -> {
                    primaryCueView.text = "Rep not counted"
                    secondaryCueView.text = "Restart from the reference position."
                }
                rep.finalizedAttempts.lastOrNull()?.verdict == ai.senp.motion.RepVerdict.UNASSESSABLE -> {
                    primaryCueView.text = "Rep could not be assessed"
                    secondaryCueView.text = "A complete visible movement is required before a rep can count."
                }
                rep.stage == ai.senp.motion.RepStage.SYNCHRONIZING -> {
                    primaryCueView.text = "Return to the reference start position"
                    secondaryCueView.text = "A complete visible movement is required before a rep can count."
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
            "Reference %.0f%% • action %.0f%% • pose %.0f%% • %s live uncertainty • hints stay relative to this movement",
            profile.confidence * 100.0,
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REFERENCE_SHA256 = "reference_sha256"
        const val EXTRA_EXERCISE_ID = "exercise_technique_id"
        const val EXTRA_SAMPLE_CLASSIFIER = "enable_sample_pose_classifier"
        const val EXTRA_LEARNED_QUALITY = "enable_learned_quality_diagnostics"
        const val EXTRA_QUALIFIED_REPS = "enable_qualified_reps"
        const val EXTRA_REP_SIDE = "qualified_rep_side"
        const val EXTRA_REP_POLICY = "qualified_rep_policy"

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
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
    var mirrored: Boolean = false
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
                target.left + ((if (mirrored) 1.0 - a.image.x else a.image.x) * target.width()).toFloat(),
                target.top + (a.image.y * target.height()).toFloat(),
                target.left + ((if (mirrored) 1.0 - b.image.x else b.image.x) * target.width()).toFloat(),
                target.top + (b.image.y * target.height()).toFloat(),
                skeletonPaint,
            )
        }
        current.landmarks.forEach { landmark ->
            if (!drawable(landmark)) return@forEach
            canvas.drawCircle(
                target.left + ((if (mirrored) 1.0 - landmark.image.x else landmark.image.x) * target.width()).toFloat(),
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
