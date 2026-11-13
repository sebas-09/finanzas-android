package ec.finanzas.database

import androidx.room.*
import ec.finanzas.dashboard.*
import java.time.YearMonth
import java.util.UUID

@Dao
interface ProductDao {
    @Query("SELECT * FROM diagnostic_counter ORDER BY reason") suspend fun diagnostics(): List<DiagnosticCounterEntity>
    @Query("SELECT * FROM category ORDER BY name,id") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM merchant_rule ORDER BY priority DESC,id") suspend fun rules(): List<MerchantRuleEntity>
    @Query("SELECT * FROM budget ORDER BY month DESC,categoryId") suspend fun budgets(): List<BudgetEntity>
    @Upsert suspend fun category(value: CategoryEntity)
    @Upsert suspend fun rule(value: MerchantRuleEntity)
    @Upsert suspend fun budget(value: BudgetEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedCategory(value: CategoryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedRule(value: MerchantRuleEntity)
    @Query("DELETE FROM budget WHERE id=:id") suspend fun removeBudget(id: String)
}

fun ruleSpec(rule: MerchantRuleEntity) = RuleSpec(rule.id, rule.matchType, rule.pattern, rule.merchantAlias, rule.categoryId, rule.priority, rule.enabled)

fun BookSnapshot.insightRecords(): List<InsightRecord> = transactions.map { t ->
    val account = accounts.firstOrNull { it.id == t.accountId }
    val category = if (t.kind == "REFUND") transactions.firstOrNull { it.id == t.originalTransactionId }?.categoryId else t.categoryId
    val instrument = if (cards.any { it.accountId == account?.id && it.type == "DEBIT" }) "DEBIT_CARD" else account?.let { accountType(it.kind).name }
    val involved = accounts.filter { it.id == t.accountId || it.id == t.counterAccountId }
    InsightRecord(bookOperation(t, postings), t.occurredAt, category, t.merchantAlias, account?.provider,
        instrument, t.origin, if (t.deletedAt != null) "DELETED" else t.status, t.estimatedDate,
        involved.map { it.provider }.toSet(), involved.map { accountType(it.kind).name }.toSet() + cards.filter { card -> involved.any { it.id == card.accountId } }.map { if (it.type == "DEBIT") "DEBIT_CARD" else "CREDIT_CARD" })
}

class ProductStore(private val db: BootstrapDatabase) {
    private val dao = db.product()

    suspend fun initialize() = db.withTransaction {
        dao.seedCategory(CategoryEntity("subscriptions", "Suscripciones"))
        dao.seedCategory(CategoryEntity("food", "Alimentación"))
        dao.seedCategory(CategoryEntity("transport", "Transporte"))
        dao.seedRule(MerchantRuleEntity("spotify", "CONTAINS", "spotify", "Spotify", "subscriptions", 0))
    }

    suspend fun category(id: String, name: String, active: Boolean) = db.withTransaction {
        require(name.trim().length in 1..40 && name.none(Char::isISOControl)) { "La categoría requiere un nombre de 1 a 40 caracteres." }
        require(dao.categories().none { it.id != id && Insights.normalize(it.name) == Insights.normalize(name) }) { "Ya existe una categoría con ese nombre." }
        val old = dao.categories().firstOrNull { it.id == id }
        dao.category(CategoryEntity(id, name.trim(), old?.parentId, old?.icon ?: "category", active))
        audit(id, "CATEGORY_SAVED", "name,active")
    }

    suspend fun rule(value: MerchantRuleEntity) = db.withTransaction {
        require(value.matchType in setOf("EXACT", "CONTAINS")) { "Elige coincidencia exacta o contiene." }
        require(value.pattern.trim().length in 1..80 && value.pattern.none(Char::isISOControl)) { "El patrón requiere de 1 a 80 caracteres." }
        require(value.merchantAlias.trim().length in 1..80 && value.merchantAlias.none(Char::isISOControl)) { "Escribe un alias de comercio de 1 a 80 caracteres." }
        require(value.categoryId == null || dao.categories().any { it.id == value.categoryId && (it.active || !value.enabled) }) { "Elige una categoría activa." }
        dao.rule(value.copy(pattern = value.pattern.trim(), merchantAlias = value.merchantAlias.trim()))
        audit(value.id, "RULE_SAVED", "pattern,merchantAlias,categoryId,priority,enabled")
    }

    suspend fun budget(category: String, month: String, limit: Long, alerts: Boolean, threshold: Int) = db.withTransaction {
        require(runCatching { YearMonth.parse(month).toString() == month }.getOrDefault(false)) { "Escribe el mes como AAAA-MM." }
        require(limit > 0 && threshold in 1..100) { "El límite debe ser positivo y el umbral debe estar entre 1 y 100 %." }
        val old = dao.budgets().firstOrNull { it.categoryId == category && it.month == month }
        require(dao.categories().any { it.id == category && (it.active || old != null) }) { "Selecciona una categoría activa." }
        val notified = if (old?.limitMinor == limit && old.threshold == threshold) old.lastNotifiedLevel else 0
        dao.budget(BudgetEntity(old?.id ?: UUID.randomUUID().toString(), category, month, limit, alerts, threshold, notified))
        audit(category, "BUDGET_SAVED", "month,limitMinor,alertsEnabled,threshold")
    }

    suspend fun removeBudget(id: String) = db.withTransaction {
        dao.removeBudget(id); audit(id, "BUDGET_REMOVED", "budget")
    }

    /** La marca solo avanza si Android acepta el aviso. El identificador estable permite reintentar sin duplicarlo. */
    suspend fun deliverAlerts(month: YearMonth, post: (String, Int) -> Boolean) = db.withTransaction {
        val book = BookSnapshot(db.book().accounts(), db.book().transactions(), db.book().postings(), emptyList(), emptyList())
        val totals = Insights.month(month, book.insightRecords())
        dao.budgets().filter { it.month == month.toString() && it.alertsEnabled }.forEach { budget ->
            val level = Insights.budgetLevel(totals.categories[budget.categoryId] ?: 0, budget.limitMinor, budget.threshold)
            if (level > budget.lastNotifiedLevel && post(budget.id, level)) dao.budget(budget.copy(lastNotifiedLevel = level))
        }
    }

    private suspend fun audit(id: String, kind: String, fields: String) = db.capture().audit(
        ChangeAuditEntity(UUID.randomUUID().toString(), id, kind, System.currentTimeMillis(), fields))
}
