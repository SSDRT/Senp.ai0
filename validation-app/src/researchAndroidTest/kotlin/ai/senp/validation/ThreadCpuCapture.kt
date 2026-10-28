package ai.senp.validation

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Raw self-process procfs census. Missing/racing entries stay explicit, never zero CPU. */
internal object ThreadCpuCapture {
    fun snapshot(): JSONObject {
        val start=System.nanoTime();val rows=JSONArray();var enumerationError: String?=null
        val files=try { File("/proc/self/task").listFiles()?.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
            ?: run { enumerationError="THREAD_DIRECTORY_UNAVAILABLE";emptyList() } }
            catch(error: Exception) { enumerationError=error.toString();emptyList() }
        for(file in files) {
            val row=JSONObject().put("tid",file.name)
            try { row.put("raw_stat",File(file,"stat").readText()) }
            catch(error: Exception) { row.put("read_error",error.toString()) }
            rows.put(row)
        }
        return JSONObject().put("scan_start_ns",start).put("scan_end_ns",System.nanoTime())
            .put("enumeration_error",enumerationError ?: JSONObject.NULL).put("threads",rows)
    }
}
