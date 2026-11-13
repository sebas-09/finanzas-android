package ec.finanzas.database

import androidx.room.withTransaction
import ec.finanzas.ledger.*
import ec.finanzas.model.*
import ec.finanzas.reconciliation.*
import ec.finanzas.dashboard.Insights
import java.util.UUID
import kotlinx.coroutines.flow.map

data class BookSnapshot(val accounts: List<FinancialAccountEntity>, val transactions: List<TransactionEntity>,
    val postings: List<AccountPostingEntity>, val pending: List<CandidateEntity>, val observations: List<SourceObservationEntity>,
    val cards: List<CardProfileEntity> = emptyList(), val ignored: List<CandidateEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(), val rules: List<MerchantRuleEntity> = emptyList(), val budgets: List<BudgetEntity> = emptyList(),
    val diagnostics: List<DiagnosticCounterEntity> = emptyList()) {
    fun totals(): BookTotals = LedgerRules.totals(accounts.map(::bookAccount), transactions.map { bookOperation(it, postings) })
}
fun accountType(value: String) = when (value) { "BANK" -> InstrumentType.BANK_ACCOUNT; else -> InstrumentType.valueOf(value) }
fun bookAccount(a: FinancialAccountEntity) = BookAccount(a.id, accountType(a.kind), a.openingBalanceMinor)
fun included(t: TransactionEntity) = t.deletedAt == null && t.mergedIntoId == null && t.origin != "SYNTHETIC" &&
    t.status in setOf("MANUAL_CONFIRMED", "AUTO_CONFIRMED") && t.kind != "UNKNOWN" && t.accountId != null
fun bookOperation(t: TransactionEntity, postings: List<AccountPostingEntity>) = BookOperation(t.id, t.amountMinor,
    TransactionKind.valueOf(t.kind), t.accountId.orEmpty(), t.originalTransactionId,
    postings.filter { it.transactionId == t.id }.map { BookEffect(it.accountId, it.deltaMinor) }, included(t))
fun operationDraft(t: TransactionEntity) = OperationDraft(t.id, t.accountId.orEmpty(), t.amountMinor, TransactionKind.valueOf(t.kind),
    t.occurredAt, MoneyDirection.valueOf(t.direction), t.counterAccountId, t.merchantAlias, t.note, t.originalTransactionId,
    expectedRevision = t.revision, estimatedDate = t.estimatedDate, categoryId = t.categoryId, categoryChanged = true)

/** Una transacción SQLite por decisión, efectos, procedencia y auditoría. */
class BookStore(private val db: BootstrapDatabase) {
    private val dao = db.book()
    private val capture = db.capture()
    private val history = BookHistory(db)
    private val versions = BookVersions(db)
    fun observe() = db.invalidationTracker.createFlow("financial_account", "ledger_transaction", "account_posting", "candidate",
        "source_observation", "card_profile", "category", "merchant_rule", "budget", "diagnostic_counter").map {
        db.withTransaction {
            val product = db.product()
            BookSnapshot(dao.accounts(), dao.transactions(), dao.postings(), dao.pending(), dao.observations(), dao.cards(), dao.ignored(),
                product.categories(), product.rules(), product.budgets(), product.diagnostics())
        }
    }

