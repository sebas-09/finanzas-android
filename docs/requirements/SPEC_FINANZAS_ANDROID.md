# SPEC — Finanzas Personales Android

**Versión:** 1.0  
**Fecha:** 2026-10-14  
**Estado:** Propuesta lista para implementación (MVP)  
**Plataforma:** Android nativo  
**Idioma:** Español (Ecuador)  
**Moneda base:** USD  
**Persistencia:** Local, offline-first  

---

## 1. Propósito y decisiones cerradas

Crear una aplicación Android que **registre y organice automáticamente movimientos financieros nuevos** recibidos mediante notificaciones del teléfono, sin conexión a credenciales bancarias, sin servidores y sin importar historial previo. El usuario podrá consultar saldos *estimados*, gastos por categoría, ingresos, transferencias, recargas y la deuda de su tarjeta de crédito.

**Decisiones de alcance:**

1. Capturar únicamente notificaciones publicadas **después de completar el onboarding y activar la captura**. No leer notificaciones activas anteriores mediante `getActiveNotifications()` ni importar correos o extractos históricos.
2. Bancos y medios iniciales: Banco Pichincha, Banco Guayaquil, Produbanco (cuenta y tarjeta **de débito**), Deuna, y PeiGo (tarjeta **de crédito**).
3. Kotlin, Jetpack Compose, Material 3, arquitectura modular Clean Architecture / MVVM, Room/SQLite, coroutines y Flow.
4. No requerir login, backend, nube, suscripciones, APIs pagas ni IA remota para operar.
5. Solicitar al usuario acceso explícito al servicio de notificaciones. Capturar solo aplicaciones habilitadas; **Outlook desactivado por defecto** y no se pide acceso al buzón ni permisos Microsoft Graph.
6. El contenido bancario es sensible: minimización de datos, almacenamiento local cifrado y ninguna transmisión a terceros.
7. No prometer cobertura total: algunas entidades no emiten notificaciones, las agrupan, las ocultan, cambian su formato o Android puede impedir su entrega.
8. Las cuentas bancarias pueden tener un **saldo estimado**, nunca presentarlo como saldo verificado con el banco.

### 1.1 Fuera de alcance del MVP

- Conexión directa mediante credenciales, scraping de banca online, open banking, APIs de bancos, SMS o lectura del buzón de correo.
- Recuperación de notificaciones anteriores a la activación; sincronización entre dispositivos; soporte iOS.
- Conciliación con estados de cuenta reales; ejecución de pagos o transferencias.
- Predicción automática de ingresos o gastos, inversiones, préstamos amortizados, multiusuario.
- Reconocimiento fiable del emisor de PeiGo sin una notificación de ejemplo validada.

## 2. Usuarios y casos de uso

**Actor principal:** propietario de un dispositivo Android con varias cuentas y medios de pago.

| ID | Caso de uso | Resultado esperado |
|---|---|---|
| CU-01 | Activar captura | Conceder permiso del sistema y habilitar fuentes seleccionadas |
| CU-02 | Recibir compra de débito | Crear un gasto en Produbanco, con comercio y categoría |
| CU-03 | Recibir consumo de crédito | Registrar gasto y aumentar deuda estimada de PeiGo |
| CU-04 | Recibir transferencia enviada | Registrar salida; decidir si gasto o transferencia interna |
| CU-05 | Recibir transferencia recibida | Registrar entrada; decidir si ingreso externo o transferencia interna |
| CU-06 | Recargar Deuna | Traslado entre cuentas propias, no gasto adicional |
| CU-07 | Recibir notificaciones repetidas | Fusionar o marcar para revisión, sin duplicar |
| CU-08 | Corregir un registro | Modificar importe, categoría, cuenta, descripción o clasificación con auditoría local |
| CU-09 | Ver resumen mensual | Presentar ingreso, gasto, balance y deuda estimada, separados |
| CU-10 | Revisar alertas dudosas | Bandeja de pendientes y opción ignorar/confirmar |

## 3. Fuentes de notificaciones y cobertura

