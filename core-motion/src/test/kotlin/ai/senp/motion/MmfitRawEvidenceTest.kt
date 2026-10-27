package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.*

class MmfitRawEvidenceTest {
    private fun row(time: Long, count: Int = 33, confidence: Double = .8, worldConfidenceCount: Int = count) = buildJsonObject {
        put("timestamp_ms",time)
        put("landmarks",JsonArray(List(count) { JsonArray(listOf(.5,.5,0.0,confidence,confidence,1.0,2.0,3.0).map(::JsonPrimitive)) }))
        put("raw_world_visibility_presence",JsonArray(List(worldConfidenceCount) { JsonArray(listOf(JsonPrimitive(.7),JsonPrimitive(.9))) }))
    }
    @Test fun retainsMissingFramesAndDoesNotRepairLowConfidence() {
        MmfitReferenceCalibrationAudit.verifyRaw(listOf(row(8000),row(8067,0),row(8133,confidence=.1)),8000,9000)
    }
    @Test fun refusesRetimedDuplicatedReorderedAndOutOfIntervalEvidence() {
        for (times in listOf(listOf(8000L,8000L),listOf(8067L,8000L),listOf(7999L,8067L),listOf(8000L,9001L))) {
            assertFailsWith<IllegalArgumentException> { MmfitReferenceCalibrationAudit.verifyRaw(times.map { row(it) },8000,9000) }
        }
    }
    @Test fun refusesIncompleteRawChannelsAndFabricatedConfidence() {
        for (bad in listOf(row(8000,32),row(8000,confidence=1.1),row(8000,worldConfidenceCount=32))) {
            assertFailsWith<IllegalArgumentException> { MmfitReferenceCalibrationAudit.verifyRaw(listOf(bad),8000,9000) }
        }
    }
}
