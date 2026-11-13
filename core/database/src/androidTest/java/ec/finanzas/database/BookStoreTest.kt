package ec.finanzas.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ec.finanzas.ledger.*
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BookStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "f3_book_test"
    private var db: BootstrapDatabase? = null
    private suspend fun open(): BookStore {
        db = EncryptedDatabaseFactory.open(context, name)
        return BookStore(db!!).also {
            it.saveAccount("bank", "Banco", BankProvider.PICHINCHA, InstrumentType.BANK_ACCOUNT, 10000)
            it.saveAccount("wallet", "Billetera", BankProvider.DEUNA, InstrumentType.WALLET, 0)
            it.saveAccount("credit", "Crédito", BankProvider.PEIGO, InstrumentType.CREDIT_CARD, 0)
        }
    }
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval() }
    private fun d(id: String, amount: Long, kind: TransactionKind = TransactionKind.EXPENSE, account: String = "bank",
        counter: String? = null, date: Long = 1000) = OperationDraft(id, account, amount, kind, date,
        LedgerRules.direction(kind, if (account == "credit") InstrumentType.CREDIT_CARD else if (account == "wallet") InstrumentType.WALLET else InstrumentType.BANK_ACCOUNT), counter)
    private suspend fun totals() = BookStore(db!!).observe().first().totals()
    private suspend fun rejected(block: suspend () -> Unit) { try { block(); fail("La acción debía rechazarse") } catch (_: IllegalArgumentException) { } }
    private suspend fun source(id: String, reference: String? = null, sourcePackage: String = "fixture.bank", date: Long = 1000,
        origin: String = "NOTIFICATION", key: String = id, posted: Long = date, revision: String = id): OperationDraft {
        db!!.capture().observation(SourceObservationEntity(id, sourcePackage = sourcePackage, eventFingerprint = id,
            keyHash = key, revisionHash = revision, parserVersion = "fixture-f3", postedAt = posted, receivedAt = date + 1,
            lastReceivedAt = date + 1, provider = "PICHINCHA", origin = origin, state = "NEEDS_REVIEW", amountMinor = 100,
            occurredAt = date, direction = "OUTGOING", merchantNormalized = "TIENDA", transactionReferenceHash = reference))
        db!!.capture().candidate(CandidateEntity(id, amountMinor = 100, occurredAt = date, direction = "OUTGOING", provider = "PICHINCHA",
            instrument = "BANK_ACCOUNT", merchant = "TIENDA", receivedAt = date + 1, origin = origin))
        return d("candidate:$id", 100, date = date).copy(candidateId = id, merchant = "TIENDA")
    }
    @Test fun pendingCreditNoticeCorrectsPaymentAndRepeatedLinkIsIdempotent() = runBlocking {
        val book = open()
        book.save(d("purchase", 2500, TransactionKind.CREDIT_CARD_CHARGE, "credit"))
        book.save(d("payment", 2500))
        source("credit-notice")
        val capture = db!!.capture()
        capture.candidate(capture.candidate("credit-notice")!!.copy(amountMinor = 2500, provider = "PEIGO",
            instrument = "CREDIT_CARD", direction = "INCOMING"))
        repeat(3) {
            book.linkCandidate("credit-notice", "payment", "credit", MoneyDirection.INCOMING, 2500, 1000,
                TransactionKind.CREDIT_CARD_PAYMENT, expectedRevision = 0)
        }
        assertEquals(2500L, totals().expense)
        assertEquals(7500L, totals().balances["bank"])
        assertEquals(0L, totals().balances["credit"])
        assertEquals(1L, db!!.book().transaction("payment")!!.revision)
        book.undo("payment")
        assertEquals(5000L, totals().expense)
        assertEquals(2500L, totals().balances["credit"])
        assertNull(capture.observation("credit-notice")!!.transactionId)
        assertEquals("NEEDS_REVIEW", capture.candidate("credit-notice")!!.status)
    }
    @Test fun creditTopupAndPaymentCountConsumptionOnlyOnceCA06CA11() = runBlocking {
        val book = open()
        book.save(d("buy", 1000, TransactionKind.CREDIT_CARD_CHARGE, "credit"))
        assertEquals(1000L, totals().balances["credit"])
        book.save(d("top", 500, TransactionKind.WALLET_TOP_UP, counter = "wallet"))
        book.save(d("pay", 1000, TransactionKind.CREDIT_CARD_PAYMENT, counter = "credit"))
        val t = totals(); assertEquals(1000L, t.expense); assertEquals(0L, t.income)
        assertEquals(8500L, t.balances["bank"]); assertEquals(500L, t.balances["wallet"]); assertEquals(0L, t.balances["credit"])
        book.save(d("refund", 500, TransactionKind.REFUND, "credit").copy(originalId = "buy"))
        assertEquals(-500L, totals().balances["credit"]); assertEquals(500L, totals().expense)
    }
    @Test fun idempotentSaveEditDeleteAndUndoPreserveIndependentDecisions() = runBlocking {
        val book = open(); val first = d("a", 100)
        repeat(5) { book.save(first) }
        assertEquals(1, db!!.book().transactions().size); assertEquals(1, db!!.book().postings().size)
        book.save(d("b", 300, TransactionKind.INCOME))
        book.save(first.copy(amount = 200, expectedRevision = 0, note = "Corrección manual"))
        assertEquals(200L, totals().expense)
        book.undo("a")
        assertEquals(100L, totals().expense); assertEquals(10200L, totals().balances["bank"])
        assertEquals(300L, db!!.book().transaction("b")!!.amountMinor)
        book.softDelete("b", 0); assertEquals(0L, totals().income)
        book.undo("b"); assertEquals(300L, totals().income)
        book.undo("a"); assertNull(db!!.book().transaction("a")); assertEquals(10300L, totals().balances["bank"])
    }
    @Test fun fiveCallbacksOneObservationAndDistinctRevisionsRequireExplicitDecision() = runBlocking {
        val book = open(); val capture = CaptureStore(db!!)
        capture.initializeAfterProcessStart(1000); capture.consent(1000); capture.permission(true, 1100); capture.connection(true, 1100)
        capture.source(AllowedSourceEntity("fixture.bank", "Emisor sintético", "PICHINCHA", enabled = true, signingDigest = "fixture-cert"), 1500)
        capture.activate(2000)
        val session = db!!.capture().settings().currentSessionId
        val event = NormalizedBankEvent("fixture.bank", "key", Instant.ofEpochMilli(2101), Instant.ofEpochMilli(1000), 100,
            direction = MoneyDirection.OUTGOING, bank = BankProvider.PICHINCHA, instrument = InstrumentType.BANK_ACCOUNT,
            merchantNormalized = "TIENDA", transactionReferenceHash = null, kind = CandidateKind.OUTGOING_TRANSFER,
            confidence = .75, evidenceCodes = setOf(EvidenceCode.MISSING_ACCOUNT))
        repeat(5) { i -> capture.enqueue(CaptureEnvelope("event$i", "fixture.bank", "key", "same-revision", session, 2100, 2101,
            DataOrigin.NOTIFICATION, ParseResult.Candidate(event, "fixture"))); capture.drain() }
        assertEquals(1L, db!!.capture().observationCount("NOTIFICATION"))
        val first = d("candidate:event0", 100).copy(candidateId = "event0", merchant = "TIENDA")
        repeat(5) { book.save(first) }
        assertEquals(100L, totals().expense)
        capture.enqueue(CaptureEnvelope("revision2", "fixture.bank", "key", "different-data", session, 2100, 2102,
            DataOrigin.NOTIFICATION, ParseResult.Candidate(event, "fixture"))); capture.drain()
        val another = d("candidate:revision2", 100).copy(candidateId = "revision2", merchant = "TIENDA")
        rejected { book.save(another) }; assertEquals(100L, totals().expense)
        book.save(another, separate = true); assertEquals(200L, totals().expense)
        val distinct = source("distinct-key", date = 1100)
        book.save(distinct); assertEquals(300L, totals().expense)
    }
    @Test fun strongReferenceLinksChannelsAndAmbiguousMatchesNeverChooseFirst() = runBlocking {
        val book = open()
        val a = source("a", "ref"); val root = book.save(a)
        val b = source("b", "ref", "fixture.outlook", 1100)
        repeat(5) { assertEquals(root, book.save(b)) }
        assertEquals(1, db!!.book().transactions().size); assertEquals(2, db!!.book().observations().count { it.transactionId == root })
        assertEquals(100L, totals().expense)
        book.undo(root)
        assertNull(db!!.capture().observation("b")!!.transactionId)
        assertEquals("NEEDS_REVIEW", db!!.capture().candidate("b")!!.status)
        book.save(b, separate = true)
        val c = source("c", "ref", "fixture.third", 1200)
        rejected { book.save(c) }; assertEquals(200L, totals().expense)
        assertEquals(2, db!!.book().transactions().count(::included))
    }
    @Test fun lateTopupReclassificationAndSeparationDoNotDuplicateCash() = runBlocking {
        val book = open()
        book.save(d("out", 500).copy(note = "Salida provisional"))
        book.save(d("in", 500, TransactionKind.INCOME, "wallet", date = 1100).copy(note = "Entrada provisional"))
        assertEquals(500L, totals().expense); assertEquals(500L, totals().income)
        book.merge("out", "in", TransactionKind.WALLET_TOP_UP)
        assertEquals(0L, totals().expense); assertEquals(0L, totals().income)
        assertEquals(9500L, totals().balances["bank"]); assertEquals(500L, totals().balances["wallet"])
        assertEquals(1, db!!.book().transactions().count(::included)); assertEquals(2, db!!.book().postings().size)
        val linked = db!!.book().transaction("out")!!
        book.save(operationDraft(linked).copy(note = "Nueva decisión manual"))
        book.undo("out"); book.undo("out")
        assertEquals(2, db!!.book().transactions().count(::included))
        assertEquals("Salida provisional", db!!.book().transaction("out")!!.note)
        assertEquals("Entrada provisional", db!!.book().transaction("in")!!.note)
        assertEquals(500L, totals().expense); assertEquals(500L, totals().income)
    }
    @Test fun refundsRejectOverpaymentAndEditsRollbackAndCanBeUndoneAroundMerge() = runBlocking {
        val book = open(); book.save(d("buy", 1000))
        book.save(d("refund", 400, TransactionKind.REFUND, date = 1100).copy(originalId = "buy"))
        assertEquals(600L, totals().expense); assertEquals(0L, totals().income)
        rejected { book.save(d("too-much", 601, TransactionKind.REFUND).copy(originalId = "buy")) }
        rejected { book.save(d("buy", 300).copy(expectedRevision = 0)) }
        rejected { book.softDelete("buy", 0) }
        assertNull(db!!.book().transaction("too-much")); assertEquals(1000L, db!!.book().transaction("buy")!!.amountMinor)
        book.softDelete("refund", 0)
        book.save(d("duplicate", 1000, date = 1200))
        book.merge("buy", "duplicate", null)
        book.undo("buy")
        assertNotNull(db!!.book().transaction("refund")!!.deletedAt)
        book.undo("refund")
        assertNull(db!!.book().transaction("refund")!!.deletedAt)
        assertEquals(1600L, totals().expense)
    }
    @Test fun unknownOpeningArchiveDebitProfileAndOptimisticWritesAreSafe() = runBlocking {
        val book = open(); book.saveAccount("unknown", "Sin saldo", BankProvider.GUAYAQUIL, InstrumentType.DEBIT_CARD, null)
        book.save(d("e", 100, account = "unknown"))
        assertNull(totals().balances["unknown"]); assertEquals(-100L, totals().changes["unknown"])
        assertEquals("BANK_ACCOUNT", db!!.book().account("unknown")!!.kind)
        assertEquals("DEBIT", db!!.book().cards().single { it.accountId == "unknown" }.type)
        book.archiveAccount("unknown", false)
        rejected { book.save(d("new", 100, account = "unknown")) }
        rejected { book.saveAccount("unknown", "Cambio", BankProvider.GUAYAQUIL, InstrumentType.CREDIT_CARD, 0) }
        val e = db!!.book().transaction("e")!!
        book.save(operationDraft(e).copy(amount = 200))
        rejected { book.save(operationDraft(e).copy(amount = 300)) }
        assertEquals(200L, totals().expense)
        val stale = operationDraft(db!!.book().transaction("e")!!)
        book.undo("e")
        val restored = db!!.book().transaction("e")!!
        assertTrue(restored.revision > stale.expectedRevision!!)
        book.save(operationDraft(restored).copy(amount = 400))
        rejected { book.save(stale.copy(amount = 300)) }
        assertEquals(400L, totals().expense)
        assertFalse(db!!.book().account("unknown")!!.active)
    }
    @Test fun evidenceRemainsImmutableSyntheticExcludedAndUndoKeepsFreshMetadata() = runBlocking {
        val book = open(); val a = source("a")
        book.save(a.copy(amount = 200))
        assertEquals(100L, db!!.capture().observation("a")!!.amountMinor)
        assertEquals(200L, db!!.capture().candidate("a")!!.amountMinor)
        val fresh = db!!.capture().observation("a")!!
        db!!.capture().observation(fresh.copy(lastReceivedAt = 9999, revisionHash = "new-technical-metadata"))
        val synthetic = source("synthetic", origin = "SYNTHETIC")
        rejected { book.save(synthetic) }
        book.save(d("independent", 300, TransactionKind.INCOME))
        book.undo(a.id)
        assertEquals(0L, totals().expense); assertEquals(300L, totals().income)
        val restored = db!!.capture().observation("a")!!
        assertEquals(9999L, restored.lastReceivedAt); assertEquals("new-technical-metadata", restored.revisionHash)
        assertEquals(100L, restored.amountMinor); assertNull(restored.transactionId)
        assertEquals("NEEDS_REVIEW", db!!.capture().candidate("a")!!.status)
        assertEquals(100L, db!!.capture().candidate("a")!!.amountMinor)
        assertEquals(10300L, totals().balances["bank"])
        book.save(a.copy(amount = 200))
        val reposted = db!!.book().transaction(a.id)!!
        assertTrue(reposted.revision > 0)
        rejected { book.save(operationDraft(reposted).copy(expectedRevision = 0, amount = 500)) }
        assertEquals(200L, totals().expense)
    }
}
