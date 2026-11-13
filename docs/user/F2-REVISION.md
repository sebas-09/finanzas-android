# F2: extracción y revisión

[APK debug 0.3.0-f2](../../artifacts/finanzas-locales-f2-debug.apk) · [SHA-256](../../artifacts/finanzas-locales-f2-debug.apk.sha256). Para actualizar F1, instala el APK sobre la instalación existente y conserva sus datos. Se mantiene el mismo firmante.

En **Por revisar**, abre **Revisar candidato** para consultar importe, entidad, fecha de operación/recepción y las razones de revisión. No se muestran textos bancarios completos, destinatarios ni números de cuenta.

1. Crea una cuenta por alias en el formulario al final de la bandeja si aún no existe. Elige la entidad y el instrumento correctos; no escribas números de cuenta.
2. Completa el importe en USD (`3,99` o `3.99`), selecciona cuenta, sentido y clasificación.
3. **Guardar cambios** conserva un borrador pendiente. **Confirmar candidato** valida los campos y guarda tu decisión. **Ignorar candidato** pide confirmación y lo retira de pendientes conservando la decisión.

Los candidatos confirmados se conservan para F3. En esta entrega no se contabilizan movimientos, gastos, saldos ni deuda. Una recarga no se convierte automáticamente en gasto; una transferencia recibida no se convierte automáticamente en ingreso.

La bandeja muestra hasta 30 pendientes recientes, con detalle plegado. Al resolverlos aparecen los anteriores. Compara candidatos parecidos: una revisión de notificación puede describir la misma operación. La conciliación llegará en F3.

La cobertura de Pichincha, Guayaquil, Produbanco y Deuna es preliminar y solo corresponde a los ejemplos del spec. Outlook continúa experimental y requiere selección explícita en Conexión/Ajustes. Solo usa el texto que Android entrega en la notificación. Sin evidencia del comercio no se atribuye Spotify u otro nombre. PeiGo permanece desactivado.

Los metadatos capturados en F1 no se pueden reinterpretar: nunca se guardó el texto completo. Pueden completarse manualmente o ignorarse. Las pruebas internas aparecen identificadas como sintéticas.

Se requiere validación en teléfono con muestras reales anonimizadas antes de declarar soporte de un formato bancario. Conserva las limitaciones de captura de F1: no hay historial, la captura puede tener huecos y depende del acceso efectivo a notificaciones.
