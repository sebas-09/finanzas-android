# ADR-003 — Contratos de dominio

- MoneyMinor usa Long y conversión BigDecimal exacta; rechaza redondeo y overflow. No analiza contexto monetario de notificaciones: eso corresponde a F2.
- CaptureSession usa intervalo [inicio, fin). CapturePolicy exige sesión actual abierta, fuente habilitada y fecha de publicación posterior a sesión y enabledSince, sin fechas futuras respecto a recepción. La fecha bancaria nunca decide admisión. F1 enlazará esta política a sbn.postTime y permisos reales.
- NotificationInput es efímero y su toString está redactado. NormalizedBankEvent admite monto/dirección desconocidos y evidencia explicable; no exige una transacción ficticia para representar un pendiente.
- LedgerTransaction mantiene consumo/ingreso separados de AccountPosting. Activos positivos representan efectivo; para CREDIT_CARD el saldo positivo representa deuda. Compras crédito aumentan deuda; pagos la reducen sin consumo adicional.
- Solo registros confirmados, no borrados, no sintéticos y de tipo conocido pueden entrar al libro confirmado. Los repositorios de F3 validarán coherencia entre tipo, cuentas, postings y devoluciones parciales/totales en una operación atómica.
- Sin base inicial se mostrará variación neta, también para deuda. No se fabrican saldos iniciales de cero.
- SourceObservation queda separada del ledger. F3 persistirá enlaces reversibles y deduplicará con evidencia; ni monto+minuto ni una coincidencia ambigua autorizan fusionar.
- Interfaces de casos de uso y repositorios están definidas; F0 no implementa conciliación ni agregados de producción. Las fixtures contables expresan resultados esperados para F3.
- Fechas Instant UTC; períodos de presentación en America/Guayaquil. Los fixtures usan datos sintéticos y paquete propio ec.finanzas.fixture.

