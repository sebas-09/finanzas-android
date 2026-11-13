# Plan de implementación — Finanzas Personales Android

Fecha: 2026-11-09. Fuente: `SPEC_FINANZAS_ANDROID.md`, versión 1.0.

Este documento transforma el spec en un backlog ejecutable. No inicia el desarrollo ni modifica el alcance funcional. Las decisiones propuestas se distinguen de los requisitos ya cerrados.

## 1. Resultado y alcance

Entregar un APK Android nativo, firmado e instalable, que funcione sin internet, registre movimientos nuevos desde notificaciones autorizadas y permita registro manual, revisión, corrección y consulta financiera local.

El MVP comprende F0–F5: Pichincha, Guayaquil, Produbanco y Deuna según formatos realmente verificables; PeiGo manual; cuentas, tarjeta, dashboard, presupuestos, reglas, exportación y borrado. La automatización PeiGo corresponde a F6 y depende de muestras reales anonimizadas.

No incluye login, servidores, lectura de SMS o buzones, historial previo, conexión bancaria ni publicación en Play Store. El entregable es un APK distribuible directamente; una publicación en tienda necesitaría su propio trabajo de políticas y distribución.

## 2. Decisiones de arranque y dependencias externas

| Tema | Propuesta de implementación | Validación necesaria |
|---|---|---|
| Ubicación | Crear proyecto independiente en `finanzas-android/` junto al spec | Comprobar instrucciones locales antes de crear archivos |
| Stack | Kotlin, Compose Material 3, Navigation Compose, Room, coroutines/Flow y Hilt | Fijar versiones compatibles en catálogo Gradle durante F0 |
| Android | Proponer `minSdk 29` para Android 10; fijar `compileSdk`/`targetSdk` según versión estable y herramientas disponibles al arrancar | Compilar y probar bibliotecas y permisos; registrar versiones exactas, sin suponer que ya fueron verificadas |
| Cifrado | SQLCipher compatible con Room; secreto aleatorio de BD protegido mediante Android Keystore | Prueba instrumentada de apertura, reinicio, migración y pérdida de clave antes de guardar datos reales |
| Outlook | Incluir opt-in experimental en F2, apagado por defecto; solo extras visibles de notificaciones nuevas | Validar filtrado financiero, agrupaciones y datos disponibles; nunca acceder al correo completo |
| Fuentes | Descubrimiento de paquetes instalados mediante PackageManager y selección explícita | Verificar en teléfono paquetes reales e identidad; declarar visibilidad mínima necesaria, sin permisos generales innecesarios |
| Distribución | APK debug para pruebas y APK release firmado para entrega | Definir propietario y custodia de clave de firma antes de F5 |
| PeiGo automático | Adaptador desactivado hasta validar ejemplos de consumo y pago | Muestras reales anonimizadas; no bloquea el MVP manual |
| Produbanco débito | Soporte automático condicionado a campos publicados realmente | Distinguir notificación directa de aviso Outlook y documentar cobertura comprobada |

La ausencia de muestras reales no impide construir dominio, parsers preliminares ni modo manual. Sí impide declarar validado el soporte automático de ese formato. La falta de dispositivo físico o de una clave de firma custodiada bloquea la aceptación final correspondiente.

## 3. Orden técnico y contratos que deben cerrarse primero

### 3.1 Dependencias entre módulos

`app` compone navegación e inyección. Las features dependen de contratos de dominio; Room y los detalles Android permanecen en adaptadores de datos/plataforma. El dominio no depende de Compose, NotificationListenerService ni SQLCipher.

Mantener la estructura modular del spec y documentar interfaces de repositorios y casos de uso antes de conectar las pantallas. Usar `core/testing` para fixtures y utilidades de pruebas, sin incluir dependencias de prueba en producción.

Flujo principal:

```text
Listener → validación de fuente y sesión → captura mínima durable
         → parser/normalizador → observación
         → deduplicación/conciliación → transacción y efectos contables
         → consultas Room/Flow → ViewModel → Compose
```

