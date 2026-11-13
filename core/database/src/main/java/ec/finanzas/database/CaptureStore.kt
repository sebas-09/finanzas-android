package ec.finanzas.database

import androidx.room.withTransaction
import ec.finanzas.model.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Autoridad durable. Ni un callback tardío ni un estado UI atrasado evitan esta validación. */
class CaptureStore(private val db: BootstrapDatabase) {
    private val dao = db.capture()

    suspend fun initializeAfterProcessStart(at: Long) = db.withTransaction {
        dao.ensureSettings(CaptureSettingsEntity())
        val settings = dao.settings()
        close(settings, at, "PROCESS_RESTART")
        dao.settings(settings.copy(listenerConnected = false, currentSessionId = null,
            mayHaveGaps = settings.mayHaveGaps || settings.requestedActive))
    }

    fun observe(): Flow<CaptureSnapshot> = combine(dao.configurationFlow(), dao.sourcesFlow(),
        dao.countsFlow(), dao.queueCountFlow(), dao.discardedFlow()) { configuration, sources, counts, queued, discarded ->
        val s = configuration?.settings ?: CaptureSettingsEntity()
        CaptureSnapshot(s.consentAt != null, s.onboardingCompleted, s.captureStartedAt, s.requestedActive,
            s.permissionGranted, s.permissionEverGranted, s.listenerConnected, s.currentSessionId,
            configuration?.sessionStartedAt, s.mayHaveGaps,
            sources.map { CaptureSource(it.packageName, it.displayName, it.provider?.let(BankProvider::valueOf),
                it.channel, it.enabled, it.enabledSince, it.signingDigest, it.available, it.lastSeenAt) },
            counts.observed, counts.synthetic, queued, discarded)
    }

    suspend fun consent(at: Long) = db.withTransaction {
        val s = dao.settings()
        dao.settings(s.copy(consentAt = s.consentAt ?: at, consentVersion = 1))
        audit("CONSENT_ACCEPTED", at, "consentAt,consentVersion")
    }

    suspend fun permission(granted: Boolean, at: Long) = db.withTransaction {
        val s = dao.settings()
        if (!granted) close(s, at, "PERMISSION_REVOKED")
        dao.settings(s.copy(permissionGranted = granted, permissionEverGranted = s.permissionEverGranted || granted,
            requestedActive = s.requestedActive && granted,
            currentSessionId = if (granted) s.currentSessionId else null,
            mayHaveGaps = s.mayHaveGaps || (!granted && s.captureStartedAt != null)))
    }

    suspend fun connection(connected: Boolean, at: Long) = db.withTransaction {
        val s = dao.settings()
        if (!connected) close(s, at, "DISCONNECTED")
        val session = when {
            !connected -> null
            s.currentSessionId != null -> s.currentSessionId
            s.requestedActive && s.permissionGranted && s.consentAt != null && s.onboardingCompleted ->
                dao.session(CaptureSessionEntity(startedAt = at))
            else -> null
        }
        dao.settings(s.copy(listenerConnected = connected, currentSessionId = session,
            mayHaveGaps = s.mayHaveGaps || (!connected && s.captureStartedAt != null)))
    }

    suspend fun activate(at: Long) = db.withTransaction {
        val s = dao.settings()
        check(s.consentAt != null && s.permissionGranted)
        check(dao.sources().any { it.enabled && it.available })
        close(s, at, "NEW_ACTIVATION")
        val session = if (s.listenerConnected) dao.session(CaptureSessionEntity(startedAt = at)) else null
        dao.settings(s.copy(onboardingCompleted = true, captureStartedAt = s.captureStartedAt ?: at,
            requestedActive = true, pausedAt = null, currentSessionId = session))
        audit("CAPTURE_ACTIVATED", at, "captureStartedAt,currentSessionId,requestedActive")
    }