    suspend fun saveAccount(id: String, alias: String, provider: BankProvider, instrument: InstrumentType,
        opening: Long?, active: Boolean = true, billingDay: Int? = null, dueDay: Int? = null, limit: Long? = null) = db.withTransaction {
        require(alias.trim().length in 1..40 && alias.none { it.isISOControl() }) { "Escribe un alias de 1 a 40 caracteres, sin números completos de cuenta." }
        require(billingDay == null || billingDay in 1..31) { "El día de corte debe estar entre 1 y 31." }
        require(dueDay == null || dueDay in 1..31) { "El día de vencimiento debe estar entre 1 y 31." }
        require(limit == null || limit > 0) { "El límite debe ser positivo." }
        require(instrument != InstrumentType.CREDIT_CARD || opening == null || opening >= 0) { "La deuda inicial no puede ser negativa." }
        val old = dao.account(id)
        val hasHistory = dao.transactions().any { it.accountId == id || it.counterAccountId == id }
        val kind = if (instrument == InstrumentType.DEBIT_CARD) "BANK_ACCOUNT" else instrument.name
        require(!hasHistory || (old != null && accountType(old.kind).name == kind && old.provider == provider.name)) { "Una cuenta con movimientos conserva su entidad y tipo. Puedes cambiar alias, saldo inicial o archivarla." }
        dao.account(FinancialAccountEntity(id, alias.trim(), provider.name, kind, opening, active = active,
            createdAt = old?.createdAt ?: now()))
        if (instrument in setOf(InstrumentType.DEBIT_CARD, InstrumentType.CREDIT_CARD)) {
            val card = dao.cards().firstOrNull { it.accountId == id }
            dao.card(CardProfileEntity(card?.id ?: UUID.randomUUID().toString(), id,
                if (instrument == InstrumentType.CREDIT_CARD) "CREDIT" else "DEBIT", provider.name, billingDay, dueDay, limit))
        }
        validateTotals()
        audit(id, "ACCOUNT_SAVED", "alias,provider,kind,openingBalanceMinor,active,card_profile")
    }
    suspend fun archiveAccount(id: String, active: Boolean) = db.withTransaction {
        val account = requireNotNull(dao.account(id)) { "La cuenta ya no está disponible." }
        dao.account(account.copy(active = active)); audit(id, "ACCOUNT_ARCHIVED", "active")
    }

