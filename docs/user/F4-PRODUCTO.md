# F4 — Resumen, organización y presupuestos

En Inicio, selecciona un mes como `2026-11`. El resumen muestra consumo neto, ingresos externos y su diferencia. Las fechas corresponden a Guayaquil. Una devolución reduce consumo en el mes en que se registra y usa la categoría actual de su compra original. Los saldos al cierre incluyen el saldo inicial y los movimientos registrados hasta el final del mes seleccionado. Una cuenta sin saldo inicial muestra variación registrada. Todos los saldos y deudas son estimados.

En Movimientos, busca por comercio o alias de cuenta. Abre los filtros para combinar rango de fechas inclusive, entidad, cuenta o tarjeta, instrumento, categoría, origen, estado y tipo. Puedes limpiar todos los filtros y cargar más resultados. El detalle presenta procedencia, adaptador, recepción y razones de extracción. Las pruebas sintéticas se mantienen fuera del libro financiero.

En Cuentas y tarjetas, configura corte, vencimiento y límite de crédito opcional. El ciclo incluye el día de corte y comienza al día siguiente del corte anterior. Los días inexistentes se ajustan al último día del mes. El vencimiento estimado es el primer día configurado posterior al corte. Consumo neto y pagos del ciclo se muestran por separado. El disponible se calcula con límite menos deuda estimada y requiere una deuda inicial conocida. Estas convenciones deben compararse con las del emisor.

En Categorías y presupuestos puedes añadir, renombrar y archivar categorías. Archivar conserva la historia. La regla inicial de Spotify necesita que el comercio ya esté presente en la operación. Puedes configurar reglas de coincidencia exacta o de texto contenido, alias, categoría, prioridad y activación. Se ignoran mayúsculas, acentos y espacios repetidos. La mayor prioridad gana y los empates se resuelven por identificador estable.

Las reglas sugieren categoría al crear operaciones. Elegir una categoría o Sin categoría permite decidirla manualmente. Usar sugerencia aplica también el alias propuesto. Las operaciones ya registradas permiten aplicar una regla desde su detalle. Deshacer última acción restaura la clasificación anterior. Editar una regla conserva las decisiones de movimientos existentes.

Cada presupuesto corresponde a una categoría y un mes. Configura límite y umbral de 1 a 100 %. El consumo considera gastos y compras a crédito, y resta devoluciones. Los movimientos propios y los pendientes se excluyen. Puedes editar o eliminar el presupuesto sin modificar las operaciones.

Las alertas se activan por presupuesto. Android 13 o superior solicita permiso únicamente desde Permitir avisos de presupuesto. También puedes abrir los ajustes del sistema y actualizar el estado del permiso. La denegación permite seguir capturando, registrando y consultando presupuestos. Los avisos no incluyen importes, cuentas ni nombres de categorías.

Las alertas evalúan el mes actual al cambiar el libro y periódicamente mientras existan presupuestos con alertas activadas. WorkManager utiliza un intervalo mínimo de 15 minutos y Android puede retrasarlo. Cada presupuesto registra el umbral y el límite ya notificados para evitar repeticiones. Cambiar límite o umbral reinicia esa evaluación. El estado en pantalla se actualiza al consultar los datos.

En Ajustes y Conexión de notificaciones siguen disponibles fuentes, pausa, pruebas y diagnóstico. La cobertura bancaria es preliminar. PeiGo admite crédito manual y su parser continúa desactivado. La exportación y el borrado completo corresponden a F5.
