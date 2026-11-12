package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.*

class UiprmdReplayScopeTest {
    private fun manifest(subjects: List<String>, validation: Boolean, testOpened: Boolean = false): JsonObject = buildJsonObject {
        put("schema", "uiprmd-${if (validation) "validation" else "development"}-export/1")
        put("subjects_opened",JsonArray(subjects.map { JsonPrimitive(it) }))
        put("validation_trajectories_opened",validation);put("test_trajectories_opened",testOpened)
        put("records",JsonArray(subjects.flatMap { s -> listOf("m01","m07").flatMap { m -> listOf(true,false).map { c ->
            buildJsonObject { put("subject",s);put("movement",m);put("correct",c) }
        } } }))
    }

    @Test fun splitGuardAcceptsOnlyTheDeclaredCompleteCohort() {
        UiprmdSkeletalReplay.verifySubjects(manifest(listOf("01","02","03","04"),false),false)
        UiprmdSkeletalReplay.verifySubjects(manifest(listOf("05","06","07"),true),true)
        assertFailsWith<IllegalArgumentException> {
            UiprmdSkeletalReplay.verifySubjects(manifest(listOf("05","06","08"),true),true)
        }
        assertFailsWith<IllegalArgumentException> {
            UiprmdSkeletalReplay.verifySubjects(manifest(listOf("05","06","07"),true,true),true)
        }
        assertFailsWith<IllegalArgumentException> {
            UiprmdSkeletalReplay.verifySubjects(manifest(listOf("05","06","07"),true),false)
        }
    }

    @Test fun duplicateAndIncompleteRecordingsAreRejectedBeforeReadingFiles() {
        val original = manifest(listOf("05","06","07"),true)
        val records = original.getValue("records").jsonArray
        for (changed in listOf(records.dropLast(1),records.dropLast(1)+records.first())) {
            val modified = JsonObject(original+mapOf("records" to JsonArray(changed)))
            assertFailsWith<IllegalArgumentException> { UiprmdSkeletalReplay.verifySubjects(modified,true) }
        }
    }
}