    suspend fun save(d: OperationDraft, separate: Boolean = false): String = db.withTransaction {
        require(d.id.isNotBlank() && d.kind != TransactionKind.UNKNOWN) { "Clasifica la operación." }
        require(d.note?.let { it.length <= 500 && it.none { char -> char.isISOControl() && char != '\n' && char != '\t' } } ?: true) { "La nota admite hasta 500 caracteres." }
        require(d.merchant?.let { it.length <= 80 && it.none { char -> char.isISOControl() } } ?: true) { "El comercio admite hasta 80 caracteres." }
        require(d.occurredAt <= now() + 300_000) { "La fecha de operación no puede estar en el futuro." }
        val old = dao.transaction(d.id)
        val candidate = d.candidateId?.let { requireNotNull(capture.candidate(it)) { "El candidato ya no está disponible." } }
        val source = candidate?.let { requireNotNull(capture.observation(it.id)) { "Espera a que termine la captura." } }
        val categories = db.product().categories()
        val suggested = if (old == null && !d.categoryChanged && candidate?.categoryDecided != true) Insights.rule(d.merchant, db.product().rules()
            .filter { rule -> rule.categoryId == null || categories.any { it.id == rule.categoryId && it.active } }.map(::ruleSpec)) else null
        val category = when {
            d.kind == TransactionKind.REFUND -> d.originalId?.let { dao.transaction(it)?.categoryId }
            d.categoryChanged -> d.categoryId
            old != null -> old.categoryId
            candidate?.categoryDecided == true -> candidate.categoryId
            else -> candidate?.categoryId ?: suggested?.categoryId
        }
        if (candidate != null) {
            require(candidate.origin != "SYNTHETIC") { "Las pruebas sintéticas no se contabilizan." }
            if (candidate.status == "ACCOUNTED") {
                val linked = source?.transactionId?.let { dao.transaction(it) }
                require(linked != null && candidate.amountMinor == d.amount && candidate.occurredAt == d.occurredAt && candidate.accountId == d.accountId &&
                    candidate.direction == d.direction.name && candidate.classification == d.kind.name && candidate.counterAccountId == d.counterAccountId &&
                    candidate.originalTransactionId == d.originalId && candidate.note == d.note?.trim()?.ifBlank { null } &&
                    candidate.merchant == d.merchant?.trim()?.ifBlank { null } && candidate.estimatedDate == d.estimatedDate &&
                    (!d.categoryChanged || candidate.categoryId == d.categoryId)) { "El candidato ya está vinculado. Edita la operación existente." }
                return@withTransaction linked.id
            }
            require(candidate.status in setOf("NEEDS_REVIEW", "MANUAL_CONFIRMED")) { "Reabre el candidato antes de contabilizar." }
        }
        if (old != null && old.revision != d.expectedRevision) {
            require(included(old) && sameDraft(old, d)) { "La operación cambió. Cierra el formulario y ábrelo de nuevo." }
            return@withTransaction old.id
        }
        require(old != null || d.expectedRevision == null) { "La operación se deshizo. Actualiza la lista." }
        require(old == null || (old.deletedAt == null && old.mergedIntoId == null)) { "Deshaz el borrado o la conciliación antes de editar." }
        require(category == null || categories.any { it.id == category && (it.active || it.id == old?.categoryId || d.kind == TransactionKind.REFUND) }) { "Selecciona una categoría activa." }
        val accounts = dao.accounts().associateBy { it.id }
        for (id in listOfNotNull(d.accountId, d.counterAccountId)) {
            val a = requireNotNull(accounts[id]) { "Selecciona cuentas válidas." }
            require(a.active || id == old?.accountId || id == old?.counterAccountId) { "La cuenta está archivada." }
            require(a.currency == "USD")
        }
        if (candidate != null) {
            val a = accounts.getValue(d.accountId)
            val instrument = accountType(a.kind).name
            val mismatch = (candidate.provider != null && a.provider != candidate.provider) ||
                (source?.direction != null && source.direction != d.direction.name) ||
                (candidate.instrument != null && candidate.instrument != instrument && !(candidate.instrument == "DEBIT_CARD" && instrument == "BANK_ACCOUNT"))
            require(!mismatch || d.correctedEvidence) { "La cuenta, el instrumento o el sentido difieren del aviso. Confirma explícitamente la corrección." }
        }
        val all = dao.transactions()
        val original = d.originalId?.let { id -> all.firstOrNull { it.id == id }?.let { bookOperation(it, dao.postings()) } }
        val effects = LedgerRules.effects(d, accounts.mapValues { bookAccount(it.value) }, original)
        val refunds = all.filter { included(it) && it.originalTransactionId == d.id }
        require(refunds.isEmpty() || (old?.accountId == d.accountId && old.kind == d.kind.name)) { "Corrige primero las devoluciones vinculadas antes de cambiar cuenta o tipo." }
        if (source != null && old == null) {
            val matches = strongMatches(source, d, all)
            require(matches.size <= 1 || separate) { "Hay varias operaciones con evidencia compatible. Selecciona el vínculo manualmente." }
            if (matches.size == 1 && !separate) {
                val match = matches.single()
                require((d.note.isNullOrBlank() || d.note?.trim() == match.note) && (d.merchant.isNullOrBlank() || d.merchant?.trim() == match.merchantAlias)) {
                    "La referencia ya existe. Vincula el aviso y edita la operación para cambiar comercio o nota."
                }
                attach(d, match.id, "AUTO_EVIDENCE_LINK")
                return@withTransaction matches.single().id
            }
            val conflictingReference = source.transactionReferenceHash?.let { reference -> dao.observations().any { observation ->
                observation.id != source.id && observation.transactionReferenceHash == reference && observation.provider == source.provider &&
                    all.any { it.id == observation.transactionId && included(it) }
            } } ?: false
            require(!conflictingReference || separate) { "La referencia ya respalda una operación con otros datos. Vincula, corrige o confirma que es distinta." }
            val possible = dao.observations().filter { it.id != source.id && it.transactionId != null && it.sourcePackage == source.sourcePackage &&
                it.keyHash == source.keyHash && kotlin.math.abs(it.receivedAt - source.receivedAt) <= 300_000 }
                .any { observation -> all.any { it.id == observation.transactionId && included(it) } }
            require(!possible || separate) { "Esta clave de notificación ya respalda otra operación cercana. Vincúlala o marca explícitamente que es una operación distinta." }
        }
        val obsIds = (dao.observations().filter { it.transactionId == d.id }.map { it.id } + listOfNotNull(d.candidateId)).distinct()
        val before = history.snapshot(listOf(d.id), obsIds)
        val at = now()
        val record = TransactionEntity(d.id, d.accountId, d.occurredAt, old?.observedAt ?: source?.receivedAt ?: at, d.amount,
            direction = d.direction.name, kind = d.kind.name, categoryId = category, merchantAlias = d.merchant?.trim()?.ifBlank { null },
            status = "MANUAL_CONFIRMED", origin = old?.origin ?: if (source != null) "NOTIFICATION" else "MANUAL",
            originalTransactionId = d.originalId, createdAt = old?.createdAt ?: at, updatedAt = at, counterAccountId = d.counterAccountId,
            note = d.note?.trim()?.ifBlank { null }, revision = versions.next(d.id), estimatedDate = d.estimatedDate)
        dao.transaction(record)
        dao.clearPostings(listOf(d.id))
        effects.forEach { dao.posting(AccountPostingEntity("${d.id}:${it.accountId}", d.id, it.accountId, it.delta)) }
        if (candidate != null) resolve(candidate.id, d.id, d)
        validateTotals()
        history.record(d.id, before, listOf(d.id), obsIds)
        audit(d.id, if (d.correctedEvidence) "OPERATION_SAVED_EVIDENCE_CORRECTED" else "OPERATION_SAVED", "operation,postings,observations")
        d.id
    }