### 3.2 Ajustes necesarios al modelo propuesto

Resolver en F0 y materializar en F1/F3, mediante decisiones documentadas:

1. **Sesiones de captura:** `captureStartedAt` por sí solo no representa pausas, revocaciones o habilitación tardía de una fuente. Añadir intervalos/sesiones y `enabledSince` por fuente. Comparar `sbn.postTime` con la ventana autorizada; usar la fecha de operación únicamente para contabilidad, nunca para decidir si una notificación es nueva. Al reanudar no procesar callbacks de publicaciones anteriores a esa reanudación. No invocar `getActiveNotifications()`.
2. **Ingesta durable:** definir una cola cifrada de entradas mínimas aún no procesadas, si se requiere reintento. Guardar lo indispensable con retención acotada y purgarlo tras normalizar. Reintentar con corrutinas y, cuando corresponda, WorkManager; no usarlo para recuperar notificaciones que Android nunca entregó. Un callback puede perderse antes de persistir: documentar ese límite.
3. **Observación y revisión:** candidatos sin importe o cuenta no deben forzarse dentro de una transacción contabilizable. Persistir los campos normalizados necesarios para revisarlos, versión del parser y razones de incertidumbre.
4. **Efectos contables:** usar una representación única de efectos por cuenta, por ejemplo `account_posting`, vinculada a la operación. Transferencias, recargas y pagos generan efectos complementarios sin duplicar consumo. `transfer_link` conserva la conciliación; no debe aplicar efectos adicionales que ya estén contabilizados.
5. **Tarjeta:** definir deuda inicial opcional y convención de signo para pasivos. Sin base inicial conocida, mostrar variación de deuda registrada y advertencia de deuda total desconocida. Separar saldo de deuda, consumo del ciclo y límite disponible estimado.
6. **Datos faltantes en tablas:** incorporar presupuesto por categoría/mes, ciclos de tarjeta, vínculos de devolución/reverso, estado de prueba, historial de cambios suficiente para deshacer clasificación y retención del diagnóstico opcional.
7. **Reprocesamiento:** cambios de reglas producen propuestas o reclasificaciones explícitas y auditadas; no sobrescribir silenciosamente correcciones manuales ni reinterpretar todo el historial al abrir la app.
8. **Claves y privacidad:** HMAC para huellas de datos previsibles; claves separadas por propósito. Desactivar backups y extracción de datos según APIs objetivo, incluyendo reglas aplicables de transferencia de dispositivo. No guardar texto completo por defecto.

### 3.3 Invariantes financieras

- Todos los importes se calculan en centavos `Long`; conversión mediante decimal exacto y validación de rango. Nunca usar coma flotante para dinero.
- Solo movimientos confirmados y vigentes contribuyen a indicadores definitivos. Pendientes, ignorados, borrados y pruebas quedan fuera.
- Débito comercial: gasto y reducción de efectivo. Crédito: gasto y aumento de deuda.
- Pago de tarjeta: reducción de efectivo y deuda, sin nuevo gasto. Recarga/transferencia propia: traslado, sin ingreso ni consumo.
- Transferencias a terceros o entradas de finalidad incierta requieren clasificación; la dirección monetaria no determina por sí sola ingreso o gasto.
- Devoluciones y reversos se vinculan al original y se contabilizan una sola vez; validar parciales y totales.
- Editar, borrar, restaurar clasificación o separar una fusión recalcula los efectos atómicamente.
- Sin saldo inicial, una cuenta muestra variación neta registrada. Las fechas se almacenan en UTC y los períodos se delimitan en `America/Guayaquil`.

## 4. Fases y backlog ejecutable

Las fases conservan los identificadores del spec. Se adelanta el cifrado desde F5 a F0/F1 para que la primera persistencia real ya esté protegida. Cada fase termina con compilación y suite completa de las pruebas implementadas hasta ese punto.

### F0 — Base técnica, contratos y prueba de cifrado