| Fuente | Producto | Ejemplos reales disponibles | Nivel inicial |
|---|---|---|---|
| Banco Pichincha / Banca móvil | Cuenta | «¡Transferencia exitosa!», «¡Recibiste 1.00!» | Parser preliminar |
| Banco Guayaquil | Cuenta | «Se ha realizado una transferencia de $0.01...» | Parser preliminar |
| Produbanco | Cuenta | «Transferencia enviada», «Por $0.01 a la cuenta ...» | Parser preliminar |
| Produbanco | Débito | Correo: «Consumo tarjeta de débito por USD 3.99», comercio Spotify | Formato de correo conocido; **no es prueba de que la notificación de la app incluya igual detalle** |
| Deuna | Billetera | «¡Listo! Pagaste $1,00», «Recargaste $1,00» | Parser preliminar |
| PeiGo | Crédito | Aún falta muestra | Adaptador pendiente; entrada manual disponible |
| Outlook (opcional) | Alertas de correo | Notificaciones agrupadas de Produbanco/otros | Opt-in experimental; solo texto visible de nueva notificación |

**Regla:** no codificar nombres de paquetes Android inventados. Identificarlos al instalar/seleccionar fuentes mediante `PackageManager`, verificar firma/identidad cuando sea viable y persistir `packageName` real en un registro de fuentes permitidas. Mantener perfiles editables y pruebas por proveedor.

**Limitación:** Android puede entregar títulos/resúmenes, contenido truncado, mensajes agrupados o contenido redactado. Un correo completo NO es legible simplemente por recibir una notificación Outlook. Para el MVP, si falta monto o sentido del movimiento, guardar únicamente un candidato pendiente o ignorar según confianza; jamás inventar campos.

## 4. Requisitos funcionales (RF)

### 4.1 Inicio y permisos

- **RF-001**: Onboarding en español con descripción de datos leídos, almacenamiento local y riesgos de privacidad.
- **RF-002**: Abrir la pantalla de ajustes de Android para conceder acceso a notificaciones al servicio autorizado; detectar concesión/revocación.
- **RF-003**: Registrar instante de activación `captureStartedAt` en UTC tras consentimiento; descartar eventos anteriores a ese instante, incluso si Android vuelve a emitir callbacks.
- **RF-004**: Mostrar selección explícita de fuentes bancarias; todas deshabilitadas hasta elección del usuario.
- **RF-005**: Permitir pausar/reanudar captura. Una pausa no se rellena retrospectivamente.
- **RF-006**: Mostrar estado del servicio: activo, desactivado, permiso revocado, fuente no detectada.
- **RF-007**: No requerir acceso a contactos, ubicación, cámara, SMS, archivos ni internet para la captura principal.

### 4.2 Ingesta y normalización

- **RF-008**: Implementar `NotificationListenerService`; procesar `onNotificationPosted` usando `StatusBarNotification`, `Notification.EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_TEXT_LINES` y datos estructurados disponibles.
- **RF-009**: Aceptar solo `packageName` permitido; ignorar actualizaciones irrelevantes y notificaciones no financieras.
- **RF-010**: Normalizar moneda USD, separadores decimales `1,00` y `1.00`, fecha/hora (zona `America/Guayaquil`), tipo de movimiento y comercio.
- **RF-011**: Conservar campos originalizados mínimos necesarios para depuración local opcional; no almacenar indefinidamente notificaciones completas ni datos personales del destinatario.
- **RF-012**: Extraer importe como `Decimal` representado en centavos (`Long`), nunca `Float`/`Double` para cálculos monetarios.
- **RF-013**: Distinguir fecha de operación declarada por el banco y fecha de recepción de notificación; preferir fecha declarada válida, guardar ambas.
- **RF-014**: Tratar notificaciones agrupadas y actualizaciones del mismo `sbn.key` de forma idempotente; no crear transacciones por cada actualización.
- **RF-015**: Registrar causa de rechazo o estado pendiente (sin exposición del mensaje completo en logs).

### 4.3 Clasificación y conciliación