    suspend fun softDelete(id: String, expectedRevision: Long) = db.withTransaction {
        val old = requireNotNull(dao.transaction(id))
        require(old.revision == expectedRevision && included(old)) { "Actualiza la operación antes de borrarla." }
        require(dao.transactions().none { included(it) && it.originalTransactionId == id }) { "Borra o deshaz primero las devoluciones vinculadas." }
        val obsIds = dao.observations().filter { it.transactionId == id }.map { it.id }
        val before = history.snapshot(listOf(id), obsIds)
        dao.transaction(old.copy(deletedAt = now(), updatedAt = now(), revision = versions.next(id)))
        dao.clearPostings(listOf(id))
        validateTotals(); history.record(id, before, listOf(id), obsIds); audit(id, "OPERATION_DELETED", "deletedAt,postings")
    }

    suspend fun merge(primaryId: String, otherId: String, kind: TransactionKind?, windowMinutes: Int = 5,
        primaryRevision: Long? = null, otherRevision: Long? = null) = db.withTransaction {
        require(primaryId != otherId && windowMinutes in 1..1440)
        val a = requireNotNull(dao.transaction(primaryId)); val b = requireNotNull(dao.transaction(otherId))
        require((primaryRevision == null || primaryRevision == a.revision) && (otherRevision == null || otherRevision == b.revision)) { "Una operación cambió. Actualiza la selección antes de conciliar." }
        require(included(a) && included(b) && a.amountMinor == b.amountMinor && a.currency == b.currency) { "Selecciona dos operaciones vigentes del mismo importe y moneda." }
        require(kotlin.math.abs(a.occurredAt - b.occurredAt) <= windowMinutes * 60_000L) { "Las fechas están fuera de la ventana elegida." }
        require(dao.transactions().none { included(it) && it.originalTransactionId in listOf(primaryId, otherId) }) { "Resuelve primero las devoluciones de esas compras." }
        val obsIds = dao.observations().filter { it.transactionId in listOf(primaryId, otherId) }.map { it.id }
        if (kind == null && a.kind !in INTERNAL_KINDS.map { it.name }) requireNoConflictingReferences(obsIds)
        val before = history.snapshot(listOf(primaryId, otherId), obsIds)
        val draft = if (kind == null) {
            require(a.accountId == b.accountId && a.counterAccountId == b.counterAccountId && a.direction == b.direction && a.kind == b.kind && a.originalTransactionId == b.originalTransactionId &&
                (a.merchantAlias.isNullOrBlank() || b.merchantAlias.isNullOrBlank() || a.merchantAlias.equals(b.merchantAlias, true))) { "Las operaciones no describen el mismo movimiento. Usa una conciliación interna cuando corresponda." }
            operationDraft(a)
        } else {
            require(kind in INTERNAL_KINDS) { "Elige un tipo de conciliación interna." }
            require(a.counterAccountId == null && b.counterAccountId == null) { "Selecciona dos operaciones simples, sin otra cuenta ya vinculada." }
            require(a.accountId != b.accountId) { "La conciliación interna requiere cuentas diferentes." }
            operationDraft(a).copy(kind = kind, counterAccountId = b.accountId, originalId = null)
        }
        val effects = LedgerRules.effects(draft, dao.accounts().associate { it.id to bookAccount(it) },
            draft.originalId?.let { original -> dao.transactions().firstOrNull { it.id == original }?.let { bookOperation(it, dao.postings()) } })
        if (kind != null) validateComplement(b.accountId!!, MoneyDirection.valueOf(b.direction), effects)
        dao.transaction(a.copy(kind = draft.kind.name, counterAccountId = draft.counterAccountId, updatedAt = now(), revision = versions.next(a.id)))
        dao.transaction(b.copy(status = "MERGED", deletedAt = now(), mergedIntoId = a.id, updatedAt = now(), revision = versions.next(b.id)))
        dao.clearPostings(listOf(a.id, b.id))
        effects.forEach { dao.posting(AccountPostingEntity("${a.id}:${it.accountId}", a.id, it.accountId, it.delta)) }
        for (id in obsIds) { capture.observation(id)?.let { capture.observation(it.copy(transactionId = a.id, state = "MANUAL_CONFIRMED")) } }
        val debit = if (kind != null && a.direction == "INCOMING") b.id else a.id
        val credit = if (debit == a.id) b.id else a.id
        dao.link(TransferLinkEntity(UUID.randomUUID().toString(), debit, credit, kind?.name ?: "SAME_OPERATION", "MANUAL_CONFIRMED"))
        validateTotals(); history.record(a.id, before, listOf(a.id, b.id), obsIds); audit(a.id, "OPERATIONS_LINKED", "operation,postings,transfer_link,observations")
    }