Dependencias: ninguna. Esfuerzo orientativo: 4–6 días efectivos.

- [x] F0.01 Crear proyecto, Gradle Wrapper, catálogo de versiones y módulos del spec; configurar Hilt, compilación y lint.
- [x] F0.02 Definir contratos de evento, candidatos, estados, repositorios, sesiones y efectos contables; documentar las decisiones de la sección 3.
- [x] F0.03 Crear fixtures sintéticos/anonimizados y tabla de resultados esperados para CA-01–CA-12.
- [x] F0.04 Probar Room + SQLCipher + Keystore; desactivar backups y revisar manifest final sin permiso INTERNET.
- [x] F0.05 Definir navegación de las nueve pantallas y componentes base; prever revisión, vacío, error, texto grande y modo oscuro.
- [x] F0.06 Configurar CI para compilación, lint y pruebas JVM; separar pruebas instrumentadas que requieran emulador/dispositivo.

Aceptación: app mínima compilable; contrato aprobado mediante revisión técnica; prueba de BD cifrada que reabre después de reiniciar el proceso; ninguna persistencia financiera en claro. Versiones y limitaciones registradas.

**Estado F0 (2026-11-09): completada.** Build, lint y pruebas JVM/Android 10 y 16 aprobados; [evidencia](finanzas-android/docs/testing/F0.md). CI remota preparada, sin publicación del repositorio.

### F1 — Persistencia segura, consentimiento y captura

Depende de F0. Esfuerzo orientativo: 6–9 días.

- [x] F1.01 Implementar esquema Room inicial, DAOs, índices, claves foráneas y transacciones; exportar esquema y preparar pruebas de migraciones.
- [x] F1.02 Construir onboarding y consentimiento; abrir ajustes de acceso a notificaciones y consultar estado efectivo al volver.
- [x] F1.03 Descubrir/seleccionar fuentes reales; todas deshabilitadas inicialmente; persistir activación por fuente.
- [x] F1.04 Implementar listener con declaración y binding correctos; leer extras disponibles, filtrar paquetes y sesiones, y evitar trabajo pesado en callback.
- [x] F1.05 Implementar pausa, reanudación, revocación, reconexión y procesamiento durable idempotente; nunca recuperar historial activo.
- [x] F1.06 Añadir diagnóstico técnico sin contenido privado y modo sintético aislado de estadísticas reales.

Aceptación: CA-01 y CA-09 pasan en dispositivo; fuente no autorizada no persiste contenido; pausa y habilitación tardía no rellenan historial; reinicio reprocesa únicamente entradas ya persistidas y autorizadas. La captura no depende de POST_NOTIFICATIONS.

**Estado F1 (2026-11-09): implementación y pruebas automatizadas aprobadas en Android 10 y 16.** [Evidencia](finanzas-android/docs/testing/F1.md). Aceptación con aplicaciones bancarias en teléfono físico pendiente; ningún banco se declara validado.

### F2 — Normalización, adaptadores y candidatos revisables

Depende de F1; los parsers puros pueden prepararse con los contratos de F0. Esfuerzo orientativo: 6–9 días.

- [x] F2.01 Implementar normalización monetaria exacta, fecha declarada/recepción, moneda, comercio y sentido; preservar ambigüedad cuando la fecha no sea inequívoca.
- [x] F2.02 Crear adaptadores versionados de Pichincha, Guayaquil, Produbanco y Deuna con los patrones del spec y fixtures negativos.
- [x] F2.03 Implementar reglas explicables de confianza, razones de rechazo y candidatos incompletos sin inventar campos.
- [x] F2.04 Crear bandeja inicial para confirmar, completar o ignorar candidatos; confirmar una transacción requiere importe, cuenta y clasificación válidos.
- [x] F2.05 Añadir Outlook experimental con habilitación explícita y filtrado conservador; pruebas de agrupaciones y contenido truncado.
- [x] F2.06 Crear contrato PeigoParser desactivado; la ausencia de formato real mantiene revisión o entrada manual.