- **RF-016**: Soportar `EXPENSE`, `INCOME`, `OWN_TRANSFER`, `CREDIT_CARD_PAYMENT`, `REFUND`, `CREDIT_CARD_CHARGE`, `WALLET_TOP_UP`, `UNKNOWN`.
- **RF-017**: Separar **flujo de dinero**, **categoría de consumo** y **medio financiero**; transferencias internas, pagos de tarjeta y recargas no aumentan gasto por consumo.
- **RF-018**: Deducir categoría mediante reglas deterministas por comercio, editables y persistentes (ej. Spotify → Suscripciones).
- **RF-019**: Asociar dos alertas de una misma operación con reglas de deduplicación; conservar trazabilidad de los eventos que respaldan un registro.
- **RF-020**: Conciliar posibles transferencias entre cuentas propias por importe, ventanas de tiempo, origen/destino y evidencias; exigir confirmación si ambiguas.
- **RF-021**: Si se paga tarjeta PeiGo desde una cuenta propia: disminuir efectivo y deuda estimada, **sin registrar segundo gasto**.
- **RF-022**: Si se compra con crédito PeiGo: registrar gasto en fecha de compra e incrementar deuda estimada; el pago posterior no suma gastos.
- **RF-023**: Si Deuna se recarga desde un banco propio: disminuir banco y aumentar billetera, sin gasto; si el origen es desconocido, enviar a revisión.
- **RF-024**: Detectar devoluciones y reversos como correcciones vinculadas a su gasto, sin generar ingreso ordinario duplicado.
- **RF-025**: Manejar ambigüedad con estados `AUTO_CONFIRMED`, `NEEDS_REVIEW`, `MANUAL_CONFIRMED`, `IGNORED` y puntuación de confianza explicable.
- **RF-026**: Nunca consolidar automáticamente dos transacciones legítimas del mismo monto/comercio/tiempo sin suficiente evidencia.

### 4.4 Gestión financiera

- **RF-027**: Crear/editar/archivar cuentas, billetera y tarjeta con alias y tipo; saldo inicial manual opcional.
- **RF-028**: Permitir registro, edición, borrado lógico y anotaciones manuales de movimientos.
- **RF-029**: Dashboard mensual con gastos reales, ingresos externos, balance ingresos−gastos y deuda estimada; excluir movimientos internos de indicadores de consumo.
- **RF-030**: Filtros por fecha, banco, cuenta, tarjeta, categoría, origen, estado y tipo.
- **RF-031**: Control de tarjeta PeiGo con límite opcional, fecha de corte, vencimiento, consumo del ciclo, pagos y deuda **estimada**.
- **RF-032**: Presupuesto por categoría y alertas locales opcionales de umbral.
- **RF-033**: Búsqueda por comercio/alias sin mostrar números de cuenta ni terceros innecesarios.
- **RF-034**: Exportar CSV bajo acción explícita, con aviso de sensibilidad; importación no requerida.
- **RF-035**: Eliminar completamente datos y restablecer captura con nuevo `captureStartedAt`.

### 4.5 Diagnóstico y transparencia

- **RF-036**: Mostrar lista de fuentes detectadas y última notificación financiera procesada por fuente, sin revelar texto sensible.
- **RF-037**: Ofrecer modo prueba con notificaciones sintéticas, visible como prueba y sin afectar estadísticas reales.
- **RF-038**: Mostrar al usuario advertencias sobre cobertura incompleta y saldos estimados.
- **RF-039**: Revisar manualmente cualquier movimiento no identificado antes de contabilizarlo como gasto definitivo.
- **RF-040**: Configurar categorías, nombres de comercios, reglas personalizadas y deshacer clasificación previa.

## 5. Reglas de negocio (RN)

| ID | Regla |
|---|---|
| RN-01 | Un débito comercial confirmado incrementa gasto y disminuye saldo estimado de cuenta, si existe saldo inicial |
| RN-02 | Un cargo a crédito incrementa gasto y deuda estimada; no disminuye efectivo en la compra |
| RN-03 | Pagar tarjeta de crédito reduce deuda y efectivo, sin nuevo gasto de consumo |
| RN-04 | Transferencia entre cuentas propias no es ingreso ni gasto |
| RN-05 | Recarga de billetera desde cuenta propia es transferencia interna |
| RN-06 | Transferencia a tercero es salida monetaria; si no se conoce finalidad, categoría «Transferencia a tercero» o pendiente, sin asumir que siempre es consumo |
| RN-07 | Ingreso recibido de tercero puede ser ingreso, reembolso, préstamo o traslado; si no está claro, solicitar clasificación |
| RN-08 | Notificaciones Outlook y bancarias del mismo movimiento deben enlazarse, no duplicarse |
| RN-09 | Eliminar/editar un registro recalcula agregados y saldos derivados |
| RN-10 | Las cuentas sin saldo inicial no muestran saldo real; solo variación neta registrada |
| RN-11 | Todas las fechas se guardan en UTC y se presentan en `America/Guayaquil` |
| RN-12 | Datos no confiables o truncados nunca generan importes por inferencia |
| RN-13 | Si el servicio estuvo desactivado, informar «puede haber movimientos no registrados» |
| RN-14 | Saldo de tarjeta estimado puede diferir del estado de cuenta por intereses, comisiones, pendientes y movimientos omitidos |

