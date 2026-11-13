# F3: cuentas y libro mayor

En **Cuentas y tarjetas** o **Configuración inicial**, añade tus instrumentos por alias. No escribas números completos de cuenta o tarjeta. El saldo inicial es el anterior al primer movimiento que registrarás. Vacío significa desconocido; escribir `0` significa cero. Una tarjeta de débito usa la cuenta que creas con ese perfil y no añade un saldo independiente. En crédito, el valor inicial representa deuda.

En **Movimientos**, registra una operación manual con importe positivo y fecha de Guayaquil (`AAAA-MM-DDTHH:MM:SS`). Elige la clasificación:

- **Gasto:** disminuye cuenta/billetera y aumenta consumo.
- **Compra a crédito:** aumenta consumo y deuda, sin restar efectivo.
- **Ingreso externo:** aumenta efectivo e ingreso.
- **Transferencia propia / Recarga de billetera:** selecciona las dos cuentas propias; no aumenta consumo ni ingreso.
- **Pago de tarjeta:** selecciona efectivo y tarjeta; reduce ambos saldos, sin otro gasto.
- **Devolución:** selecciona la compra original de esa cuenta. Puedes registrar devoluciones parciales hasta su importe total.

Las cuentas sin saldo inicial muestran variación registrada; los saldos y deuda siempre son estimados. El resumen actual incluye todo el período registrado. El dashboard mensual, filtros y presupuestos corresponden a la siguiente fase.

En **Por revisar**, completa los candidatos nuevos o las decisiones conservadas de F2. **Guardar borrador** conserva información incompleta sin efectos financieros. **Usar recepción como fecha estimada** es opcional y queda identificado. No se inventa una fecha de operación. Para contabilizar, selecciona cuentas, importe, fecha y clasificación válidos. Una corrección respecto del sentido/instrumento/entidad del aviso requiere confirmación explícita. Los candidatos sintéticos solo sirven para diagnóstico y no se contabilizan.

Si el aviso ya corresponde a una operación registrada, elige **Ya estaba registrado**, ajusta la ventana de minutos y el tipo de relación. Las coincidencias de monto y fecha son sugerencias: comprueba la procedencia y finalidad. Vincular conserva los datos de la operación elegida; puedes editarla después para corregir nota o comercio. Si se trata de otra operación legítima, confírmalo expresamente para conservar ambas.

Para corregir dos movimientos ya registrados, abre **Conciliar operaciones ya registradas** en Movimientos. Elige cuál conservar y la relación. Por ejemplo, una salida marcada provisionalmente como gasto y una entrada Deuna marcada como ingreso pueden pasar a una recarga propia: desaparecen ese gasto e ingreso, y se conserva la variación correcta de banco/billetera.

**Editar movimiento** recalcula los efectos. **Borrar movimiento** lo excluye sin destruir la trazabilidad. **Deshacer última acción** restaura la versión anterior o separa vínculos; si hubo cambios relacionados posteriores, deshazlos primero. Activa **Ver borrados y vínculos anteriores** para acceder a operaciones retiradas. Los movimientos independientes creados después se conservan. Una compra con devoluciones activas exige resolverlas antes de cambiar de cuenta, clase o borrarla.

Archivar una cuenta impide movimientos nuevos, pero conserva su historia y sus totales. Puedes reactivarla y corregir operaciones históricas. Una cuenta con historia conserva entidad y tipo; alias y saldo inicial siguen siendo editables.

PeiGo admite crédito manual; su parser continúa desactivado por falta de muestras reales. Las reglas bancarias y Outlook siguen siendo preliminares. La captura puede tener huecos y no recupera historial. Nunca se garantiza que los saldos coincidan con los del banco.