Aceptación: CA-02–CA-05 pasan con fixtures; 0.01 → 1, 1,00 → 100 y 3.99 → 399; fechas y números de cuenta no se confunden con importes. No atribuir Spotify si el comercio no aparece. Distinguir cobertura preliminar de validada en teléfono.

**Estado F2 (2026-11-09): implementada y verificada con fixtures y emuladores Android 10/16.** [Evidencia](finanzas-android/docs/testing/F2.md). Cobertura bancaria preliminar; validación física pendiente. La confirmación conserva decisiones, sin asientos ni efectos contables hasta F3.

### F3 — Libro mayor, deduplicación y conciliación

Depende de F2. Esfuerzo orientativo: 8–12 días.

- [x] F3.01 Implementar cuentas, billetera, tarjeta débito/crédito, alias, archivo y saldos/deuda iniciales opcionales.
- [x] F3.02 Implementar casos de uso financieros y consultas derivadas según RN-01–RN-14; persistir operación, efectos y vínculos en una sola transacción SQLite.
- [x] F3.03 Resolver callbacks repetidos y actualizaciones de una misma notificación; evitar duplicar observaciones o aplicar efectos dos veces.
- [x] F3.04 Conciliar entre canales usando referencia y contexto fuerte; mantener observaciones independientes, decisiones explicables y separación reversible.
- [x] F3.05 Conciliar transferencias propias, recargas y pagos de tarjeta; ante varios candidatos compatibles, enviar a revisión.
- [x] F3.06 Implementar registro/edición manual, borrado lógico, anotaciones, devoluciones/reversos y auditoría local mínima; recalcular al cambiar cuenta, importe o clasificación.
- [x] F3.07 Resolver pendientes y deshacer clasificaciones sin alterar silenciosamente decisiones manuales anteriores.

Aceptación: CA-06–CA-08 y CA-11 pasan; cinco entregas del mismo evento producen un solo efecto; dos compras legítimas iguales siguen siendo posibles. Confirmar tarde una transferencia previamente clasificada corrige agregados sin sumar otro movimiento financiero.

**Estado F3 (2026-11-10). Implementada y verificada con fixtures, SQLCipher y emuladores Android 10/16.** [Evidencia](finanzas-android/docs/testing/F3.md). Compra, recarga y pago de tarjeta conservan efectos idempotentes y conciliación reversible. APK debug `0.4.0-f3`, con la firma de F2. Validación bancaria física pendiente. F4 continúa con dashboard mensual, filtros, ciclos, categorías, presupuestos y accesibilidad.

### F4 — Producto completo y accesibilidad

Depende de F3. Esfuerzo orientativo: 7–10 días.

- [x] F4.01 Dashboard mensual: gastos, ingresos externos, balance, deuda/variación estimada y movimientos recientes.
- [x] F4.02 Lista, búsqueda, filtros y detalle con procedencia, explicación de confianza y correcciones.
- [x] F4.03 Completar revisión y cuentas/tarjetas: PeiGo manual, corte, vencimiento, consumo del ciclo, pagos y límite opcional.
- [x] F4.04 Implementar categorías, comercios y reglas editables con prioridad y deshacer clasificación.
- [x] F4.05 Implementar presupuestos mensuales y alertas locales opt-in; pedir POST_NOTIFICATIONS solo cuando se habiliten avisos propios en versiones que lo requieran. Denegarlo no bloquea captura.
- [x] F4.06 Completar ajustes de captura, privacidad y diagnóstico; mostrar última actividad sin revelar mensajes.
- [ ] F4.07 Verificar TalkBack, texto grande, contraste, modo oscuro y estados de carga/vacío/error; montos con signo/texto además de color.

Aceptación: CU-01–CU-10 recorribles; ningún indicador mezcla pruebas o pendientes con datos definitivos; navegación y gestión financiera funcionan en modo avión. Mostrar siempre límites de cobertura y saldos estimados.