## 6. Reglas iniciales por entidad

> Los textos siguientes son *ejemplos de patrones*, no contratos oficiales del banco. Se deben comprobar mediante pruebas con notificaciones reales y adaptadores versionados.

### 6.1 Banco Pichincha

- Título «¡Transferencia exitosa!» + texto «La transferencia por $1.00 a la cuenta ... fue exitosa» → candidato `OUTGOING_TRANSFER`.
- Título «¡Recibiste 1.00!» + texto «... te envió 1.00 ...» → candidato `INCOMING_TRANSFER`.
- Normalizar formatos con/sin signo `$`; no asumir que transferencia recibida es salario.

### 6.2 Banco Guayaquil

- Texto «Se ha realizado una transferencia de $0.01 a la Cuenta AHORROS ...» → candidato `OUTGOING_TRANSFER`.
- Identificar sentido por frase explícita, no solo por presencia de «transferencia».

### 6.3 Produbanco

- App: «Transferencia enviada» + «Por $0.01 a la cuenta ... el 10/14/2026 11:24:35» → `OUTGOING_TRANSFER`.
- Ejemplo **de correo** recibido: asunto «Consumo tarjeta de débito por USD 3.99» y cuerpo «Valor: USD 3.99», «Establecimiento: Spotify ...», «Fecha y Hora: 10/Octubre/2026 8:09» → `DEBIT_PURCHASE`; el MVP solo podrá extraer campos presentes en la **notificación de Outlook** si el usuario la habilita, no en el cuerpo de correo al que la notificación no dé acceso.
- Categoría sugerida para Spotify: Suscripciones / Entretenimiento.

### 6.4 Deuna

- «¡Listo! Pagaste $1,00» → `PAYMENT`, sujeto a conciliación por fuente de fondos.
- «Recargaste $1,00» → `WALLET_TOP_UP`, verificar origen antes de clasificar como transferencia propia.

### 6.5 PeiGo (tarjeta de crédito)

- Implementar interfaz `PeigoParser` y pruebas placeholder; **no inventar regex de producción** hasta recibir una notificación real anonimizada.
- En ausencia de patrón confirmado, eventos se ignoran de forma segura o se envían a revisión según detectabilidad de fuente; opción de consumo manual.

## 7. Motor de extracción y decisiones

**Pipeline:** `NotificationListenerService` → validación de fuente/tiempo → extracción de extras → normalización → clasificación de evento → verificación de duplicados → conciliación → persistencia transaccional → actualización de `Flow` UI.

### 7.1 Contrato de evento normalizado

```kotlin
data class NormalizedBankEvent(
    val sourcePackage: String,
    val notificationKeyHash: String,
    val observedAt: Instant,
    val occurredAt: Instant?,
    val amountMinor: Long?,
    val currency: String = "USD",
    val direction: MoneyDirection?,
    val bank: BankProvider?,
    val instrument: InstrumentType?,
    val merchantNormalized: String?,
    val transactionReferenceHash: String?,
    val kind: CandidateKind,
    val confidence: Double,
    val evidenceCodes: Set<String>
)
```

No guardar texto completo por defecto ni datos personales extraídos de terceros. Los hashes deben usar secreto local (HMAC) cuando se basen en datos previsibles.

### 7.2 Extracción robusta

- Reconocer importes `USD 3.99`, `$3,99`, `$1.00`, `1,00` **solo cuando haya evidencia contextual monetaria**; evitar leer fechas o dígitos de cuentas como importe.
- Soportar mayúsculas, emojis, acentos y espacios variables.
- No inferir monto a partir del título si no hay coincidencia no ambigua.
- Reglas regex con pruebas unitarias, límites de longitud y ejecución controlada.
- Adapters por banco y versión `parserVersion`; normalizador independiente de los parsers.

### 7.3 Deduplicación

1. **Misma notificación:** huella del `sourcePackage + sbn.key + contenido normalizado` y ventana temporal para actualizaciones.
2. **Mismo movimiento entre canales:** referencia del banco (si existe), cuenta/instrumento, monto, dirección, fecha y comercio; asociar múltiples `SourceObservation` a una sola transacción.
3. **Transferencia entre cuentas propias:** dos movimientos complementarios que el usuario haya definido como cuentas propias y que coincidan en monto y ventana temporal configurable.
4. Si existen múltiples candidatos compatibles, marcar `NEEDS_REVIEW` y evitar fusión automática.
5. Mantener enlaces reversibles; el usuario puede «separar» observaciones fusionadas.

