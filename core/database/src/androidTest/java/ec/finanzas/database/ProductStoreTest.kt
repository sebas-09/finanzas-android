package ec.finanzas.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ec.finanzas.dashboard.*
import ec.finanzas.ledger.*
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProductStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "f4_product_test"
    private var db: BootstrapDatabase? = null
    private suspend fun open(): BootstrapDatabase {
        val database = EncryptedDatabaseFactory.open(context, name)
        db = database
        ProductStore(database).initialize()
        BookStore(database).saveAccount("bank", "Cuenta de prueba", BankProvider.PRODUBANCO, InstrumentType.BANK_ACCOUNT, 10000)
        return database
    }
    private fun draft(id: String, amount: Long = 800) = OperationDraft(id,"bank",amount,TransactionKind.EXPENSE,
        LocalDate.of(2026,11,2).atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli(),MoneyDirection.OUTGOING,merchant="SPOTIFY Premium")
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context,name).deleteAfterDatabaseRemoval() }

    @Test fun rulesRespectManualDecisionsAndClassificationUndoPreservesFinancialEffects() = runBlocking {
        val database = open(); val book = BookStore(database); val products = ProductStore(database)
        repeat(3) { book.save(draft("purchase")) }
        val purchase = database.book().transaction("purchase")!!
        assertEquals("subscriptions", purchase.categoryId)
        book.save(operationDraft(purchase).copy(categoryId="food",categoryChanged=true,merchant="Mi alias"))
        assertEquals("food",database.book().transaction("purchase")!!.categoryId)
        book.undo("purchase")
        assertEquals("subscriptions",database.book().transaction("purchase")!!.categoryId)
        assertEquals(9200L,book.observe().first().totals().balances["bank"])
        products.rule(MerchantRuleEntity("higher","CONTAINS","spotify","Nuevo alias","transport",10))
        assertEquals("subscriptions",database.book().transaction("purchase")!!.categoryId)
        book.save(draft("manual-empty").copy(categoryChanged=true))
        assertNull(database.book().transaction("manual-empty")!!.categoryId)
        products.category("transport","Transporte",false)
        book.save(draft("after-archive"))
        assertEquals("subscriptions",database.book().transaction("after-archive")!!.categoryId)
        val original = database.book().transaction("purchase")!!
        book.save(draft("refund",100).copy(kind=TransactionKind.REFUND,direction=MoneyDirection.INCOMING,originalId=original.id,
            categoryId="food",categoryChanged=true))
        assertEquals("subscriptions",database.book().transaction("refund")!!.categoryId)
        assertEquals(1500L,Insights.month(YearMonth.of(2026,11),book.observe().first().insightRecords()).categories["subscriptions"])
    }

    @Test fun budgetDeliveryRetriesPermissionDenialAndNeverRepeatsAcceptedThreshold() = runBlocking {
        val database = open(); val book = BookStore(database); val products = ProductStore(database)
        products.budget("subscriptions","2026-11",1000,true,80)
        book.save(draft("a"))
        val month=YearMonth.of(2026,11)
        products.deliverAlerts(month) { _, _ -> false }
        assertEquals(0,database.product().budgets().single().lastNotifiedLevel)
        val sent=mutableListOf<Int>()
        repeat(3) { products.deliverAlerts(month) { _, level -> sent.add(level); true } }
        assertEquals(listOf(1),sent)
        book.save(draft("b",200))
        repeat(3) { products.deliverAlerts(month) { _,level -> sent.add(level); true } }
        assertEquals(listOf(1,2),sent)
        val b=database.book().transaction("b")!!
        book.softDelete(b.id,b.revision)
        products.deliverAlerts(month) { _, level -> sent.add(level); true }
        assertEquals(listOf(1,2),sent)
        products.budget("subscriptions","2026-11",1000,false,80)
        products.deliverAlerts(month) { _, _ -> fail("Alertas desactivadas"); true }
        database.close(); db=EncryptedDatabaseFactory.open(context,name)
        assertEquals(2,db!!.product().budgets().single().lastNotifiedLevel)
        assertEquals(800L,Insights.month(month,BookStore(db!!).observe().first().insightRecords()).expense)
    }

    @Test fun draftExplicitEmptyCategorySurvivesReopenAndLegacyF3UndoSnapshotsStillWork() = runBlocking {
        val database=open(); val capture=database.capture(); val book=BookStore(database)
        val at=draft("x").occurredAt
        capture.observation(SourceObservationEntity("notice",sourcePackage="fixture",eventFingerprint="event",keyHash="key",revisionHash="rev",
            postedAt=at,receivedAt=at,lastReceivedAt=at,provider="PRODUBANCO",origin="NOTIFICATION",state="NEEDS_REVIEW",amountMinor=800,occurredAt=at,direction="OUTGOING"))
        capture.candidate(CandidateEntity("notice",800,at,"OUTGOING","PRODUBANCO","BANK_ACCOUNT","SPOTIFY Premium",receivedAt=at,origin="NOTIFICATION"))
        book.saveCandidateDraft("notice",800,"bank",TransactionKind.EXPENSE,MoneyDirection.OUTGOING,at,null,null,"SPOTIFY Premium",null,false,null,true)
        database.close(); db=EncryptedDatabaseFactory.open(context,name)
        val reopened=BookStore(db!!)
        assertTrue(db!!.capture().candidate("notice")!!.categoryDecided)
        reopened.save(draft("candidate:notice").copy(candidateId="notice"))
        assertNull(db!!.book().transaction("candidate:notice")!!.categoryId)
        val history=db!!.book().latestHistory("candidate:notice")!!
        fun legacy(json: String): String = JSONObject(json).apply {
            val candidates=getJSONArray("candidates")
            for (index in 0 until candidates.length()) { candidates.getJSONObject(index).remove("categoryId"); candidates.getJSONObject(index).remove("categoryDecided") }
        }.toString()
        db!!.capture().candidate(db!!.capture().candidate("notice")!!.copy(categoryDecided=false))
        db!!.book().history(LedgerHistoryEntity(ownerId=history.ownerId,beforeJson=legacy(history.beforeJson),afterJson=legacy(history.afterJson),createdAt=history.createdAt+1))
        reopened.undo("candidate:notice")
        assertNull(db!!.book().transaction("candidate:notice"))
        assertEquals("NEEDS_REVIEW",db!!.capture().candidate("notice")!!.status)
        assertFalse(db!!.capture().candidate("notice")!!.categoryDecided)
    }
}
