package ai.senp.motion
import kotlinx.serialization.json.*
import kotlin.test.*

class ClaheReferenceEvidenceTest {
    @Test fun photometryCannotChangeOutputCoordinatesWorldValuesOrConfidenceAfterInference() {
        val raw=List(33) { listOf(.2,.3,-.1,.8,.9,.1,-.2,.3) }
        ClaheReferenceEvidence.unmodified(raw,raw,true);ClaheReferenceEvidence.unmodified(raw,emptyList(),false)
        for(i in 0..7) {
            val changed=raw.mapIndexed { j,p -> if(j==0) p.mapIndexed { k,v -> if(k==i) v+.001 else v } else p }
            assertFailsWith<IllegalArgumentException> { ClaheReferenceEvidence.unmodified(raw,changed,true) }
        }
        assertFailsWith<IllegalArgumentException> { ClaheReferenceEvidence.unmodified(raw,raw,false) }
    }
    @Test fun inputPipelinesStayDistinctFromRawCameraAndEachOther() {
        fun payload(pipeline: String,schema: String,sha: String=ClaheReferenceEvidence.SHA) = buildJsonObject { put("preprocessing_id",pipeline);put("schema",schema);put("model_sha256",sha) }
        val good=payload(ClaheReferenceEvidence.PIPELINE,"mp33-clahe-reference-research/1")
        ClaheReferenceEvidence.identity(good,"clahe")
        assertFailsWith<IllegalArgumentException> { ClaheReferenceEvidence.identity(good,"original") }
        assertFailsWith<IllegalArgumentException> { ClaheReferenceEvidence.identity(payload("mp33-raw-world-workout/1","mp33-clahe-reference-research/1"),"clahe") }
        assertFailsWith<IllegalArgumentException> { ClaheReferenceEvidence.identity(payload(ClaheReferenceEvidence.PIPELINE,"mp33-clahe-reference-research/1","f".repeat(64)),"clahe") }
    }
}
