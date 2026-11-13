package ec.finanzas.reconciliation

import org.junit.Assert.*
import org.junit.Test

class ObservationMatchingTest {
    private val a = MatchFacts("a", 100, "OUTGOING", "EXPENSE", "PICHINCHA", 1000, null, "TIENDA", "bank", "key", 1100, "hash")
    @Test fun exactTechnicalPublicationIsStrong() { assertEquals(MatchStrength.TECHNICAL, ObservationMatching.compare(a, a, 300000)) }
    @Test fun differentRawDataCannotCollapseLegitimateRepeatedPurchases() {
        assertEquals(MatchStrength.SUGGESTED, ObservationMatching.compare(a, a.copy(revisionHash = "different"), 300000))
    }
    @Test fun sameAmountAndMinuteAloneAreInsufficient() {
        assertEquals(MatchStrength.NONE, ObservationMatching.compare(a.copy(merchant = null), a.copy(sourcePackage = "email", merchant = null), 300000))
        assertEquals(MatchStrength.SUGGESTED, ObservationMatching.compare(a, a.copy(sourcePackage = "email"), 300000))
    }
    @Test fun businessReferenceNeedsAccountContextAndProvider() {
        val b = a.copy(reference = "ref", sourcePackage = "email")
        assertEquals(MatchStrength.BUSINESS_REFERENCE, ObservationMatching.compare(a.copy(reference = "ref"), b, 300000))
        assertEquals(MatchStrength.NONE, ObservationMatching.compare(a.copy(reference = "ref"), b.copy(account = "other"), 300000))
        assertEquals(MatchStrength.SUGGESTED, ObservationMatching.compare(a.copy(reference = "ref"), b.copy(provider = null), 300000))
    }
    @Test fun contradictionsAndMissingDatesPreventAutomaticMatching() {
        listOf(a.copy(account = "b"), a.copy(amount = 101), a.copy(direction = "INCOMING"), a.copy(kind = "INCOME"),
            a.copy(provider = "DEUNA"), a.copy(merchant = "OTRA")).forEach { b ->
            assertEquals(MatchStrength.NONE, ObservationMatching.compare(a, b, 300000))
        }
        assertEquals(MatchStrength.NONE, ObservationMatching.compare(a.copy(reference = "ref"), a.copy(reference = "other"), 300000))
        assertEquals(MatchStrength.NONE, ObservationMatching.compare(a, a.copy(sourcePackage = "email", occurredAt = null), 300000))
        assertEquals(MatchStrength.NONE, ObservationMatching.compare(a, a.copy(sourcePackage = "email", occurredAt = 400000), 300000))
    }
}