**No utilizar únicamente importe + minuto** como clave de deduplicación.

### 7.4 Confianza

- Alta: fuente autorizada, tipo explícito, importe inequívoco y sentido claro.
- Media: falta identificación de cuenta/comercio o hay posibles duplicados.
- Baja: texto truncado, grupo de mensajes o importe ambiguo.
- Los umbrales se configuran y validan con pruebas; no inventar porcentajes como garantías estadísticas.

## 8. Modelo de datos (Room)

| Tabla / entidad | Campos principales |
|---|---|
| `financial_account` | `id`, `alias`, `provider`, `kind`, `openingBalanceMinor?`, `currency`, `active`, `createdAt` |
| `card_profile` | `id`, `accountId`, `type` (CREDIT/DEBIT), `issuer`, `billingDay?`, `dueDay?`, `creditLimitMinor?` |
| `transaction` | `id`, `accountId?`, `occurredAt`, `observedAt`, `amountMinor`, `currency`, `direction`, `kind`, `categoryId?`, `merchantAlias?`, `status`, `origin`, `createdAt`, `updatedAt`, `deletedAt?` |
| `source_observation` | `id`, `transactionId?`, `sourcePackage`, `eventFingerprint`, `keyHash`, `parserVersion`, `receivedAt`, `confidence`, `evidenceCodes`, `state` |
| `transfer_link` | `id`, `debitTransactionId`, `creditTransactionId`, `linkType`, `confirmationState` |
| `category` | `id`, `name`, `parentId?`, `icon`, `active` |
| `merchant_rule` | `id`, `matchType`, `pattern`, `merchantAlias`, `categoryId`, `priority`, `enabled` |
| `review_queue` | `id`, `observationId`, `reasonCode`, `createdAt`, `resolvedAt?`, `resolution` |
| `capture_settings` | `captureStartedAt`, `pausedAt?`, `permissionState`, `outlookEnabled`, `privacyOptions` |
| `allowed_source` | `packageName`, `displayName`, `enabled`, `provider`, `lastSeenAt?` |
| `change_audit` | `id`, `entityId`, `changeKind`, `timestamp`, `fieldsChanged` (sin secretos) |

**Restricciones:** `UNIQUE(sourcePackage, eventFingerprint)` para la misma observación; índices por fechas y cuenta; claves foráneas activas; transacciones SQLite atómicas; migraciones versionadas y probadas. Para eventos repetidos legítimos, la huella debe incluir identificador/evento inequívoco o contexto suficiente para no colapsarlos.

**Cifrado:** Room sobre SQLite no equivale a cifrado automático. Seleccionar y documentar una estrategia real de cifrado en reposo para la BD (p. ej. SQLCipher compatible con Room) y gestionar secretos con Android Keystore; cifrar también copias de seguridad locales, si se habilitan. No registrar cuerpos de notificaciones en logs. Para MVP, desactivar backup Android por defecto mientras no exista backup seguro implementado.

## 9. Arquitectura y estructura del proyecto

```text
app/
  src/main/java/.../app/             # Application, navegación y DI
core/
  model/                             # Entidades y enums de dominio
  database/                          # Room, DAO, migraciones, cifrado
  security/                          # Keystore, políticas de privacidad
  design-system/                     # Componentes Compose Material 3
  testing/                           # Fixtures, factories
feature/
  onboarding/                         # Permisos y selección de bancos
  capture/                            # NotificationListenerService
  parsers/                            # Contratos y parsers por banco
  ledger/                             # Movimientos y edición
  reconciliation/                     # Deduplicación y revisión
  dashboard/                          # Indicadores, filtros y gráficos
  accounts/                           # Cuentas, billeteras y tarjetas
  settings/                           # Reglas, exportación y borrado
```

