package ai.senp.evidence

import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.ProblemCertainty
import ai.senp.core.contracts.ProblemWindow
import ai.senp.core.contracts.TimestampMs
import kotlin.math.abs

/** Which moment of a problem window a captured frame is meant to show. */
enum class EvidenceMoment { ENTRY, MIDPOINT, EXIT }

/**
 * One frame worth capturing, and the reference frame it lines up with.
 *
 * [referenceTimestamp] is null when the aligner could not map the window onto the reference, in
 * which case only the user's side can be shown.
 */
data class EvidenceFrame(
    val moment: EvidenceMoment,
    val sourceTimestamp: TimestampMs,
    val referenceTimestamp: TimestampMs?,
)

/** A problem window worth explaining, with the frames that show it. */
data class WindowEvidence(
    val rank: Int,
    val window: ProblemWindow,
    val frames: List<EvidenceFrame>,
)

/**
 * Decides which frames are worth extracting for coaching, without touching video.
 *
 * This runs after the analysis pipeline. The deterministic maths has already decided which
 * windows are problems; this only decides what to show for them, so it stays pure Kotlin and is
 * testable against the headless runner's output with no device, decoder or model.
 *
 * Selection is deliberately narrow. Every extra window costs a video seek, a render, and — once
 * a coaching model reads the frames — tokens, so the default is the three windows most likely
 * to be worth a user's attention rather than everything the aligner flagged.
 */
object EvidenceSelector {

    const val DEFAULT_MAX_WINDOWS: Int = 3

    /**
     * The windows worth showing, most important first.
     *
     * Confirmed problems outrank uncertain ones outright: an uncertain window is one the
     * aligner could not stand behind, and leading with it spends the user's trust on a guess.
     * Within a certainty, higher severity wins; ties break on start time so the order is
     * reproducible for a given analysis.
     */
    fun select(
        problems: List<ProblemWindow>,
        alignment: AlignmentResult,
        maxWindows: Int = DEFAULT_MAX_WINDOWS,
    ): List<WindowEvidence> {
        require(maxWindows > 0) { "maxWindows must be positive, was $maxWindows" }
        return problems
            .sortedWith(
                compareBy<ProblemWindow> { if (it.certainty == ProblemCertainty.GENUINE) 0 else 1 }
                    .thenByDescending { it.severity }
                    .thenBy { it.sourceStart.value },
            )
            .take(maxWindows)
            .mapIndexed { index, window -> WindowEvidence(index, window, framesFor(window, alignment)) }
    }

    /**
     * The frames for one window, snapped onto the alignment path.
     *
     * Snapping is not a rounding convenience. A skeleton overlay needs a pose at the frame it is
     * drawn on, and poses exist only at sampled timestamps; the alignment path carries exactly
     * those, one point per sampled frame. Asking for an arbitrary millisecond would yield a
     * frame the renderer has no pose for. Snapping also supplies the matching reference
     * timestamp from the same point, rather than looking it up separately.
     */
    private fun framesFor(window: ProblemWindow, alignment: AlignmentResult): List<EvidenceFrame> {
        val inside = alignment.points.filter {
            it.sourceTimestamp >= window.sourceStart && it.sourceTimestamp < window.sourceEndExclusive
        }
        val candidates = inside.ifEmpty { alignment.points }
        if (candidates.isEmpty()) return emptyList()

        val lastInside = TimestampMs(window.sourceEndExclusive.value - 1)
        // ponytail: the contract carries peakDeviation but not the timestamp it occurred at, so
        // the midpoint stands in for the peak frame. Add a peak timestamp to ProblemWindow and
        // this becomes the real most-informative frame instead of an approximation.
        val midpoint = TimestampMs((window.sourceStart.value + lastInside.value) / 2)

        return listOf(
            EvidenceMoment.ENTRY to window.sourceStart,
            EvidenceMoment.MIDPOINT to midpoint,
            EvidenceMoment.EXIT to lastInside,
        )
            .map { (moment, target) ->
                val point = candidates.minBy { abs(it.sourceTimestamp.value - target.value) }
                // A window the aligner left unmapped stays unmapped. The path still holds a
                // reference timestamp, but presenting it as the matching pose would invent a
                // correspondence the alignment stage declined to claim.
                val reference = if (window.referenceStart == null) null else point.referenceTimestamp
                EvidenceFrame(moment, point.sourceTimestamp, reference)
            }
            // A window covering one sampled frame would otherwise ask for it three times.
            .distinctBy { it.sourceTimestamp }
    }
}
