package ai.senp.core.contracts

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TimestampMs(val value: Long) : Comparable<TimestampMs> {
    init {
        require(value >= 0) { "timestamp must be non-negative" }
    }

    override fun compareTo(other: TimestampMs): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class DurationMs(val value: Long) : Comparable<DurationMs> {
    init {
        require(value >= 0) { "duration must be non-negative" }
    }

    override fun compareTo(other: DurationMs): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class Sha256(val value: String) {
    init {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }

    override fun toString(): String = value
}

internal fun requireFinite(value: Double, name: String) {
    require(value.isFinite()) { "$name must be finite" }
}

internal fun requireProbability(value: Double, name: String) {
    requireFinite(value, name)
    require(value in 0.0..1.0) { "$name must be in [0, 1]" }
}

internal fun requireVersion(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= 128) { "$name must be at most 128 characters" }
}