- **UI:** Compose + Navigation Compose + ViewModel; estado `StateFlow`, efectos de una sola ejecución.
- **Dominio:** casos de uso puros: `ParseNotification`, `ReconcileObservation`, `CreateTransaction`, `UpdateLedger`, `CalculateInsights`.
- **Datos:** repositorios con Room; sin red ni servicios externos para MVP.
- **DI:** Hilt (o alternativa consistente), sin singletons de estado global mutable.
- **Concurrencia:** callbacks ligeros; trabajo seguro en corrutinas con cola persistente cuando corresponda; evitar bloquear el hilo principal del listener.
- **Recuperación:** reintentos idempotentes después de finalización abrupta del proceso; detectar revocación de permiso.
- **Compatibilidad:** definir `minSdk` y `targetSdk` tras revisar soporte real de bibliotecas, permisos y políticas actuales de Android; probar desde Android 10 hasta versión estable objetivo cuando sea posible. No exigir permiso de notificaciones de publicación (`POST_NOTIFICATIONS`) salvo que la app vaya a mostrar las suyas.

### 9.1 Consideraciones Android

`NotificationListenerService` requiere declaración `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` en el `<service>` y acción `android.service.notification.NotificationListenerService`; **el permiso se otorga en Ajustes por el usuario**, no en diálogo runtime genérico. El servicio no permite extraer contenido que la app origen no publique. Revisar comportamiento de notificaciones sensibles, grupos, actualización, ahorro de batería y restricciones por versión/OEM.

## 10. Pantallas y navegación

1. **Bienvenida y privacidad:** qué puede leer la app, qué no leerá y qué se guarda.
2. **Conexión de notificaciones:** instrucciones para habilitar acceso y fuentes detectadas.
3. **Configuración inicial de cuentas:** Pichincha, Guayaquil, Produbanco débito, Deuna, PeiGo crédito; alias y saldos opcionales.
4. **Inicio/Dashboard:** gastos del mes, ingresos, diferencia neta, deuda PeiGo estimada y movimientos recientes.
5. **Movimientos:** búsqueda, filtros, detalle, procedencia y correcciones.
6. **Por revisar:** candidatos ambiguos, duplicados y transferencias internas por confirmar.
7. **Cuentas y tarjetas:** movimientos por instrumento, fechas de corte/vencimiento y presupuesto disponible estimado.
8. **Categorías y presupuestos:** reglas automáticas y límites por mes.
9. **Ajustes:** fuentes, pausa, privacidad, exportación, eliminación total y diagnóstico.

**UX:** mostrar monto con `$` y dos decimales, distinguir cargo/abono sin basarse solo en color, accesibilidad TalkBack, texto escalable, modo oscuro, estados vacíos y errores comprensibles.

## 11. Requisitos no funcionales (RNF)

| ID | Requisito verificable |
|---|---|
| RNF-01 | Uso 100 % offline para recepción, clasificación, consulta y edición |
| RNF-02 | No solicitar contraseñas bancarias ni guardar PIN/OTP o números completos de tarjeta |
| RNF-03 | Base de datos cifrada en reposo; clave vinculada a Android Keystore |
| RNF-04 | Logs de producción sin montos, beneficiarios, cuentas, títulos/cuerpos de notificación |
| RNF-05 | Proceso de captura y guardado no bloquea UI; tolera ráfagas de notificaciones |
| RNF-06 | Reprocesamiento idempotente tras reinicio o actualización de notificación |
| RNF-07 | Pruebas unitarias de parsers y conciliación, de DAO/migraciones y pruebas instrumentadas del servicio |
| RNF-08 | Pantallas navegables con TalkBack y tamaños de letra grandes |
| RNF-09 | Mensaje visible sobre la imposibilidad de garantizar integridad/saldos bancarios reales |
| RNF-10 | Exportación consciente: advertir que CSV no está cifrado automáticamente |
| RNF-11 | El proceso no hace peticiones a internet; idealmente omitir permiso `INTERNET` en MVP |
| RNF-12 | Disponibilidad de métricas técnicas no sensibles: eventos aceptados, pendientes, descartados y errores |

## 12. Escenarios críticos y criterios de aceptación

### CA-01 — Primera instalación
**Dado** que el usuario instala la app sin historial **cuando** habilita permiso y fuentes a las 12:00 **entonces** no se crean transacciones por notificaciones anteriores ni por notificaciones que ya estaban visibles antes de la activación.

### CA-02 — Produbanco transferencia
**Dada** notificación «Transferencia enviada», «Por $0.01 a la cuenta ... el 10/14/2026 11:24:35» **cuando** se recibe desde el paquete autorizado **entonces** se genera un candidato de salida de `1` centavo con fecha válida, sin guardar identificador completo de cuenta.

