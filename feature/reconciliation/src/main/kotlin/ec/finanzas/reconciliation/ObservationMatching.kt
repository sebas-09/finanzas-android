package ec.finanzas.reconciliation

data class MatchFacts(val account: String, val amount: Long, val direction: String, val kind: String,
    val provider: String?, val occurredAt: Long?, val reference: String?, val merchant: String?,
    val sourcePackage: String, val keyHash: String, val postedAt: Long, val revisionHash: String = "")
enum class MatchStrength { NONE, SUGGESTED, TECHNICAL, BUSINESS_REFERENCE }

object ObservationMatching {
    fun compare(a: MatchFacts, b: MatchFacts, windowMillis: Long): MatchStrength {
        require(windowMillis in 60_000L..86_400_000L)
        if (a.account != b.account || a.amount != b.amount || a.direction != b.direction || a.kind != b.kind ||
            (a.provider != null && b.provider != null && a.provider != b.provider) ||
            (a.reference != null && b.reference != null && a.reference != b.reference)) return MatchStrength.NONE
        val sameMerchant = a.merchant.isNullOrBlank() || b.merchant.isNullOrBlank() || a.merchant.equals(b.merchant, true)
        if (!sameMerchant) return MatchStrength.NONE
        if (a.revisionHash.isNotBlank() && a.revisionHash == b.revisionHash && a.sourcePackage == b.sourcePackage && a.keyHash == b.keyHash && a.postedAt == b.postedAt &&
            a.occurredAt == b.occurredAt && a.reference == b.reference) return MatchStrength.TECHNICAL
        if (a.occurredAt == null || b.occurredAt == null || kotlin.math.abs(a.occurredAt - b.occurredAt) > windowMillis) return MatchStrength.NONE
        if (a.reference != null && a.reference == b.reference && a.provider != null && a.provider == b.provider)
            return MatchStrength.BUSINESS_REFERENCE
        return if (!a.merchant.isNullOrBlank() && !b.merchant.isNullOrBlank()) MatchStrength.SUGGESTED else MatchStrength.NONE
    }
}
