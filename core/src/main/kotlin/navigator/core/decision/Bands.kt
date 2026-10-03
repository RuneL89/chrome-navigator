package navigator.core.decision

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Band for a probability value (ART banding, vision 02 §2). Callers branch on bands,
 * never on exact values — provider probabilities vary between calls.
 */
enum class Band {
    LOW,
    UNCERTAIN,
    HIGH,
}

/**
 * Maps a probability to its band: low `< 0.30`, uncertain `[0.30, 0.70]`,
 * high `> 0.70` (vision 02 §2). Values outside `[0, 1]` are a bug — fail loudly.
 */
fun band(p: Double): Band {
    require(p in 0.0..1.0) { "probability out of range [0,1]: $p" }
    return when {
        p < 0.30 -> Band.LOW
        p > 0.70 -> Band.HIGH
        else -> Band.UNCERTAIN
    }
}