    suspend fun linkCandidate(candidateId: String, operationId: String, accountId: String, direction: MoneyDirection,
        amount: Long, occurredAt: Long, kind: TransactionKind?, windowMinutes: Int = 5, expectedRevision: Long? = null) = db.withTransaction {
        val candidate = requireNotNull(capture.candidate(candidateId))
        require(candidate.origin != "SYNTHETIC") { "El candidato sintético no se puede contabilizar." }
        val op = requireNotNull(dao.transaction(operationId))
        if (candidate.status == "ACCOUNTED") {
            require(capture.observation(candidateId)?.transactionId == operationId && included(op) &&
                amount == op.amountMinor && windowMinutes in 1..1440 &&
                kotlin.math.abs(occurredAt - op.occurredAt) <= windowMinutes * 60_000L &&
                (kind == null || kind.name == op.kind)) { "El candidato ya fue vinculado con otra decisión." }
            if (op.kind in INTERNAL_KINDS.map { it.name }) validateComplement(accountId, direction,
                dao.postings().filter { it.transactionId == op.id }.map { BookEffect(it.accountId, it.deltaMinor) })
            else require(accountId == op.accountId && direction.name == op.direction) { "La cuenta o el sentido cambiaron." }
            return@withTransaction
        }
        require(candidate.status in setOf("NEEDS_REVIEW", "MANUAL_CONFIRMED")) { "El candidato no está pendiente." }
        require(expectedRevision == null || expectedRevision == op.revision) { "La operación cambió. Actualiza la selección antes de vincular." }
        require(included(op) && amount == op.amountMinor && windowMinutes in 1..1440 && kotlin.math.abs(occurredAt - op.occurredAt) <= windowMinutes * 60_000L) {
            "Comprueba importe y fechas; deben coincidir dentro de la ventana elegida."
        }
        val account = requireNotNull(dao.account(accountId))
        require(candidate.provider == null || candidate.provider == account.provider) { "Selecciona la cuenta de la entidad del aviso." }
        val obsIds = (dao.observations().filter { it.transactionId == operationId }.map { it.id } + candidateId).distinct()
        if (kind == null && op.kind !in INTERNAL_KINDS.map { it.name }) requireNoConflictingReferences(obsIds)
        val before = history.snapshot(listOf(operationId), obsIds)
        if (kind == null) {
            if (op.kind in INTERNAL_KINDS.map { it.name }) validateComplement(accountId, direction, dao.postings()
                .filter { it.transactionId == op.id }.map { BookEffect(it.accountId, it.deltaMinor) })
            else require(accountId == op.accountId && direction.name == op.direction) { "El aviso debe corresponder a la misma cuenta y sentido." }
        }
        else {
            require(kind in INTERNAL_KINDS && op.counterAccountId == null && op.accountId != accountId) { "La conciliación requiere la otra cuenta y una operación simple." }
            val draft = operationDraft(op).copy(kind = kind, counterAccountId = accountId, originalId = null)
            val effects = LedgerRules.effects(draft, dao.accounts().associate { it.id to bookAccount(it) })
            validateComplement(accountId, direction, effects)
            require(dao.transactions().none { included(it) && it.originalTransactionId == operationId }) { "Resuelve primero las devoluciones vinculadas." }
            dao.clearPostings(listOf(operationId))
            effects.forEach { dao.posting(AccountPostingEntity("$operationId:${it.accountId}", operationId, it.accountId, it.delta)) }
            dao.transaction(op.copy(kind = kind.name, counterAccountId = accountId))
        }
        val current = requireNotNull(dao.transaction(operationId))
        dao.transaction(current.copy(updatedAt = now(), revision = versions.next(operationId)))
        resolve(candidateId, operationId)
        validateTotals(); history.record(operationId, before, listOf(operationId), obsIds); audit(operationId, "CANDIDATE_LINKED", "operation,postings,observations")
    }

