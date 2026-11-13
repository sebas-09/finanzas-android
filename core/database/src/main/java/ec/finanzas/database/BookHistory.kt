package ec.finanzas.database

import org.json.JSONArray
import org.json.JSONObject

/** Instantáneas solo de filas afectadas; nunca contiene textos de notificación. Todo reside en SQLCipher. */
internal class BookHistory(private val db: BootstrapDatabase) {
    private val dao = db.book()
    private val capture = db.capture()
    private val versions = BookVersions(db)
    suspend fun snapshot(txIds: List<String>, obsIds: List<String>): String {
        val ids = txIds.distinct().sorted(); val observations = obsIds.distinct().sorted()
        val transactions = dao.transactions().filter { it.id in ids }
        val postings = dao.postings().filter { it.transactionId in ids }
        return obj("txIds" to JSONArray(ids), "obsIds" to JSONArray(observations),
            "transactions" to array(transactions.map(::transactionJson)),
            "postings" to array(postings.map { obj("id" to it.id, "transactionId" to it.transactionId, "accountId" to it.accountId, "delta" to it.deltaMinor) }),
            "links" to array(dao.links(ids).map { obj("id" to it.id, "debit" to it.debitTransactionId, "credit" to it.creditTransactionId, "kind" to it.linkType, "state" to it.confirmationState) }),
            "observations" to array(observations.mapNotNull { id -> capture.observation(id)?.let { obj("id" to id, "transactionId" to it.transactionId, "state" to it.state) } }),
            "candidates" to array(observations.mapNotNull { id -> capture.candidate(id)?.let { obj("id" to id, "status" to it.status,
                "amount" to it.amountMinor, "date" to it.occurredAt, "direction" to it.direction, "account" to it.accountId,
                "kind" to it.classification, "merchant" to it.merchant, "counter" to it.counterAccountId,
                "original" to it.originalTransactionId, "note" to it.note, "estimated" to it.estimatedDate,
                "categoryId" to it.categoryId, "categoryDecided" to it.categoryDecided) } }),
            "reviews" to array(dao.reviews(observations).map { obj("id" to it.id, "observationId" to it.observationId,
                "reason" to it.reasonCode, "createdAt" to it.createdAt, "resolvedAt" to it.resolvedAt, "resolution" to it.resolution) })).toString()
    }
    suspend fun record(owner: String, before: String, txIds: List<String>, obsIds: List<String>) {
        dao.history(LedgerHistoryEntity(ownerId = owner, beforeJson = before,
            afterJson = snapshot(txIds, obsIds), createdAt = System.currentTimeMillis()))
    }
    suspend fun undo(owner: String) {
        val entry = requireNotNull(dao.latestHistory(owner)) { "No hay una acción pendiente de deshacer para esta operación." }
        val before = JSONObject(entry.beforeJson)
        val ids = before.getJSONArray("txIds").strings()
        val obsIds = before.getJSONArray("obsIds").strings()
        val restored = before.getJSONArray("transactions").objects().map(::transactionFromJson)
        val deletingIds = ids.filter { id -> restored.none { it.id == id } }
        require(snapshot(ids, obsIds) == compatible(entry.afterJson)) { "Hubo cambios posteriores. Deshaz primero la acción más reciente de las operaciones vinculadas." }
        require(dao.transactions().none { it.id !in ids && (it.originalTransactionId in deletingIds || it.mergedIntoId in deletingIds) }) {
            "Deshaz primero los movimientos posteriores vinculados a esta operación."
        }
        dao.clearLinks(ids)
        dao.clearPostings(ids)
        for (id in obsIds) capture.observation(id)?.let { capture.observation(it.copy(transactionId = null)) }
        for (record in restored) dao.transaction(record.copy(revision = versions.next(record.id), updatedAt = System.currentTimeMillis()))
        for (id in deletingIds) { versions.next(id); dao.removeTransaction(id) }
        for (p in before.getJSONArray("postings").objects()) dao.posting(AccountPostingEntity(p.getString("id"), p.getString("transactionId"), p.getString("accountId"), p.getLong("delta")))
        for (l in before.getJSONArray("links").objects()) dao.link(TransferLinkEntity(l.getString("id"), l.getString("debit"), l.getString("credit"), l.getString("kind"), l.getString("state")))
        for (o in before.getJSONArray("observations").objects()) {
            val current = requireNotNull(capture.observation(o.getString("id")))
            capture.observation(current.copy(transactionId = o.s("transactionId"), state = o.getString("state")))
        }
        for (c in before.getJSONArray("candidates").objects()) {
            val current = requireNotNull(capture.candidate(c.getString("id")))
            capture.candidate(current.copy(status = c.getString("status"), amountMinor = c.l("amount"), occurredAt = c.l("date"),
                direction = c.s("direction"), accountId = c.s("account"), classification = c.s("kind"), merchant = c.s("merchant"),
                counterAccountId = c.s("counter"), originalTransactionId = c.s("original"), note = c.s("note"), estimatedDate = c.getBoolean("estimated"),
                categoryId = c.s("categoryId"), categoryDecided = c.optBoolean("categoryDecided", false)))
        }
        for (r in before.getJSONArray("reviews").objects()) dao.review(ReviewQueueEntity(r.getString("id"), r.getString("observationId"), r.getString("reason"), r.getLong("createdAt"), r.l("resolvedAt"), r.s("resolution")))
        dao.undoHistory(entry.id, System.currentTimeMillis())
        // Actualiza únicamente la expectativa de versión, nunca los datos históricos ni decisiones posteriores.
        for (id in ids) {
            val prior = dao.latestHistory(id) ?: continue
            val expected = JSONObject(prior.afterJson)
            val current = snapshot(expected.getJSONArray("txIds").strings(), expected.getJSONArray("obsIds").strings())
            if (logical(current) == logical(prior.afterJson)) dao.updateHistoryExpectation(prior.id, current)
        }
    }
    private fun compatible(json: String): String = JSONObject(json).apply {
        getJSONArray("candidates").objects().forEach {
            if (!it.has("categoryId")) it.put("categoryId", JSONObject.NULL)
            if (!it.has("categoryDecided")) it.put("categoryDecided", false)
        }
    }.toString()
    private fun logical(json: String): String = JSONObject(compatible(json)).apply {
        getJSONArray("transactions").objects().forEach { it.remove("revision"); it.remove("updatedAt") }
    }.toString()
    private fun transactionJson(t: TransactionEntity) = obj("id" to t.id, "accountId" to t.accountId,
        "occurredAt" to t.occurredAt, "observedAt" to t.observedAt, "amountMinor" to t.amountMinor, "currency" to t.currency,
        "direction" to t.direction, "kind" to t.kind, "categoryId" to t.categoryId, "merchantAlias" to t.merchantAlias,
        "status" to t.status, "origin" to t.origin, "originalTransactionId" to t.originalTransactionId,
        "createdAt" to t.createdAt, "updatedAt" to t.updatedAt, "deletedAt" to t.deletedAt,
        "counterAccountId" to t.counterAccountId, "note" to t.note, "revision" to t.revision,
        "estimatedDate" to t.estimatedDate, "mergedIntoId" to t.mergedIntoId)
    private fun transactionFromJson(o: JSONObject) = TransactionEntity(o.getString("id"), o.s("accountId"), o.getLong("occurredAt"), o.getLong("observedAt"),
        o.getLong("amountMinor"), o.getString("currency"), o.getString("direction"), o.getString("kind"), o.s("categoryId"), o.s("merchantAlias"),
        o.getString("status"), o.getString("origin"), o.s("originalTransactionId"), o.getLong("createdAt"), o.getLong("updatedAt"), o.l("deletedAt"),
        o.s("counterAccountId"), o.s("note"), o.getLong("revision"), o.getBoolean("estimatedDate"), o.s("mergedIntoId"))
    private fun obj(vararg entries: Pair<String, Any?>) = JSONObject().apply { entries.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    private fun array(entries: List<JSONObject>) = JSONArray().apply { entries.forEach { put(it) } }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.s(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.l(key: String): Long? = if (isNull(key)) null else getLong(key)
}
