package ec.finanzas.model

import java.time.Instant

data class CaptureSession(val startedAt: Instant, val endedAt: Instant? = null) {
    init { require(endedAt == null || endedAt >= startedAt) }
    fun contains(time: Instant): Boolean = time >= startedAt && (endedAt == null || time < endedAt)
}

data class AllowedSource(
    val packageName: String,
    val provider: BankProvider?,
    val enabled: Boolean = false,
    val enabledSince: Instant? = null,
) {
    init { require(!enabled || enabledSince != null) }
}

enum class CaptureState { NOT_ACTIVATED, ACTIVE, PAUSED, PERMISSION_REVOKED, DISCONNECTED }

/** Solo la sesión actual autoriza callbacks: una sesión pasada no permite rellenar pausas. */
object CapturePolicy {
    fun accepts(
        source: AllowedSource,
        session: CaptureSession?,
        state: CaptureState,
        postedAt: Instant,
        receivedAt: Instant,
    ): Boolean = state == CaptureState.ACTIVE && source.enabled && source.enabledSince != null &&
        session != null && session.endedAt == null && session.contains(postedAt) &&
        postedAt >= source.enabledSince && postedAt <= receivedAt
}
