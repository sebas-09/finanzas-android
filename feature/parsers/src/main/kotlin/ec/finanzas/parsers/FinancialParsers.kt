package ec.finanzas.parsers

import ec.finanzas.model.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Acepta centavos o enteros; rechaza agrupadores ambiguos, signos y desbordamientos. */
object MoneyNormalizer {
    fun cents(value: String): Long? {
        val token = value.trim()
        val normalized = when {
            Regex("[0-9]+(?:[.,][0-9]{2})?").matches(token) -> token.replace(',', '.')
            Regex("[0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}").matches(token) -> token.replace(",", "")
            Regex("[0-9]{1,3}(?:\\.[0-9]{3})+,[0-9]{2}").matches(token) -> token.replace(".", "").replace(',', '.')
            else -> return null
        }
        return try {
            BigDecimal(normalized).movePointRight(2).longValueExact().takeIf { it > 0 }
        } catch (_: ArithmeticException) { null }
    }
}

/** Adaptadores preliminares basados exclusivamente en los formatos del spec. */
class PreliminaryBankParser(override val provider: BankProvider) : BankNotificationParser {
    override val version = "${provider.name.lowercase(Locale.ROOT)}-f2.2"
    override fun parse(input: NotificationInput): ParseResult = ParserEngine.parse(input, provider, version, false)
}

class DisabledPeigoParser : PeigoParser {
    override val provider = BankProvider.PEIGO
    override val version = "peigo-disabled-no-sample"
    override fun parse(input: NotificationInput): ParseResult = ParseResult.Rejected(EvidenceCode.UNSUPPORTED_FORMAT)
}

object NotificationParsers {
    fun parse(input: NotificationInput, provider: BankProvider?, outlook: Boolean): ParseResult = when {
        outlook -> ParserEngine.parse(input, null, "outlook-experimental-f2.2", true)
        provider == BankProvider.PEIGO -> DisabledPeigoParser().parse(input)
        provider != null -> PreliminaryBankParser(provider).parse(input)
        else -> ParseResult.Rejected(EvidenceCode.UNSUPPORTED_FORMAT)
    }
}

