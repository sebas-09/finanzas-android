package ec.finanzas.model

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FoundationTest {
    private val start = Instant.parse("2026-10-14T17:00:00Z")
    private val source = AllowedSource("ec.finanzas.fixture", BankProvider.PICHINCHA, true, start)

    @Test fun moneyIsExactAndRejectsRoundingAndOverflow() {
        assertEquals(399L, MoneyMinor.fromDecimal(BigDecimal("3.99")).value)
        assertEquals(1L, MoneyMinor.fromDecimal(BigDecimal("0.01")).value)
        assertEquals(100L, MoneyMinor.fromDecimal(BigDecimal("1.00")).value)
        assertThrows(ArithmeticException::class.java) { MoneyMinor.fromDecimal(BigDecimal("1.001")) }
        assertThrows(ArithmeticException::class.java) { MoneyMinor(Long.MAX_VALUE) + MoneyMinor(1) }
        assertThrows(ArithmeticException::class.java) { MoneyMinor.fromDecimal(BigDecimal("92233720368547758.08")) }
    }

    @Test fun captureUsesPublicationTimeAndCurrentConsentWindow() {
        val session = CaptureSession(start)
        assertFalse(CapturePolicy.accepts(source, session, CaptureState.ACTIVE, start.minusSeconds(1), start.plusSeconds(5)))
        assertTrue(CapturePolicy.accepts(source, session, CaptureState.ACTIVE, start, start))
        assertFalse(CapturePolicy.accepts(source, session, CaptureState.PAUSED, start, start))
        assertFalse(CapturePolicy.accepts(source, session, CaptureState.PERMISSION_REVOKED, start, start))
        assertFalse(CapturePolicy.accepts(source.copy(enabled = false), session, CaptureState.ACTIVE, start, start))
        assertFalse(CapturePolicy.accepts(source, CaptureSession(start, start.plusSeconds(2)), CaptureState.ACTIVE, start, start))
        val resumed = CaptureSession(start.plusSeconds(20))
        assertFalse(CapturePolicy.accepts(source, resumed, CaptureState.ACTIVE, start.plusSeconds(10), start.plusSeconds(30)))
        val laterSource = source.copy(enabledSince = start.plusSeconds(40))
        assertFalse(CapturePolicy.accepts(laterSource, resumed, CaptureState.ACTIVE, start.plusSeconds(30), start.plusSeconds(45)))
        assertFalse(CapturePolicy.accepts(source, session, CaptureState.ACTIVE, start.plusSeconds(1), start))
    }

    @Test fun incompleteAndSyntheticMovementsCannotEnterConfirmedLedger() {
        val candidate = LedgerTransaction("test", 100, TransactionKind.UNKNOWN, start, start,
            ReviewStatus.NEEDS_REVIEW, DataOrigin.NOTIFICATION, emptyList())
        assertFalse(candidate.countsInConfirmedLedger)
        val expense = candidate.copy(kind = TransactionKind.EXPENSE, status = ReviewStatus.MANUAL_CONFIRMED,
            postings = listOf(AccountPosting("bank", -100)))
        assertTrue(expense.countsInConfirmedLedger)
        assertFalse(expense.copy(origin = DataOrigin.SYNTHETIC).countsInConfirmedLedger)
        assertFalse(expense.copy(deletedAt = start).countsInConfirmedLedger)
        assertFalse(expense.copy(status = ReviewStatus.IGNORED).countsInConfirmedLedger)
    }

    @Test fun invalidRefundOrRepeatedAccountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LedgerTransaction("refund", 100, TransactionKind.REFUND, start, start,
                ReviewStatus.MANUAL_CONFIRMED, DataOrigin.MANUAL, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerTransaction("duplicate", 100, TransactionKind.OWN_TRANSFER, start, start,
                ReviewStatus.MANUAL_CONFIRMED, DataOrigin.MANUAL,
                listOf(AccountPosting("a", -100), AccountPosting("a", 100)))
        }
    }
}