    suspend fun pause(at: Long) = db.withTransaction {
        val s = dao.settings()
        close(s, at, "PAUSED")
        dao.settings(s.copy(requestedActive = false, pausedAt = at, currentSessionId = null,
            mayHaveGaps = s.mayHaveGaps || s.captureStartedAt != null))
        audit("CAPTURE_PAUSED", at, "pausedAt,requestedActive")
    }

    suspend fun source(source: AllowedSourceEntity, at: Long) = db.withTransaction {
        check(dao.settings().consentAt != null)
        check(source.channel in setOf("BANK_APP", "OUTLOOK"))
        check(source.channel == "OUTLOOK" || source.provider != null)
        val old = dao.source(source.packageName)
        val preserveWindow = old?.enabled == true && source.enabled && old.provider == source.provider &&
            old.channel == source.channel && old.signingDigest == source.signingDigest
        dao.source(source.copy(enabledSince = if (!source.enabled) null else if (preserveWindow) old?.enabledSince else at,
            lastSeenAt = old?.lastSeenAt))
        if (dao.sources().none { it.enabled && it.available }) {
            val s = dao.settings()
            close(s, at, "NO_ENABLED_SOURCE")
            dao.settings(s.copy(requestedActive = false, currentSessionId = null))
        }
        audit("SOURCE_CHANGED", at, "enabled,enabledSince,provider,channel", source.packageName)
    }

    suspend fun missingSources(valid: Map<String, String>, at: Long) = db.withTransaction {
        for (source in dao.sources()) {
            val present = valid[source.packageName] == source.signingDigest
            if (!present) dao.source(source.copy(available = false, enabled = false, enabledSince = null))
            else if (!source.available) dao.source(source.copy(available = true))
        }
        if (dao.sources().none { it.enabled && it.available }) {
            val s = dao.settings()
            close(s, at, "SOURCE_NOT_DETECTED")
            dao.settings(s.copy(requestedActive = false, currentSessionId = null,
                mayHaveGaps = s.mayHaveGaps || s.captureStartedAt != null))
        }
    }

    suspend fun discard(reason: String) { dao.count(reason) }
    suspend fun notificationRemoved(keyHash: String) { dao.closeCursor(keyHash) }

    /** F2 conserva revisiones distintas para conciliación; entradas F1 mantienen su cursor técnico. */
    suspend fun enqueue(event: CaptureEnvelope): Boolean = db.withTransaction {
        require(event.origin in setOf(DataOrigin.NOTIFICATION, DataOrigin.SYNTHETIC))
        val source = if (event.origin == DataOrigin.NOTIFICATION) dao.source(event.sourcePackage) else null
        if (event.origin == DataOrigin.NOTIFICATION) {
            val s = dao.settings()
            val session = s.currentSessionId?.let { dao.session(it) }
            if (!s.onboardingCompleted || s.consentAt == null || !s.requestedActive || !s.permissionGranted ||
                !s.listenerConnected || session == null || session.endedAt != null ||
                event.sessionId != session.id || source == null || !source.enabled || !source.available ||
                source.enabledSince == null || event.postedAt < source.enabledSince ||
                event.postedAt < session.startedAt || event.postedAt > event.receivedAt) {
                dao.count("OUTSIDE_AUTHORIZED_WINDOW")
                return@withTransaction false
            }
        }
        val fingerprint = if (event.origin == DataOrigin.NOTIFICATION && event.parsed != null) {
            dao.exactPublication(event.sourcePackage, event.keyHash, event.postedAt, event.revisionHash) ?: event.fingerprint
        } else if (event.origin == DataOrigin.NOTIFICATION && event.parsed == null) {
            val cursor = dao.cursor(event.keyHash)
            if (cursor?.active == true && cursor.sessionId == event.sessionId) cursor.eventFingerprint
            else event.fingerprint.also { dao.cursor(NotificationCursorEntity(event.keyHash, it, requireNotNull(event.sessionId))) }
        } else event.fingerprint
        val prior = dao.observation(fingerprint)
        val queued = dao.queued(fingerprint)
        if (prior?.revisionHash == event.revisionHash || queued?.revisionHash == event.revisionHash) {
            dao.count("DUPLICATE_CALLBACK")
            return@withTransaction false
        }
        event.parsed?.let { parsed ->
            val e = parsed.event
            if (dao.candidate(fingerprint) == null) dao.candidate(CandidateEntity(
                id = fingerprint, amountMinor = e.amountMinor, occurredAt = e.occurredAt?.toEpochMilli(),
                direction = e.direction?.name, provider = e.bank?.name, instrument = e.instrument?.name,
                merchant = e.merchantNormalized, candidateKind = e.kind.name, parserVersion = parsed.parserVersion,
                confidence = e.confidence, evidenceCodes = (e.evidenceCodes + EvidenceCode.POSSIBLE_DUPLICATE).joinToString(",") { it.name },
                receivedAt = event.receivedAt, origin = event.origin.name, status = "PENDING_CAPTURE"))
        }
        dao.queue(CaptureQueueEntity(eventFingerprint = fingerprint, sourcePackage = event.sourcePackage,
            keyHash = event.keyHash, revisionHash = event.revisionHash, sessionId = event.sessionId,
            postedAt = event.postedAt, receivedAt = queued?.receivedAt ?: event.receivedAt,
            lastReceivedAt = event.receivedAt, provider = source?.provider, origin = event.origin.name))
        if (source != null) dao.lastSeen(source.packageName, event.receivedAt)
        true
    }

