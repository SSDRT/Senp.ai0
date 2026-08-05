package ai.senp.core.cache

import ai.senp.core.contracts.AnalysisPayload
import ai.senp.core.contracts.CacheKey
import ai.senp.core.contracts.CacheLookup
import ai.senp.core.contracts.CachedAnalysis
import ai.senp.core.contracts.StageResult
import ai.senp.core.pipeline.AnalysisCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryAnalysisCache(
    private val maximumEntries: Int = 32,
) : AnalysisCache {
    private val mutex = Mutex()
    private val values = linkedMapOf<CacheKey, CachedAnalysis>()

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
    }

    override suspend fun lookup(key: CacheKey): StageResult<CacheLookup> = StageResult.Success(
        mutex.withLock {
            val value = values.remove(key)
            if (value == null) {
                CacheLookup.Miss
            } else {
                values[key] = value
                CacheLookup.Hit(value.detachedCopy())
            }
        },
    )

    override suspend fun store(key: CacheKey, analysis: CachedAnalysis): StageResult<Unit> {
        mutex.withLock {
            values.remove(key)
            values[key] = analysis.detachedCopy()
            while (values.size > maximumEntries) {
                val oldest = values.keys.first()
                values.remove(oldest)
            }
        }
        return StageResult.Success(Unit)
    }

    suspend fun size(): Int = mutex.withLock { values.size }

    suspend fun clear() {
        mutex.withLock { values.clear() }
    }
}

private fun CachedAnalysis.detachedCopy(): CachedAnalysis = copy(payload = payload.detachedCopy())

private fun AnalysisPayload.detachedCopy(): AnalysisPayload = copy(
    alignment = alignment.copy(points = alignment.points.toList()),
    problems = problems.toList(),
)