### CA-03 — Pichincha transferencia recibida
**Dada** «¡Recibiste 1.00!» **entonces** se detecta entrada de `100` centavos y se solicita clasificación si no se conoce si corresponde a ingreso, reembolso o transferencia propia.

### CA-04 — Deuna pago y recarga
**Dadas** «Pagaste $1,00» y «Recargaste $1,00» **entonces** se generan eventos distintos, y la recarga no se cuenta como gasto de consumo.

### CA-05 — Compra Spotify por correo
**Dada** una notificación Outlook con solo «Consumo tarjeta de débito por USD 3.99» **entonces** puede reconocerse un consumo por 399 centavos, pero el comercio no se completa como «Spotify» salvo que también esté presente en la notificación recibida. No leer automáticamente el correo completo.

### CA-06 — PeiGo crédito
**Dado** un consumo validado de $25.00 **entonces** sube el gasto en 2500 centavos y la deuda PeiGo estimada en 2500; **cuando** posteriormente se paga $25.00 desde una cuenta propia, disminuyen caja y deuda sin agregar otros $25 al gasto.

### CA-07 — Notificaciones duplicadas
**Dado** el mismo evento entregado dos veces por Android **entonces** existe una sola observación válida. **Dadas** notificaciones de app bancaria y Outlook del mismo movimiento con evidencia fuerte **entonces** existe una sola transacción enlazada a dos observaciones.

### CA-08 — Mismo monto repetido
**Dadas** dos compras legítimas de $1.00 con datos diferentes **entonces** deben poder existir ambas; ante ambigüedad no se fusionan de forma irreversible.

### CA-09 — Revocación de permiso
**Dado** que Android revoca acceso **entonces** la pantalla indica «captura desactivada» y no afirma que todos los movimientos hayan sido registrados.

### CA-10 — Privacidad
**Dado** un volcado de logs de producción **entonces** no contiene nombres, cuentas, títulos/cuerpos bancarios, OTP ni importes privados.

### CA-11 — Resumen contable
**Dada** una compra PeiGo de $10, una recarga Deuna de $5 desde Pichincha y un pago de tarjeta por $10 **entonces** «gastos de consumo» muestra $10, no $25.

### CA-12 — Eliminación
**Cuando** se ejecuta eliminar datos con confirmación **entonces** desaparecen movimientos, observaciones y reglas locales; se reinicia el punto de captura sin recuperar historial previo.

## 13. Plan de pruebas

- **Parser fixtures:** casos reales **anonimizados** para Pichincha, Guayaquil, Produbanco, Deuna; casos futuros PeiGo. Variar decimales, emojis, multilineal, texto truncado y fechas.
- **Propiedades monetarias:** `USD 3.99 → 399`, `$1,00 → 100`, `$0.01 → 1` centavos.
- **Idempotencia:** reemitir evento 5 veces y verificar un solo registro.
- **Conciliación:** recargas y pagos cruzados; demostrar que no alteran gastos agregados.
- **Privacidad:** probar que ningún log o tabla almacena contraseñas, OTP o PII no necesaria.
- **Android instrumentado:** permiso concedido/denegado, listener desconectado/reconectado, notificación agrupada, app terminada y reinicio.
- **Regresión:** por cada nuevo formato bancario agregar fixture y caso esperado, antes de habilitar regla en producción.
- **Dispositivos:** al menos un dispositivo real Android y emuladores representativos; validar comportamiento con fabricante y versión reales del usuario.

**Cobertura mínima objetivo:** 100 % de escenarios CA ejecutados; suites de dominio/parsers y conciliación sin fallos; definir umbral cuantitativo de cobertura de código con el equipo, sin usarlo como sustituto de pruebas reales.

## 14. Fases y entregables

| Fase | Alcance | Entregable |
|---|---|---|
| F0 — Bootstrap | Repositorio, Gradle, módulos, CI, diseño y reglas de seguridad | App vacía compilable + pruebas base |
| F1 — Captura | Onboarding, permisos, fuentes y listener | Eventos bancarios nuevos detectables, sin transacciones duplicadas |
| F2 — Parsers | Normalización y reglas de 4 fuentes + bandeja de pendientes | Movimientos reales/pendientes en Room |
| F3 — Libro mayor | Cuentas, clasificación, deduplicación, conciliación | Contabilidad local correcta |
| F4 — Producto | Dashboard, filtros, edición, PeiGo manual, categorías y presupuestos | MVP utilizable sin internet |
| F5 — Robustez | Seguridad, cifrado, pruebas instrumentadas, rendimiento y exportación | APK debug/release firmable, documentación y checklist |
| F6 — PeiGo automático | Incorporar patrón real verificado cuando exista muestra | Parser PeiGo validado y activado |

