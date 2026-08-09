package ai.senp.validation.model

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object PoseModelSpec {
    const val DISPLAY_NAME = "MediaPipe Pose Landmarker Full"
    const val VERSION = "1"
    const val FILE_NAME = "pose_landmarker_full.task"
    const val EXPECTED_BYTES = 9_398_198L
    const val EXPECTED_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
    const val DOWNLOAD_URL =
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task"
}

sealed interface PoseModelInstallState {
    data object Checking : PoseModelInstallState
    data object Missing : PoseModelInstallState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : PoseModelInstallState
    data class Ready(val file: File) : PoseModelInstallState
    data class Failed(val message: String) : PoseModelInstallState
}

/** Owns the private, hash-pinned on-device pose model used by both video analysis and live mode. */
class PoseModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models")
    val modelFile: File get() = File(modelDirectory, PoseModelSpec.FILE_NAME)

    fun verifiedModelFileOrNull(): File? {
        val candidate = modelFile
        if (!candidate.isFile || candidate.length() != PoseModelSpec.EXPECTED_BYTES) return null
        return if (sha256(candidate) == PoseModelSpec.EXPECTED_SHA256) candidate else null
    }

    fun removeInvalidModel() {
        val candidate = modelFile
        if (candidate.exists() && verifiedModelFileOrNull() == null) candidate.delete()
        File(modelDirectory, PoseModelSpec.FILE_NAME + ".part").delete()
    }

    suspend fun download(onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        verifiedModelFileOrNull()?.let { return@withContext it }
        modelDirectory.mkdirs()
        require(modelDirectory.isDirectory) { "Unable to create private model directory" }

        val partial = File(modelDirectory, PoseModelSpec.FILE_NAME + ".part")
        partial.delete()

        val connection = (URL(PoseModelSpec.DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            useCaches = false
        }

        try {
            connection.connect()
            val status = connection.responseCode
            require(status in 200..299) { "Model download failed with HTTP $status" }
            val serverLength = connection.contentLengthLong
            if (serverLength > 0L) {
                require(serverLength == PoseModelSpec.EXPECTED_BYTES) {
                    "Unexpected model size from server: $serverLength bytes"
                }
            }

            var copied = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        require(copied <= PoseModelSpec.EXPECTED_BYTES) { "Downloaded model is larger than expected" }
                        if (copied - lastReported >= 128 * 1024 || copied == PoseModelSpec.EXPECTED_BYTES) {
                            lastReported = copied
                            onProgress(copied, PoseModelSpec.EXPECTED_BYTES)
                        }
                    }
                }
            }

            require(partial.length() == PoseModelSpec.EXPECTED_BYTES) {
                "Incomplete model download: ${partial.length()} of ${PoseModelSpec.EXPECTED_BYTES} bytes"
            }
            val actualSha = sha256(partial)
            require(actualSha == PoseModelSpec.EXPECTED_SHA256) {
                "Downloaded model failed integrity verification"
            }

            try {
                Files.move(
                    partial.toPath(),
                    modelFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    partial.toPath(),
                    modelFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            require(verifiedModelFileOrNull() != null) { "Installed model failed final verification" }
            modelFile
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    companion object {
        fun sha256(file: File): String = file.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
