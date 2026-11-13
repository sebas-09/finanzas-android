package ec.finanzas.model

import java.math.BigDecimal
import java.math.RoundingMode

/** USD exactos. No interpreta texto de notificaciones: eso corresponde al parser contextual. */
@JvmInline
value class MoneyMinor(val value: Long) {
    operator fun plus(other: MoneyMinor) = MoneyMinor(Math.addExact(value, other.value))
    operator fun minus(other: MoneyMinor) = MoneyMinor(Math.subtractExact(value, other.value))

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(value, 2)

    companion object {
        fun fromDecimal(value: BigDecimal): MoneyMinor = MoneyMinor(
            value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact(),
        )
    }
}
