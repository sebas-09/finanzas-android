package ec.finanzas.model

data class CaptureSource(
    val packageName: String,
    val displayName: String,
    val provider: BankProvider?,
    val channel: String,
    val enabled: Boolean,
    val enabledSince: Long?,
    val signingDigest: String,
    val available: Boolean,
    val lastSeenAt: Long?,
)

data class CaptureSnapshot(
    val consentAccepted: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val captureStartedAt: Long? = null,
    val requestedActive: Boolean = false,
    val permissionGranted: Boolean = false,
    val permissionEverGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val sessionId: Long? = null,
    val sessionStartedAt: Long? = null,
    val mayHaveGaps: Boolean = false,
    val sources: List<CaptureSource> = emptyList(),
    val observedCount: Long = 0,
    val syntheticCount: Long = 0,
    val queuedCount: Long = 0,
    val discardedCount: Long = 0,
) {
    val state: CaptureState get() = when {
        !consentAccepted || !onboardingCompleted -> CaptureState.NOT_ACTIVATED
        !permissionGranted && permissionEverGranted -> CaptureState.PERMISSION_REVOKED
        !requestedActive -> CaptureState.PAUSED
        !permissionGranted -> CaptureState.PERMISSION_REVOKED
        !listenerConnected || sessionStartedAt == null -> CaptureState.DISCONNECTED
        else -> CaptureState.ACTIVE
    }
}

data class DiscoveredSource(val packageName: String, val displayName: String, val signingDigest: String)

data class CaptureUiState(
    val loading: Boolean = true,
    val storageError: Boolean = false,
    val actionError: Boolean = false,
    val snapshot: CaptureSnapshot = CaptureSnapshot(),
    val discovered: List<DiscoveredSource> = emptyList(),
)

/** Valores técnicos ya minimizados; nunca títulos, cuerpos ni destinatarios. */
data class CaptureEnvelope(
    val fingerprint: String,
    val sourcePackage: String,
    val keyHash: String,
    val revisionHash: String,
    val sessionId: Long?,
    val postedAt: Long,
    val receivedAt: Long,
    val origin: DataOrigin,
    val parsed: ParseResult.Candidate? = null,
)
