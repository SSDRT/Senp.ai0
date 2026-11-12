package ai.senp.motion

import kotlin.test.*
import kotlinx.serialization.json.*

class ReadyFormValidationScopeTest {
    private fun protocol() = buildJsonObject {
        put("schema","ready-form-validation/1");put("compiler",QualifiedRepCompiler.VERSION)
        put("validation_access",true);put("final_test_access",false);put("reference_subject","01")
        put("subjects",JsonArray(listOf("05","06","07").map(::JsonPrimitive)))
    }
    @Test fun onlyTheExplicitAlreadyOpenedValidationScopeIsAuthorized() {
        UiprmdSkeletalReplay.verifyReadyFormScope(protocol())
        for((key,value) in listOf("final_test_access" to JsonPrimitive(true),"validation_access" to JsonPrimitive(false),
            "subjects" to JsonArray(listOf("08","09","10").map(::JsonPrimitive)),"reference_subject" to JsonPrimitive("05"),
            "compiler" to JsonPrimitive("different"))) {
            assertFailsWith<IllegalArgumentException> { UiprmdSkeletalReplay.verifyReadyFormScope(JsonObject(protocol()+(key to value))) }
        }
    }
}
