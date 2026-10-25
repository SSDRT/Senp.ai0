package ai.senp.motion

import kotlin.test.*
import kotlinx.serialization.json.*

class ImageReferenceMappingTest {
    private fun row(change: String = ""): JsonObject {
        val raw = List(33) { listOf(.25,.4,.1,.95,.9,1.0,2.0,3.0) }
        val mapped = raw.map { listOf(.375,.325,.05,it[3],it[4],it[5],it[6],it[7]) }.toMutableList()
        if (change == "world") mapped[0] = mapped[0].toMutableList().also { it[7] += .01 }
        if (change == "confidence") mapped[0] = mapped[0].toMutableList().also { it[3] += .01 }
        if (change == "xy") mapped[0] = mapped[0].toMutableList().also { it[0] += .01 }
        fun points(values: List<List<Double>>) = buildJsonArray { values.forEach { p -> add(buildJsonArray { p.forEach { add(JsonPrimitive(it)) } }) } }
        return buildJsonObject {
            put("timestamp_ms",100);put("coarse_timestamp_ms",if (change == "future") 133 else 100)
            put("image_width",400);put("image_height",400);put("subject_bound",change != "unbound")
            put("crop_bounds_xyxy",buildJsonArray { listOf(100,50,300,250).forEach { add(JsonPrimitive(it)) } })
            put("raw_input_landmarks",points(raw));put("landmarks",points(if (change == "short") mapped.take(32) else mapped))
            put("raw_world_visibility_presence",points(List(33) { listOf(.95,.9) }))
        }
    }
    @Test fun unchangedWorldConfidenceAndExactInverseMappingPass() {
        ImageReferenceCalibrationAudit.verifyMapping(row())
    }
    @Test fun changedWorldConfidenceGeometryOrTimestampCannotEnterCalibration() {
        for (change in listOf("world","confidence","xy","future","short","unbound")) {
            assertFailsWith<IllegalArgumentException>(change) { ImageReferenceCalibrationAudit.verifyMapping(row(change)) }
        }
    }
}
