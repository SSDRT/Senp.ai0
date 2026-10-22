package ai.senp.pose.mediapipe

import android.content.Context
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Avoid MediaPipe's relative-path cache rewrite: another graph may still mmap that cache.
 * Map the immutable APK asset instead, verify those exact bytes, and retain the buffer in
 * the estimator until its graph closes. Compressed custom assets use direct memory.
 */
internal fun verifiedPoseModelBuffer(context: Context, assetPath: String, expectedSha256: String): ByteBuffer {
    val buffer = try {
        context.assets.openFd(assetPath).use { asset ->
            FileInputStream(asset.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }
    } catch (_: IOException) {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(buffer.duplicate())
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == expectedSha256) { "Pose model SHA-256 mismatch: expected $expectedSha256, got $actual" }
    return buffer
}
