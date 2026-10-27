package ai.senp.pose.mediapipe

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Let the pinned TaskRunner finish teardown when it reports an earlier graph error
 * again during close. Inference errors and unexpected direct close failures still throw. */
internal class LivePoseTaskCloser {
    private val closing = AtomicBoolean(false)
    private val reported = ConcurrentLinkedQueue<RuntimeException>()

    fun onError(error: RuntimeException) {
        if (closing.get()) reported.add(error) else throw error
    }

    fun close(action: () -> Unit): List<RuntimeException> {
        check(closing.compareAndSet(false,true)) { "Native task shutdown already in progress" }
        reported.clear()
        try { action() } finally { closing.set(false) }
        return reported.toList()
    }
}