**Prioridad MVP:** F0–F5. F6 depende de evidencia real.

## 15. Criterio de terminado (Definition of Done)

Cada RF implementado debe cumplir:

1. Código Kotlin con separación UI/dominio/datos y documentación breve de decisiones.
2. Casos unitarios positivos, negativos y de regresión para cualquier parser/regla.
3. Persistencia transaccional, migración Room y manejo de errores validados.
4. Seguridad y privacidad verificadas: no PII innecesaria, sin logs sensibles.
5. Accesibilidad y estados de error/carga/revisión verificados.
6. Pruebas en dispositivo físico para captura real; no declarar soportado un banco sin ensayo.
7. Demostración funcional del caso de aceptación asociado y capturas de la pantalla.

## 16. Instrucciones para el agente de desarrollo

1. **No empezar diseñando solo pantallas:** implementar primero el contrato de eventos, la base de datos, fixtures y casos de aceptación.
2. Crear una tarea por fase; dividir cada RF en unidad de trabajo rastreable (`RF-ID`, implementación, prueba, evidencia).
3. Usar los formatos de notificaciones enumerados como fixtures **anonimizados**, nunca incluir capturas con datos reales en el repositorio público.
4. No fabricar `packageName`, API de banco, cuerpo de correo Outlook ni formato PeiGo. Documentar las incertidumbres y pedir una observación real cuando sea necesario.
5. No usar un LLM para interpretar movimientos durante MVP: las reglas deterministas facilitan auditoría y privacidad.
6. Mantener transacciones de libro mayor separadas de observaciones crudas y de enlaces de conciliación.
7. Antes de etiquetar algo como «gasto», aplicar RN-01 a RN-14 y resolver ambigüedades.
8. Permitir cambiar reglas sin perder el historial ni corromper saldos; auditar las ediciones.
9. Ejecutar suite completa al terminar cada fase; adjuntar resultado de compilación/pruebas en el pull request.
10. Nunca exigir credenciales bancarias ni solicitar permisos que no correspondan a funcionalidades reales.

## 17. Riesgos y medidas

| Riesgo | Mitigación |
|---|---|
| Banco cambia texto de notificaciones | Parsers versionados, fixtures, revisión y actualización |
| Notificación incompleta o agrupada | Pendiente de revisión, no inferir importe ni comercio |
| Alertas simultáneas de dos aplicaciones | Observaciones independientes + reconciliación |
| Corte de captura por Android/batería | Estado visible, reinicio seguro, advertencia de posibles omisiones |
| Transferencias propias contadas como gasto | Tipos financieros separados y confirmación de cuentas propias |
| Acceso de terceros a datos locales | BD cifrada, Keystore, mínimo privilegio, bloqueo biométrico opcional |
| Datos de Outlook sensibles | Deshabilitar por defecto, filtrar por notificación y nunca abrir buzón |
| Saldos aparentemente exactos | Etiqueta **estimado**, saldo inicial opcional, fecha de última observación |
| PeiGo sin formato real | Introducción manual temporal + parser pendiente |

## 18. Pendientes antes de publicar

- [ ] Capturar y anonimizar notificaciones reales de **PeiGo tarjeta de crédito** (consumo y pago).
- [ ] Verificar en dispositivo si los **consumos de tarjeta de débito Produbanco** llegan como notificación directa o solo como correo/Outlook.
- [ ] Descubrir los identificadores de paquete reales de todas las fuentes instaladas.
- [ ] Definir si habilitar Outlook opt-in en MVP o dejarlo para una iteración posterior; **no equivale a leer mensajes completos**.
- [ ] Confirmar política de cifrado elegida y compatibilidad con las versiones Android objetivo.
- [ ] Probar notificaciones reales en un dispositivo y documentar los campos efectivamente expuestos.

---

**Resultado esperado del MVP:** una app Android personal y gratuita que captura notificaciones bancarias nuevas autorizadas, identifica los movimientos detectables, evita duplicaciones, permite corregir incertidumbres y presenta gastos, ingresos y deuda de crédito de forma consistente, sin prometer sincronización bancaria completa.
