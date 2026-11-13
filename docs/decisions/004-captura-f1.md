# ADR-004 — Captura autorizada y durable en F1

## Consentimiento e intervalos

El consentimiento se guarda con fecha UTC y versión, separado del permiso especial de Android, de las fuentes habilitadas y de la intención de capturar. Abrir Ajustes o conceder acceso no activa por sí solo la captura. El usuario debe habilitar al menos una fuente y pulsar Activar.

`captureStartedAt` conserva la primera activación. Cada activación, reanudación o reconexión tras una interrupción abre una sesión nueva. Se compara `StatusBarNotification.postTime`, nunca la fecha de una operación bancaria. Al reiniciar el proceso se cierra la sesión anterior y la captura permanece cerrada hasta recibir la conexión real del listener. No se consulta el inventario de notificaciones activas.

Revocar el permiso cierra la sesión, desactiva la intención de capturar y exige reanudar explícitamente después de concederlo otra vez. Pausas, desconexiones y reinicios dejan un aviso persistente de posibles omisiones. Se comprueba el permiso real tanto al volver a la app como al recibir un callback.

## Fuentes y seguridad

La selección usa aplicaciones reales obtenidas por `PackageManager` mediante visibilidad de actividades launcher. No se solicita QUERY_ALL_PACKAGES ni se adivinan paquetes de bancos. El usuario asigna la entidad al paquete que reconoce. Se registra una huella SHA-256 del certificado local y se contrasta al ingerir eventos. Esto detecta desapariciones o cambios de identidad; no constituye un directorio de certificados bancarios oficiales.

Todas las fuentes comienzan apagadas. La habilitación guarda su propio inicio temporal. Rehabilitar o cambiar de perfil no recupera notificaciones anteriores. Outlook se elige como perfil experimental y requiere una confirmación adicional al habilitarlo; no hay acceso al buzón, Graph, SMS o red.

El callback comprueba primero estado, paquete y fechas; solo entonces copia extras acotados a memoria. Una cola de 128 callbacks evita bloquear el listener. Una segunda comprobación dentro de una transacción SQLite valida consentimiento, permiso observado, sesión y fuente antes de persistir. Los descartes de aplicaciones no habilitadas no guardan su contenido ni identidad.

## Persistencia mínima y separación del dinero

Room v2 incorpora el esquema financiero, sesiones, configuración, fuentes, cola, contadores y auditoría, con claves foráneas e índices. La migración 1→2 conserva la tabla técnica de F0 y se prueba sobre SQLCipher real. No se usa migración destructiva.

F1 no interpreta importes. Una señal conservadora de vocabulario financiero filtra avisos obvios; no es un parser de banco ni una garantía de cobertura. Las publicaciones aceptadas producen observaciones `AWAITING_PARSER` y pendientes `PARSER_NOT_AVAILABLE`, sin transacciones ni postings. Los grupos resumen se descartan sin convertirlos en operaciones.

Los textos de título, cuerpo, extras y líneas se usan temporalmente para esa señal y para calcular una revisión HMAC; no se guardan en BD, cola, auditoría o logs. Las huellas usan una clave Android Keystore independiente del secreto de SQLCipher y componentes prefijados por longitud. La cola cifrada contiene únicamente paquete autorizado, huellas, sesión, origen y fechas.

Una actualización de la misma publicación activa conserva su observación técnica; la revisión es un campo separado. La retirada de una notificación cierra su cursor y permite que una publicación posterior que reutilice la clave sea independiente. Publicación y retirada pasan por la misma cola ordenada. Esta agrupación técnica no decide que dos pagos sean el mismo movimiento: F2/F3 deberán priorizar referencias bancarias y evidencia de negocio para distinguir operaciones legítimas.

`CaptureWorker` drena de forma transaccional e idempotente. WorkManager reintenta sin requisitos de red; iniciar app/listener también recupera colas persistidas. Una entrada ya autorizada puede terminar de procesarse después de una pausa. Un callback perdido antes de persistir no se puede recuperar; el producto no garantiza integridad bancaria.

Las pruebas sintéticas requieren una acción explícita, usan origen `SYNTHETIC`, tienen contadores separados y nunca crean transacciones. El permiso POST_NOTIFICATIONS existe únicamente en el APK instrumentado que publica avisos de prueba, no en el APK principal. Se excluye el servicio foreground de WorkManager porque esta fase usa trabajo diferido normal.

## Evolución y límites

F2 integrará parsers en memoria antes de encolar, ampliando el contrato durable con campos normalizados mínimos mediante una migración versionada. Los metadatos de F1 no permiten reconstruir textos o importes antiguos; no se reprocesará retrospectivamente un historial que no se guardó.

La configuración inicial de cuentas y el ledger funcional se entregan en F3; exportación y borrado completo en F5. Al implementar borrado total se deberá eliminar también el alias HMAC `ec.finanzas.locales.fingerprint.v1`, además de BD, cola, temporales y clave envolvente.

La validación de paquetes y formatos de bancos en un teléfono físico sigue pendiente. Los ensayos automatizados usan la identidad real del APK de pruebas, etiquetada como fuente sintética; no se presenta como una app bancaria.
