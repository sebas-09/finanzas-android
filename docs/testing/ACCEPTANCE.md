# Matriz de aceptación del MVP — F5

Las regresiones locales de las funciones implementadas aprobaron. Las columnas distinguen fixtures y AOSP de la aceptación bancaria física. Ver [F5](F5.md) y [resultados](evidence-f5/results.json). El MVP no se declara aceptado en producción.

| CA | Evidencia automatizada/local | Pendiente externo |
|---|---|---|
| 01 | Consentimiento, sesión nueva, habilitación tardía, pausa y rechazo de publicaciones anteriores; listener real en AOSP y reactivación tras borrado | Entrega real de cada banco y restricciones OEM |
| 02 | Fixture Produbanco de un centavo con fechas y cuenta anonimizada; revisión sin inventar campos | Notificación bancaria real |
| 03 | Fixture Pichincha de 100 centavos con finalidad revisable | Notificación bancaria real |
| 04 | Parser Deuna pago/recarga y libro mayor sin sumar la recarga propia como consumo | Formatos reales y entrega OEM |
| 05 | Outlook parcial, comercio ausente conservado como tal; regla solo con comercio presente | Outlook real, experimental |
| 06 | Compra y pago de PeiGo manuales, deuda y consumo independientes, reversión/edición | Automatización PeiGo desactivada hasta F6 y muestras reales |
| 07 | Callback repetido, actualizaciones, evidencia entre canales y ocho hijos agrupados con resumen descartado | Ráfagas y agrupaciones de apps bancarias reales |
| 08 | Dos operaciones legítimas similares conservadas; ambigüedad exige decisión, fusión reversible | Formatos bancarios ambiguos reales |
| 09 | Denegación/revocación/reconexión del listener; captura independiente del permiso para avisos propios | Restricciones particulares del fabricante |
| 10 | Manifest sin INTERNET, backup/transferencia excluidos, copia cifrada ilegible, revisión de logging/dependencias y CSV consciente | Revisión de captura/logs con formatos físicos reales |
| 11 | Consumo excluye pagos, recargas y traslados propios; devoluciones vinculadas; resumen y CSV usan categoría efectiva | Comparación con extractos reales; saldos siguen estimados |
| 12 | Borrado desde botón real en API 29/36: proceso cerrado, archivos/marcadores anteriores ausentes, BD y HMAC renovados, reglas personalizadas eliminadas, captura sin consentimiento y frontera nueva que rechaza un aviso viejo. CSV externo intacto | Repetir en teléfono físico y recorrido hablado con TalkBack |

Las suites generales omiten un probe por módulo de forma intencional; sus fases se ejecutan por separado. Los ensayos destructivos rechazan dispositivos físicos y usan AVDs dedicados. El fallo inicial de un fixture de reactivación se corrigió y quedó registrado en F5.

La firma de producción está preparada, pero el usuario aplazó aportar el keystore. Quedan pendientes generar/verificar/instalar el release firmado y custodiar la clave. La CI remota no se ejecutó desde esta entrega local.
