package ai.senp.motion

import kotlin.test.*

class UiprmdEvidenceAuditScopeTest {
    @Test fun onlyAlreadyOpenedDevelopmentAndValidationCanBeRead() {
        assertFalse(UiprmdEvidenceAudit.validationScope("development"))
        assertTrue(UiprmdEvidenceAudit.validationScope("validation"))
        for (scope in listOf("test","final","08","../validation","validation-vicon"))
            assertFailsWith<IllegalArgumentException> { UiprmdEvidenceAudit.main(arrayOf(scope,"does-not-exist","does-not-exist")) }
    }
}