**Estado F4 (2026-11-11): funciones F4.01–F4.06 implementadas y verificadas.** F4.07 parcialmente verificada: contraste, texto al 200 %, modo oscuro y orientación revisados en emulador. TalkBack pendiente porque el servicio no está disponible. La validación bancaria/OEM física sigue pendiente. Ver resultados y limitaciones en [verificación F4](../testing/F4.md).

### F5 — Exportación, borrado, robustez y APK

Depende de F4. Esfuerzo orientativo: 6–9 días.

- [x] F5.01 Exportar CSV únicamente por acción explícita mediante selector de documento del sistema, sin permiso general de archivos; advertir que el archivo exportado está en claro. Escapar delimitadores, saltos y celdas interpretables como fórmulas.
- [x] F5.02 Implementar eliminación total con confirmación: detener captura, cerrar BD, borrar BD/WAL/SHM, cola, temporales, reglas y claves propias; recrear estado seguro. El nuevo punto de captura se fija al reactivar con consentimiento. Aclarar que archivos CSV guardados por el usuario quedan fuera del almacenamiento controlado por la app.
- [x] F5.03 Auditar manifest fusionado y dependencias: sin INTERNET, sin logs sensibles ni SDK de analítica; comprobar que una BD copiada no se lee sin clave.
- [ ] F5.04 Ejecutar matriz Android: permiso denegado/revocado, reconexión, reinicio, proceso terminado, ráfagas, agrupaciones y actualizaciones; documentar comportamientos OEM.
- [x] F5.05 Probar actualización sobre instalación anterior conservando datos y clave; validar migraciones, falta de espacio, fallos de persistencia y clave inaccesible sin reset destructivo silencioso.
- [ ] F5.06 Ejecutar CA-01–CA-12, pruebas instrumentadas y revisión de accesibilidad; comprobar captura real por cada formato que se declare soportado.
- [ ] F5.07 Generar APK debug y release firmado, verificar firma y checksum SHA-256, instalar release limpio y como actualización; proteger keystore y contraseñas fuera del repositorio.
- [x] F5.08 Entregar instrucciones de instalación, permisos, cobertura validada, limitaciones, diagnóstico y mantenimiento de parsers.

Aceptación: todos los CA ejecutados y aprobados; ningún defecto bloqueante de integridad o privacidad; APK release firmado e instalado en teléfono real. Si no existe evidencia bancaria real para un formato, se entrega marcado como preliminar/no validado.

**Estado F5 (2026-11-12): funciones locales implementadas y verificadas.** Exportación CSV con selector real, borrado confirmado con renovación de claves, actualización con datos conservados, fallos de persistencia y auditoría de privacidad aprobados en AOSP Android 10/16; 51 pruebas JVM, 26 de BD y 11 de app por API (más un probe omitido por suite y ejecutado por separado). F5.04 y F5.06 siguen parcialmente verificadas: faltan bancos/OEM reales, TalkBack hablado y reinicio Android mediante ADB sin incidencias; el reinicio en frío de API 29 conservó datos tras recuperar el transporte. F5.07 conserva pendiente el APK release firmado e instalado: el usuario eligió proporcionar su keystore después; la configuración de firma y el build R8 sin firma ya están preparados. El límite de páginas SQLCipher simula falta de capacidad sin llenar el volumen. Ver [resultados y límites F5](../testing/F5.md). No se declara aceptado el MVP en producción.

### F6 — Automatización PeiGo

Depende de F5 y de muestras reales anonimizadas. Esfuerzo orientativo: 2–4 días después de obtener evidencia suficiente.

- [ ] F6.01 Verificar paquete, producto de crédito y extras reales de consumo/pago; incluir reversos si existe muestra.
- [ ] F6.02 Añadir fixtures y parser versionado sin inferir emisor, importes o campos ocultos.
- [ ] F6.03 Probar compra → deuda → pago desde cuenta propia, deduplicación y convivencia con registros manuales.
- [ ] F6.04 Habilitar soporte verificado y actualizar documentación de cobertura.

