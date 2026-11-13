# F5 — Datos, instalación y entrega

Desde la pantalla principal, en Ajustes → Tus datos, **Exportar movimientos CSV** muestra primero un aviso de privacidad. Elegir destino abre el selector de Android; cancelar no crea un archivo desde la app. Selecciona una ubicación de confianza. Si eliges un proveedor de nube, ese proveedor puede sincronizar el documento aunque Finanzas Locales no tenga acceso a internet.

El CSV está en texto claro: contiene fechas de Guayaquil, importes USD exactos, alias, categoría, nota, tipo, sentido, origen y estado. Incluye todos los movimientos confirmados vigentes y las operaciones propias; estas últimas no son gasto de consumo. Las devoluciones usan la categoría actual de su compra, como el resumen. Excluye pendientes, pruebas, borrados y duplicados absorbidos. No contiene mensajes bancarios originales ni claves, y no sirve para importar/restaurar la app. Un archivo sin movimientos conserva los encabezados. Tras un fallo puede quedar un documento parcial: elimínalo o vuelve a exportar. Guarda el archivo de forma privada.

**Eliminar todos los datos** requiere marcar el reconocimiento y pulsar **Borrar definitivamente**. Android cerrará la app. Al volver a abrirla se inicia sin cuentas, movimientos, reglas personalizadas, presupuestos, sesiones ni claves anteriores. Se recrean las plantillas iniciales. Es necesario aceptar consentimiento y seleccionar fuentes de nuevo; no se recuperan avisos anteriores a la nueva activación. Los CSV guardados fuera de la app permanecen y se eliminan desde su ubicación. No hay deshacer para este borrado.

## Instalar el APK de prueba

Utiliza Android 10 o superior y el APK debug F5 de artifacts. Comprueba su SHA-256 contra el archivo .sha256. Autoriza instalar desde la aplicación que abre el APK si Android lo solicita. F5 debug conserva el certificado de las fases anteriores: instala como actualización, sin desinstalar ni borrar datos. Una firma incompatible debe investigarse; no desinstales para forzar la actualización.

La captura comienza desactivada. Acepta el consentimiento, concede acceso a notificaciones en Ajustes Android y habilita solamente las fuentes deseadas. Autorizar acceso al listener es distinto de permitir avisos de presupuestos. Puedes denegar estos últimos y continuar registrando/capturando. Las restricciones de batería del fabricante pueden omitir o retrasar eventos: comprueba el estado y registra manualmente lo que falte. PeiGo sigue manual y Outlook es experimental. Los formatos bancarios son preliminares hasta comprobarlos en teléfono.

Al recompilar un debug, Android usa por defecto la clave debug del equipo. Para actualizar una instalación existente, conserva y configura el mismo keystore de firma; la coincidencia debe comprobarse también en los APK de pruebas.

## Preparar el release con tu clave

Usa JDK 17, SDK 36 y Build Tools 36.0.0. Mantén el keystore y sus contraseñas fuera del repositorio. Configura en el entorno local estas cuatro variables, sin guardar valores en scripts ni compartirlos en chat:

- FINANZAS_RELEASE_STORE_FILE: ruta absoluta del keystore.
- FINANZAS_RELEASE_STORE_PASSWORD: contraseña del almacén.
- FINANZAS_RELEASE_KEY_ALIAS: alias de la clave.
- FINANZAS_RELEASE_KEY_PASSWORD: contraseña de esa clave.

Ejecuta `./tooling/package-release.ps1 -Sdk RUTA_DEL_SDK`. El script compila, audita el manifest, verifica la firma y copia el APK firmado y su checksum a artifacts. Sin clave, `assembleRelease` solo genera un APK optimizado sin firma: no es el entregable instalable.

Un certificado de producción nuevo no puede actualizar directamente la instalación debug firmada con otro certificado. Antes de una transición así hay que decidir expresamente el destino de los datos y probar la instalación; el CSV es una consulta externa, no una restauración. Conserva el mismo keystore de producción para futuras actualizaciones y verifica su custodia/recuperación antes de distribuir.

## Cobertura y mantenimiento

Consulta la matriz de aceptación y el informe F5 para distinguir pruebas sintéticas de validación bancaria física. No declares soportado un formato real solo porque un fixture pasó. Para ampliar un parser: reunir muestras anonimizadas, añadir positivos y negativos, revisar fecha/importe/sentido sin inventar campos, versionar el adaptador y ejecutar regresiones de deduplicación y libro mayor. No guardar notificaciones completas, números de cuenta, OTP ni credenciales en logs o fixtures.

La validación con bancos, restricciones OEM y TalkBack hablado requiere un teléfono. La firma release final también espera tu keystore. El MVP no se declara aceptado en producción mientras falten esas comprobaciones.
