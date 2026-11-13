# Finanzas Locales — Android

App nativa offline definida en el [spec](docs/requirements/SPEC_FINANZAS_ANDROID.md) y el [plan](docs/requirements/PLAN_IMPLEMENTACION_FINANZAS_ANDROID.md). F5 añade exportación CSV mediante el selector de Android, borrado completo con confirmación y firma release configurable. Conserva el libro mayor reversible, resumen, filtros, categorías, ciclos y presupuestos de F4, con centavos exactos y persistencia cifrada.

Consulta la [guía del libro mayor](docs/user/F3-LIBRO.md), la [guía de captura](docs/user/F1-CAPTURA.md) y la [decisión técnica de F3](docs/decisions/006-libro-f3.md). La validación de bancos en teléfono físico sigue pendiente. PeiGo admite crédito manual y su parser está desactivado. Outlook es experimental. Usa `--no-daemon` en Windows para liberar JARs; `--no-parallel --max-workers=1` evita escrituras simultáneas del esquema Room entre variantes.

La entrega debug `0.6.0-f5` y su checksum están en [artifacts](artifacts/). Consulta la [guía F5 de datos e instalación](docs/user/F5-DATOS-Y-ENTREGA.md) y la [verificación F5](docs/testing/F5.md): 51 pruebas JVM, suites Android 10/16, actualización, CSV real, borrado y claves renovadas. El release firmado espera el keystore del propietario; bancos/OEM físicos y TalkBack hablado siguen pendientes.

## Requisitos

- JDK 17, Android SDK Platform 36 y Build Tools 36.0.0.
- Gradle Wrapper incluido, con checksum de distribución oficial. Las dependencias requieren internet al construir; el APK no lo solicita.
- Android 10/API 29 o superior para instalar. Dispositivo/emulador necesario para probar Keystore y SQLCipher.
- En Android Studio seleccionar Gradle JDK 17 y abrir esta carpeta, no el portafolio completo. El SDK se configura mediante ANDROID_HOME o local.properties (no versionado).

## Compilar y verificar

Windows PowerShell (con JAVA_HOME apuntando a JDK 17):

~~~powershell
.\gradlew.bat test :app:assembleDebug :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest :app:assembleRelease lint --no-daemon --no-parallel --max-workers=1
python tooling/verify-manifest.py debug
python tooling/verify-manifest.py release
~~~

Linux/macOS: usar ./gradlew y python3. APK debug: app/build/outputs/apk/debug/app-debug.apk. La firma release se configura con FINANZAS_RELEASE_* y tooling/package-release.ps1; sin esas variables solo se compila un release optimizado sin firma, no instalable.

Pruebas Android:

~~~powershell
.\gradlew.bat :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest
powershell -NoProfile -File tooling/test-process-restart.ps1 -Adb adb
~~~

El script de reapertura ejecuta escritura y lectura en procesos distintos con force-stop entre ellos. En instalaciones con más de un dispositivo usar -Serial. No se necesita permiso bancario para ninguna prueba F0.

Si el APK de pruebas ya está compilado, el script admite -SkipBuild. En equipos con poca memoria ejecutar las tareas instrumentadas de cada módulo de forma secuencial.

## Organización

- app: Application Hilt, navegación Compose, comprobación local de almacenamiento.
- core/model: contratos e invariantes sin Android; core/testing: fixtures sintéticos.
- core/security: secreto SQLCipher envuelto y HMAC con Keystore; core/database: esquema financiero, migraciones y cola cifrada.
- core/design-system: tema Material 3 claro/oscuro.
- feature/capture: listener, comprobación de fuentes, HMAC y recuperación con WorkManager.
- feature/onboarding: consentimiento, selección de fuentes y controles de captura.
- feature/parsers: normalización monetaria y adaptadores JVM con fixtures sintéticos.
- feature/ledger y feature/reconciliation proveen reglas contables y coincidencias entre observaciones. app integra cuentas, revisión, registro, edición, conciliación y deshacer.
- feature/dashboard: agregados mensuales, filtros, reglas, ciclos y umbrales; app integra pantallas y avisos mediante WorkManager.
- feature/settings: CSV UTF-8 con importes exactos y protección de fórmulas; app integra selector de documentos y borrado de datos mediante Android.
- docs/decisions: versiones, seguridad, contabilidad, captura y candidatos; docs/testing: aceptación y evidencia; docs/DESIGN.md: navegación y accesibilidad.

La CI define build/lint/pruebas JVM y un job instrumentado independiente para API 29 y 36. Crear el repositorio y subirlo a GitHub no forma parte de F0 local; el workflow se ejecutará cuando se publique el código allí.

## Privacidad y límites

Sin INTERNET, credenciales bancarias, analítica ni backups Android. La captura comienza apagada y requiere consentimiento, permiso de Android y fuentes seleccionadas. Los textos se usan temporalmente; la cola y observaciones conservan huellas y metadatos cifrados. No se importa historial ni se conecta a bancos o buzones. El estado y los contadores describen detección técnica, con cobertura incompleta.

Las pruebas instrumentadas activan y revocan el acceso del listener y registran una fuente del APK de pruebas. Ejecutarlas en emuladores o una instalación dedicada de ensayo. El APK principal solicita POST_NOTIFICATIONS desde el control explícito de presupuestos para alertas opcionales. Denegarlo no bloquea captura. El APK de pruebas también emite avisos sintéticos desde su propio proceso.