private object ParserEngine {
    // Conservar el token completo, incluso si es inválido: nunca aceptar un prefijo numérico.
    private const val TOKEN = "([^\\s!;)]+(?:[\\s\\u00a0\\u202f]+[0-9][0-9.,]*)?)?"
    private val amount = Regex("([-+])?\\s*(?:\\$|\\bUSD\\s*)\\s*" + TOKEN, RegexOption.IGNORE_CASE)
    private val receivedAmount = Regex("recibiste\\s+(?:(?:\\$|USD)\\s*)?" + TOKEN, RegexOption.IGNORE_CASE)
    private val unsafe = Regex("c[oó]digo|contrase[nñ]a|otp|promoci[oó]n|oferta|intenta|fallid|rechazad|no se|no fue|no pagaste|no recargaste|pendiente|cancelad|anulad|€|£", RegexOption.IGNORE_CASE)
    fun parse(input: NotificationInput, bank: BankProvider?, version: String, outlook: Boolean): ParseResult {
        if (input.isGroupSummary) return ParseResult.Rejected(EvidenceCode.GROUP_SUMMARY)
        if (bank == BankProvider.PEIGO) return ParseResult.Rejected(EvidenceCode.UNSUPPORTED_FORMAT)
        // Preferir la vista expandida; comparar todas las vistas para no elegir entre importes contradictorios.
        val parts = (listOfNotNull(input.title, input.text, input.bigText) + input.textLines).distinct()
        val text = parts.joinToString("\n")
        val lower = text.lowercase(Locale.ROOT)
        if (unsafe.containsMatchIn(text) || Regex("\\b(?:EUR|GBP|COP|MXN|CAD|AUD|PEN)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
            return ParseResult.Rejected(EvidenceCode.UNSUPPORTED_FORMAT)
        val kind = when {
            outlook && lower.contains("consumo tarjeta de débito") -> CandidateKind.DEBIT_PURCHASE
            bank == BankProvider.PICHINCHA && lower.contains("recibiste") -> CandidateKind.INCOMING_TRANSFER
            bank == BankProvider.PICHINCHA && lower.contains("transferencia") && lower.contains("exitosa") -> CandidateKind.OUTGOING_TRANSFER
            bank == BankProvider.GUAYAQUIL && lower.contains("se ha realizado una transferencia") -> CandidateKind.OUTGOING_TRANSFER
            bank == BankProvider.PRODUBANCO && lower.contains("transferencia enviada") -> CandidateKind.OUTGOING_TRANSFER
            bank == BankProvider.DEUNA && lower.contains("pagaste") -> CandidateKind.PAYMENT
            bank == BankProvider.DEUNA && lower.contains("recargaste") -> CandidateKind.WALLET_TOP_UP
            else -> return ParseResult.Rejected(EvidenceCode.UNSUPPORTED_FORMAT)
        }
        val tokens = amount.findAll(text).map { it.groupValues[1] + it.groupValues[2] }.toList() +
            if (kind == CandidateKind.INCOMING_TRANSFER) receivedAmount.findAll(text).map { it.groupValues[1] }.toList() else emptyList()
        val values = tokens.map(MoneyNormalizer::cents)
        val cents = values.distinct().singleOrNull()?.takeIf { values.all { value -> value != null } }
        val truncated = parts.any { it.endsWith("…") || it.endsWith("...") || it.length >= 4096 }
        val direction = if (kind == CandidateKind.INCOMING_TRANSFER || kind == CandidateKind.WALLET_TOP_UP)
            MoneyDirection.INCOMING else MoneyDirection.OUTGOING
        // El único formato de fecha conocido es el de Produbanco. No usar fecha de recepción como operación.
        val dates = Regex("\\b([0-9]{2}/[0-9]{2}/[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        val occurred = if (bank == BankProvider.PRODUBANCO && dates.size == 1 && dates.single().substring(3, 5).toInt() > 12) try {
            LocalDateTime.parse(dates.single(), DateTimeFormatter.ofPattern("MM/dd/uuuu HH:mm:ss")
                .withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.of("America/Guayaquil")).toInstant()
                .takeIf { !it.isAfter(input.receivedAt) }
        } catch (_: java.time.DateTimeException) { null } else null
        val evidence = mutableSetOf(EvidenceCode.ALLOWED_SOURCE, EvidenceCode.EXPLICIT_KIND,
            EvidenceCode.EXPLICIT_DIRECTION, EvidenceCode.MISSING_ACCOUNT, EvidenceCode.UNKNOWN_PURPOSE)
        if (cents != null) evidence += EvidenceCode.UNAMBIGUOUS_AMOUNT else evidence += EvidenceCode.AMBIGUOUS_AMOUNT
        if (occurred == null) evidence += EvidenceCode.MISSING_OPERATION_DATE
        if (truncated) evidence += EvidenceCode.TRUNCATED_CONTENT
        if (outlook) evidence += EvidenceCode.EXPERIMENTAL_SOURCE
        val merchants = Regex("(?:^|\\n|;)\\s*comercio:\\s*([^\\n;]+)", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].trim().replace(Regex("\\s+"), " ") }.distinct().toList()
        val merchant = merchants.singleOrNull()?.takeIf { value ->
            value.length in 2..80 && !Regex("[0-9]{4}|@|https?://").containsMatchIn(value) &&
                value.all { it.isLetterOrDigit() || it in " &.'-" }
        }
        return ParseResult.Candidate(NormalizedBankEvent(input.sourcePackage, "", input.receivedAt,
            occurred, cents, direction = direction, bank = bank,
            instrument = if (bank == BankProvider.DEUNA) InstrumentType.WALLET else if (outlook) InstrumentType.DEBIT_CARD else InstrumentType.BANK_ACCOUNT,
            merchantNormalized = merchant, transactionReferenceHash = null, kind = kind,
            confidence = if (cents == null) 0.35 else if (truncated || outlook) 0.55 else 0.75,
            evidenceCodes = evidence), version)
    }
}