Aceptación: CA-06 pasa con formato validado en teléfono y regresión contable completa. No retrasar el MVP por esta fase.

## 5. Trazabilidad de requisitos funcionales

La evidencia de cada RF se adjunta a su tarea. La fase indica dónde se entrega el requisito; las comprobaciones pueden repetirse en F5.

| RF | Tarea principal | Prueba/evidencia esperada |
|---|---|---|
| 001 | F1.02 | Onboarding y consentimiento en español |
| 002 | F1.02, F1.05 | Concesión/revocación y retorno desde Ajustes; CA-09 |
| 003 | F1.04–05 | Fronteras temporales, sesiones; CA-01 |
| 004 | F1.03 | Ninguna fuente activa sin selección |
| 005 | F1.05 | Pausa/reanudación sin relleno |
| 006 | F1.05, F4.06 | Estados de servicio y fuente ausente |
| 007 | F0.04, F5.03 | Manifest fusionado con permisos mínimos |
| 008 | F1.04 | Extras, multilineal, grupos y callbacks |
| 009 | F1.03–04, F2.03 | Paquetes no permitidos y alertas no financieras |
| 010 | F2.01 | Formatos monetarios, UTC/Guayaquil y comercio |
| 011 | F1.01, F5.03 | Retención mínima, diagnóstico opcional y purga |
| 012 | F2.01 | Centavos exactos y límites de rango |
| 013 | F2.01 | Fecha declarada válida y recepción separadas |
| 014 | F3.03 | Repetición, actualización y grupos; CA-07 |
| 015 | F2.03, F4.06 | Razones técnicas sin contenido privado |
| 016 | F0.02, F3.02 | Todos los tipos y sus efectos |
| 017 | F3.02 | Flujo/categoría/instrumento separados; CA-11 |
| 018 | F4.04 | Reglas persistentes y prioridad determinista |
| 019 | F3.04 | Una operación, múltiples observaciones; CA-07 |
| 020 | F3.05 | Transferencia propia y candidatos ambiguos |
| 021 | F3.02, F3.05 | Pago reduce caja/deuda sin gasto; CA-06 |
| 022 | F3.02 | Compra crédito aumenta gasto/deuda; CA-06 |
| 023 | F3.05 | Recarga con origen conocido/desconocido; CA-04 |
| 024 | F3.06 | Reembolso/reverso parcial y total vinculado |
| 025 | F2.03, F3.07 | Estados y razones de confianza explicables |
| 026 | F3.04 | Compras repetidas legítimas; CA-08 |
| 027 | F3.01 | Crear/editar/archivar; saldo inicial opcional |
| 028 | F3.06 | CRUD manual y recálculo atómico |
| 029 | F4.01 | Agregados del mes y exclusiones; CA-11 |
| 030 | F4.02 | Combinación de filtros y límites de fecha |
| 031 | F4.03 | Corte, vencimiento, ciclos y deuda estimada |
| 032 | F4.05 | Umbrales y permiso de avisos independiente |
| 033 | F4.02 | Búsqueda por comercio/alias sin PII |
| 034 | F5.01 | CSV explícito, aviso y apertura de archivo |
| 035 | F5.02 | Borrado, reinicio y nuevo consentimiento; CA-12 |
| 036 | F4.06 | Fuentes y última actividad sin texto |
| 037 | F1.06 | Datos sintéticos etiquetados y aislados |
| 038 | F4.01, F4.06 | Cobertura y estimaciones visibles |
| 039 | F2.04, F3.07 | UNKNOWN excluido hasta confirmación |
| 040 | F4.04, F3.07 | Reglas, nombres y deshacer clasificación |

## 6. Verificación transversal

