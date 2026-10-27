package ai.senp.motion

import kotlin.test.*
import kotlinx.serialization.json.*

class LiteReferenceEvidenceTest {
    private fun identity(sha: String = LiteReferenceEvidence.SHA, pipeline: String = LiteReferenceEvidence.PIPELINE, variant: String = "lite") = buildJsonObject {
        put("schema","mp33-lite-reference-research/1");put("model_sha256",sha);put("preprocessing_id",pipeline);put("model_variant",variant)
    }
    @Test fun onlyPinnedLiteIdentityIsAllowed() {
        LiteReferenceEvidence.identity(identity())
        for (sha in listOf("5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1","64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b"))
            assertFailsWith<IllegalArgumentException> { LiteReferenceEvidence.identity(identity(sha)) }
    }
    @Test fun otherPipelineOrVariantCannotReuseLiteModelIdentity() {
        assertFailsWith<IllegalArgumentException> { LiteReferenceEvidence.identity(identity(pipeline = RepEvidenceFingerprint.RAW_WORLD_PIPELINE)) }
        assertFailsWith<IllegalArgumentException> { LiteReferenceEvidence.identity(identity(variant = "full")) }
    }
    @Test fun lowConfidenceAndMissingRawOutputStayRawWithoutReconstruction() {
        val p = List(33) { listOf(.5,.5,.1,.1,.2,-2.0,3.0,4.0) }
        LiteReferenceEvidence.raw(p);LiteReferenceEvidence.raw(emptyList())
        assertFalse(RotationReferenceMapping.sameSubject(p,p))
        assertEquals(.1,p[0][3]);assertEquals(.2,p[0][4])
    }
    @Test fun partialNonfiniteOrInvalidProbabilityOutputFailsClosed() {
        val p = List(33) { List(8) { .5 } }
        assertFailsWith<IllegalArgumentException> { LiteReferenceEvidence.raw(p.drop(1)) }
        for ((axis,value) in listOf(3 to 1.1,4 to -.1,5 to Double.NaN)) {
            val changed = p.mapIndexed { i,v -> if (i==0) v.mapIndexed { j,d -> if (j==axis) value else d } else v }
            assertFailsWith<IllegalArgumentException> { LiteReferenceEvidence.raw(changed) }
        }
    }
}