    /** Reintentos seguros incluso si se revoca el permiso después de persistir la entrada autorizada. */
    suspend fun drain(limit: Int = 100): Boolean = db.withTransaction {
        for (queued in dao.pending(limit)) {
            val old = dao.observation(queued.eventFingerprint)
            val observation = old?.copy(revisionHash = queued.revisionHash, lastReceivedAt = queued.lastReceivedAt)
                ?: SourceObservationEntity(id = queued.eventFingerprint, sourcePackage = queued.sourcePackage,
                    eventFingerprint = queued.eventFingerprint, keyHash = queued.keyHash,
                    revisionHash = queued.revisionHash, postedAt = queued.postedAt, receivedAt = queued.receivedAt,
                    lastReceivedAt = queued.lastReceivedAt, provider = queued.provider, origin = queued.origin)
            val candidate = dao.candidate(observation.id)
                ?: CandidateEntity(id = observation.id, provider = observation.provider, receivedAt = observation.receivedAt, origin = observation.origin)
                    .also { dao.candidate(it) }
            val ready = if (candidate.status == "PENDING_CAPTURE") candidate.copy(status = "NEEDS_REVIEW") else candidate
            if (ready != candidate) dao.candidate(ready)
            dao.observation(if (old != null) observation.copy(state = ready.status) else observation.copy(parserVersion = candidate.parserVersion,
                state = ready.status, amountMinor = candidate.amountMinor, occurredAt = candidate.occurredAt,
                direction = candidate.direction, merchantNormalized = candidate.merchant,
                confidence = candidate.confidence, evidenceCodes = candidate.evidenceCodes))
            dao.review(ReviewQueueEntity(id = observation.id, observationId = observation.id,
                reasonCode = "CANDIDATE_REVIEW", createdAt = observation.receivedAt))
            dao.removeQueued(queued.eventFingerprint)
        }
        dao.queueCount() > 0
    }

    private suspend fun close(settings: CaptureSettingsEntity, at: Long, reason: String) {
        settings.currentSessionId?.let { dao.closeSession(it, at, reason) }
    }
    private suspend fun audit(kind: String, at: Long, fields: String, entity: String = "capture") {
        dao.audit(ChangeAuditEntity(UUID.randomUUID().toString(), entity, kind, at, fields))
    }
}
