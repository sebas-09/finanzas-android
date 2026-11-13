# ADR-001 — Base técnica F0

Estado: implementado; consultar docs/testing/F0.md para evidencia ejecutada y pendiente.

- Namespace propio ec.finanzas.locales; no representa paquetes bancarios.
- Gradle 8.14.3 con checksum oficial; JDK 17. No usar el JAVA_HOME global JDK 25 con esta base.
- AGP 8.13.2, Kotlin 2.2.10, KSP 2.2.10-2.0.2 y Hilt 2.57.1 fijados en catálogo.
- minSdk 29, compileSdk/targetSdk 36 como baseline concreto; no se afirma que sea la última versión Android disponible.
- Compose BOM 2025.08.01, Material 3, Navigation Compose 2.9.3 y StateFlow con recolección consciente del ciclo de vida.
- Dominio y módulos feature son Kotlin/JVM puros en F0. Los módulos de captura y pantallas migrarán a Android/Compose cuando tengan adaptadores reales; no dependen ahora de APIs que todavía no usan.
- app contiene composición, navegación base e inyección. core/design-system contiene tema. Las pantallas de F0 son estados vacíos explícitos; no implementan todavía onboarding persistente, permisos o ledger.
- Las dependencias solo se resuelven durante construcción. El APK no solicita INTERNET ni usa fuentes descargables.
- Versiones fijadas por compatibilidad, sin rangos dinámicos. Antes de actualizar se ejecuta la misma matriz de pruebas.
- lint.xml deja como información únicamente las sugerencias de nuevas versiones de dependencias y herramientas. Se mantienen abortOnError y warningsAsErrors para los controles de corrección, recursos, seguridad y accesibilidad; no se creó una baseline de errores.