| Área | Comprobación | Requisitos |
|---|---|---|
| Offline | Capturar, revisar, editar y consultar en modo avión; sin permiso INTERNET | RNF-01, RNF-11 |
| Privacidad | Auditar BD, cola, logs, exportación y permisos; no credenciales ni OTP | RNF-02–04, RNF-10; CA-10 |
| Resiliencia | Reinicio, actualización, duplicados, ráfagas y fallos de BD | RNF-05–07 |
| UX | TalkBack, tipografía ampliada, límites de cobertura y saldos estimados | RNF-08–09 |
| Diagnóstico | Contadores aceptados/pendientes/descartados/errores sin datos bancarios | RNF-12 |

Pruebas JVM: normalización, parsers, límites monetarios, reglas, conciliación y agregados. Pruebas instrumentadas: Room/SQLCipher, claves, migraciones y servicio. Pruebas UI: onboarding, revisión, edición y navegación accesible. Pruebas físicas: fuentes bancarias, restricciones Android/OEM e instalación release.

Cada formato nuevo necesita fixture anonimizado y prueba de regresión antes de activarse. La cobertura del código se acuerda en F0; el criterio obligatorio es ejecutar el 100 % de CA-01–CA-12, sin sustituir pruebas físicas por fixtures.

## 7. Estimación y ruta crítica

Estimación inicial para una persona con experiencia Android, dedicación completa y dispositivo disponible:

| Fase | Días efectivos |
|---|---:|
| F0 | 4–6 |
| F1 | 6–9 |
| F2 | 6–9 |
| F3 | 8–12 |
| F4 | 7–10 |
| F5 | 6–9 |
| Total MVP | 37–55 |

Equivale aproximadamente a 8–11 semanas laborables sin esperas externas. Reservar 20 % adicional por formatos bancarios, compatibilidad y revisiones sitúa la planificación en unas 9–14 semanas. F6 añade 2–4 días tras conseguir muestras; no incluye la espera por ellas. Son rangos de planificación, no una fecha comprometida; recalibrar después de F1 y de la primera captura real.

Ruta crítica: contratos y cifrado → captura autorizada → parsers y candidatos → libro mayor/conciliación → producto completo → validación física y APK firmado. Preparar fixtures, documentación y revisión de navegación puede avanzar cuando los contratos estén fijados.

Hitos demostrables:

1. **H0 / F0:** app vacía compilable y cifrado probado.
2. **H1 / F1:** una notificación nueva autorizada llega al pipeline; las anteriores se descartan.
3. **H2 / F2:** ejemplos del spec producen candidatos correctos y revisables.
4. **H3 / F3:** compra, recarga y pago de tarjeta no duplican gasto.
5. **H4 / F4:** uso completo offline con PeiGo manual y presupuestos.
6. **H5 / F5:** APK release firmado, instalado y acompañado de evidencia.

## 8. Evidencias y condiciones de entrega

Organizar dentro del proyecto futuro:

```text
docs/decisions/           decisiones Android, contabilidad, cifrado y retención
docs/testing/            matriz RF/RNF/CA, dispositivo, versión y resultados
docs/coverage/           formatos y fuentes: preliminar/validado/no disponible
docs/user/               instalación, permisos, límites y uso
core/testing/            fixtures anonimizados y resultados esperados
artifacts/               APK y checksums generados; fuera del historial de código
```

Por cada tarea registrar: ID, RF/RN/CA asociados, dependencias, implementación, pruebas, evidencia y estado. Las evidencias y capturas nunca contendrán datos financieros personales reales.

El MVP se considera terminado cuando F0–F5 están completas, CA-01–CA-12 aprobados, RF-001–RF-040 trazados, pruebas sin fallos bloqueantes, captura real documentada por formato declarado y APK release firmado instalado correctamente. Entregar código fuente, instrucciones de compilación, APK, checksum, resultados de pruebas y limitaciones conocidas. No incluir claves de firma ni secretos en el repositorio.

Primera unidad de trabajo al iniciar desarrollo: **F0.01–F0.04**, proyecto compilable con contratos financieros, fixtures y prueba de cifrado; después habilitar captura real.
