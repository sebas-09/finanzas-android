# Usar la captura de F1

Esta versión detecta eventos nuevos autorizados y conserva metadatos técnicos cifrados. La interpretación de importes, clasificación y libro de movimientos se implementan en F2/F3. No muestra saldos bancarios verificados ni ejecuta pagos.

1. Instala el APK de F1. Al actualizar la versión F0 firmada con la misma clave se conserva la base cifrada y se aplica una migración.
2. En la bienvenida, lee la información y marca la casilla de consentimiento. También puedes explorar sin activar; las fuentes y la captura seguirán apagadas.
3. Pulsa **Abrir acceso a notificaciones**. Android concede un acceso amplio al servicio; la app filtra por las fuentes que habilites. Busca **Finanzas Locales · Fuentes autorizadas** y concede el acceso si decides usarlo. Vuelve a la app.
4. Busca tu app instalada, comprueba su nombre y paquete y elige la entidad correspondiente. La app guarda una huella del certificado local; esta selección no certifica por sí sola la identidad oficial del banco. Habilita explícitamente la fuente. Ninguna comienza activa por defecto.
5. Pulsa **Activar captura**. Verás el estado del servicio y, al llegar avisos nuevos detectables, la fecha de la última detección por fuente y el contador de eventos. Los textos completos no se guardan.
6. Usa **Pausar captura** y **Reanudar captura** en Conexión o Ajustes. Las pausas no se rellenan y una fuente habilitada tarde no recupera sus avisos anteriores. Una entrada que ya estaba persistida puede terminar su procesamiento después de pausar.

Si Android bloquea el acceso por haber instalado un APK, abre Información de la app, menú de tres puntos y **Permitir ajustes restringidos**, si esa opción está disponible. Después vuelve a los ajustes de acceso a notificaciones. La app no puede concederse este permiso por sí sola.

Revocar el acceso en Android desactiva la captura. Después de concederlo nuevamente, pulsa Reanudar. Un reinicio o desconexión puede producir omisiones; siempre se abre una ventana nueva, sin consultar notificaciones activas antiguas.

## Outlook y pruebas

Outlook es un perfil experimental opcional y requiere una confirmación adicional. Solo se considera texto visible de notificaciones nuevas; no hay acceso al buzón ni al cuerpo completo de los correos. Los resúmenes agrupados se descartan en esta fase.

**Ejecutar prueba sintética** añade una observación interna marcada como SYNTHETIC. Su contador se muestra separado. La prueba no publica una notificación y no crea gastos, ingresos, deuda o movimientos de cuentas.

## Cobertura actual

El filtro de F1 usa señales conservadoras de vocabulario financiero. Puede ignorar alertas truncadas, redactadas o con textos distintos. Los contadores describen eventos detectados, no operaciones conciliadas. Ningún banco se declara validado hasta ensayar su aplicación y sus formatos en un teléfono físico.

Las notificaciones ya capturadas en F1 no contienen textos ni importes recuperables. Instalar futuros parsers no importará ese historial retrospectivamente. La exportación y eliminación completa desde la app corresponden a F5.
