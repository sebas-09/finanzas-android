# F2: candidatos antes de la contabilización

Los adaptadores interpretan únicamente los extras de notificaciones de fuentes autorizadas por F1. El texto permanece en RAM. Se guarda un candidato mínimo en la misma transacción que la cola cifrada: centavos, sentido, tipo, fecha declarada cuando es inequívoca, comercio explícito saneado, versión y razones técnicas.

Cada adaptador se identifica con la entidad elegida por el usuario y su versión; esa selección no certifica el formato real del banco. Pichincha, Guayaquil, Produbanco y Deuna tienen cobertura preliminar basada en el spec. Outlook solo opera si el canal fue habilitado expresamente, nunca abre correos ni atribuye un banco por el remitente. PeiGo rechaza todos los formatos hasta disponer de muestras reales.

Se admiten importes enteros, dos decimales y agrupación inequívoca con separadores distintos para miles/decimales. Se rechazan signos, fracciones de centavo, cero, desbordamientos y agrupadores ambiguos como `1,000`. Importes contradictorios entre extras producen un candidato sin importe. No se usan números de cuenta ni fechas como importes. La fecha de recepción se conserva separada; solo se interpreta la fecha de Produbanco del ejemplo del spec cuando el día desambigua el orden, la fecha existe y no está en el futuro, con zona America/Guayaquil.

La confianza describe la extracción, no la certeza contable ni una probabilidad calibrada: 0,35 si falta importe inequívoco; 0,55 para Outlook o texto posiblemente recortado; 0,75 para el resto de ejemplos reconocidos. Todos requieren revisión, incluida finalidad y cuenta.

La identidad de los candidatos incluye publicación, sesión y revisión HMAC. Las entregas exactamente repetidas no crean otra fila. Las revisiones distintas se conservan como candidatos separados, con advertencia de posible duplicado. F3 resolverá si son actualizaciones de la misma operación o nuevas operaciones. No se deduplica por importe y minuto ni se fusionan operaciones irreversiblemente. La cursorización técnica de F1 se mantiene para entradas antiguas sin parser.

La tabla `candidate` conserva la decisión editable y se vincula por identificador a la observación/cola. La observación retiene la evidencia normalizada original; completar no reescribe esa evidencia. Confirmar exige importe positivo, cuenta activa compatible, sentido y clasificación coherentes. Crear una cuenta por alias habilita este requisito; saldos iniciales, archivo, tarjetas y gestión completa pertenecen a F3. Confirmar o ignorar se audita en una transacción, sin textos privados. Las entradas sintéticas conservan su origen.

Confirmar significa confirmar el candidato para el trabajo contable de F3. No se inserta una transacción ni un asiento en F2. La UI lo declara expresamente. F3 debe consumir estas decisiones de forma idempotente, respetar la distinción entre origen sintético y notificaciones, conciliar revisiones y no volver a decidir campos confirmados silenciosamente.

La migración cifrada 2→3 conserva F1 y crea candidatos sin inventar importes, cuentas o fechas históricas. No existe texto antiguo para volver a ejecutar los parsers. Una entrada F1 ya en cola puede drenarse sin parser después de la actualización. Se mantiene la migración 1→2 y no existe fallback destructivo.