    suspend fun undo(id: String) = db.withTransaction {
        history.undo(id); validateTotals(); audit(id, "OPERATION_UNDONE", "operation,postings,observations,transfer_link")
    }
    suspend fun ignoreCandidate(id: String) = db.withTransaction {
        val c = requireNotNull(capture.candidate(id))
        require(c.status in setOf("NEEDS_REVIEW", "MANUAL_CONFIRMED")) { "El candidato ya fue resuelto." }
        capture.candidate(c.copy(status = "IGNORED")); capture.resolve(id, now(), "IGNORED")
        capture.observation(id)?.let { capture.observation(it.copy(state = "IGNORED")) }
        audit(id, "CANDIDATE_IGNORED", "status")
    }
    suspend fun saveCandidateDraft(id: String, amount: Long?, accountId: String?, kind: TransactionKind?, direction: MoneyDirection,
        occurredAt: Long?, counter: String?, original: String?, merchant: String?, note: String?, estimated: Boolean, category: String? = null, categoryDecided: Boolean = false) = db.withTransaction {
        val c = requireNotNull(capture.candidate(id))
        require(c.status in setOf("NEEDS_REVIEW", "MANUAL_CONFIRMED") && capture.observation(id)?.transactionId == null) { "El candidato ya fue resuelto." }
        require(amount == null || amount > 0) { "El importe debe ser positivo." }
        require(note == null || note.length <= 500) { "La nota admite hasta 500 caracteres." }
        require(merchant == null || merchant.length <= 80) { "El comercio admite hasta 80 caracteres." }
        capture.candidate(c.copy(amountMinor = amount, accountId = accountId, classification = kind?.name,
            direction = direction.name, occurredAt = occurredAt, counterAccountId = counter, originalTransactionId = original,
            merchant = merchant, note = note, estimatedDate = estimated, categoryId = category, categoryDecided = categoryDecided, status = "NEEDS_REVIEW"))
        capture.observation(id)?.let { capture.observation(it.copy(state = "NEEDS_REVIEW")) }
        dao.reviews(listOf(id)).forEach { dao.review(it.copy(resolvedAt = null, resolution = null)) }
        audit(id, "CANDIDATE_DRAFT_SAVED", "amountMinor,accountId,classification,direction,occurredAt,merchant,note")
    }
    suspend fun reopenCandidate(id: String) = db.withTransaction {
        val c = requireNotNull(capture.candidate(id)); val observation = capture.observation(id)
        require(observation?.transactionId == null) { "Deshaz el vínculo de la operación antes de reabrir el candidato." }
        capture.candidate(c.copy(status = "NEEDS_REVIEW"))
        observation?.let { capture.observation(it.copy(state = "NEEDS_REVIEW")) }
        dao.reviews(listOf(id)).forEach { dao.review(it.copy(resolvedAt = null, resolution = null)) }
        audit(id, "CANDIDATE_REOPENED", "status")
    }
    private suspend fun resolve(candidateId: String, operationId: String, draft: OperationDraft? = null) {
        val c = requireNotNull(capture.candidate(candidateId))
        val obs = requireNotNull(capture.observation(candidateId)) { "Falta la observación de origen." }
        capture.candidate(if (draft == null) c.copy(status = "ACCOUNTED") else c.copy(status = "ACCOUNTED", amountMinor = draft.amount,
            occurredAt = draft.occurredAt, direction = draft.direction.name, accountId = draft.accountId, classification = draft.kind.name,
            merchant = draft.merchant?.trim()?.ifBlank { null }, note = draft.note?.trim()?.ifBlank { null }, counterAccountId = draft.counterAccountId,
            originalTransactionId = draft.originalId, estimatedDate = draft.estimatedDate, categoryId = dao.transaction(operationId)?.categoryId, categoryDecided = true))
        capture.observation(obs.copy(transactionId = operationId, state = "MANUAL_CONFIRMED"))
        capture.resolve(candidateId, now(), "ACCOUNTED")
    }
    private suspend fun attach(draft: OperationDraft, operationId: String, reason: String) {
        val candidateId = requireNotNull(draft.candidateId)
        val ids = (dao.observations().filter { it.transactionId == operationId }.map { it.id } + candidateId).distinct()
        val before = history.snapshot(listOf(operationId), ids)
        val op = requireNotNull(dao.transaction(operationId))
        dao.transaction(op.copy(updatedAt = now(), revision = versions.next(operationId))); resolve(candidateId, operationId, draft)
        history.record(operationId, before, listOf(operationId), ids); audit(operationId, reason, "observations")
    }
    private suspend fun strongMatches(source: SourceObservationEntity, d: OperationDraft, records: List<TransactionEntity>): List<TransactionEntity> {
        val observations = dao.observations()
        fun facts(obs: SourceObservationEntity, op: OperationDraft) = MatchFacts(op.accountId, op.amount, op.direction.name, op.kind.name,
            obs.provider, op.occurredAt, obs.transactionReferenceHash, op.merchant, obs.sourcePackage, obs.keyHash, obs.postedAt, obs.revisionHash)
        return records.filter { included(it) && it.counterAccountId == d.counterAccountId && it.originalTransactionId == d.originalId && observations.any { obs -> obs.transactionId == it.id &&
            ObservationMatching.compare(facts(source, d), facts(obs, operationDraft(it)), 300_000) in setOf(MatchStrength.BUSINESS_REFERENCE, MatchStrength.TECHNICAL) } }
    }
    private suspend fun requireNoConflictingReferences(ids: List<String>) {
        val refs = dao.observations().filter { it.id in ids }.mapNotNull { it.transactionReferenceHash }.distinct()
        require(refs.size <= 1) { "Las referencias bancarias son diferentes; conserva operaciones separadas." }
    }
    private suspend fun validateComplement(id: String, direction: MoneyDirection, effects: List<BookEffect>) {
        val a = requireNotNull(dao.account(id))
        val delta = requireNotNull(effects.singleOrNull { it.accountId == id }) { "El aviso no corresponde a una cuenta de esta operación." }.delta
        val incoming = if (accountType(a.kind) == InstrumentType.CREDIT_CARD) delta < 0 else delta > 0
        require(incoming == (direction == MoneyDirection.INCOMING)) { "Los avisos no describen lados complementarios de la operación." }
    }
    private suspend fun validateTotals() {
        val postings = dao.postings()
        LedgerRules.totals(dao.accounts().map(::bookAccount), dao.transactions().map { bookOperation(it, postings) })
    }
    private fun sameDraft(t: TransactionEntity, d: OperationDraft) = included(t) && t.accountId == d.accountId && t.amountMinor == d.amount &&
        t.kind == d.kind.name && t.direction == d.direction.name && t.counterAccountId == d.counterAccountId && t.occurredAt == d.occurredAt &&
        t.originalTransactionId == d.originalId && t.note == d.note?.trim()?.ifBlank { null } && t.merchantAlias == d.merchant?.trim()?.ifBlank { null } && t.estimatedDate == d.estimatedDate &&
        (!d.categoryChanged || t.categoryId == d.categoryId)
    private suspend fun audit(id: String, kind: String, fields: String) { capture.audit(ChangeAuditEntity(UUID.randomUUID().toString(), id, kind, now(), fields)) }
    private fun now() = System.currentTimeMillis()
    companion object { val INTERNAL_KINDS = setOf(TransactionKind.OWN_TRANSFER, TransactionKind.WALLET_TOP_UP, TransactionKind.CREDIT_CARD_PAYMENT) }
}
