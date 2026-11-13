# ADR-002 — Cifrado y recuperación

- Room 2.8.1 sobre SQLCipher Android 4.10.0; System.loadLibrary y SupportOpenHelperFactory reales. El esquema inicial solo contiene bootstrap_probe, nunca datos bancarios simulados.
- Secreto SQLCipher: 32 bytes aleatorios. Se persiste envuelto mediante AES-256-GCM; la clave envolvente queda en Android Keystore y el archivo envuelto en noBackupFilesDir mediante AtomicFile.
- El alias forma parte del AAD. Cada cifrado usa IV generado por el proveedor. Formato versionado: versión + IV de 12 bytes + secreto cifrado y tag de autenticación.
- La apertura existente falla si falta secreto o clave; no hay fallbackToDestructiveMigration ni reset automático. Pérdida de Keystore puede hacer los datos irrecuperables, dado que MVP no tiene backup.
- El helper SQLCipher mantiene secreto en memoria durante su vida; no se afirma protección ante un dispositivo comprometido. La política de gestión/liberación de memoria se revisará al incorporar el almacenamiento financiero F1.
- HMAC para futuras huellas utilizará una clave Keystore independiente; no se reutiliza la clave SQLCipher. En F0 se define el contrato, no se procesa ninguna notificación.
- Backup Android deshabilitado y exclusiones completas en cloud-backup y device-transfer. Se excluyen también dominios device-protected. No hay exportación ni diagnóstico con texto completo en F0.
- Ningún logger/analítica se instala. No se registran montos, textos, cuentas ni excepciones que pudieran contener consultas privadas.
- Migraciones financieras, retención/purga de cola mínima y eliminación total se incorporan en F1/F5, respectivamente. Esquema Room v1 exportado para que las futuras migraciones sean verificables.

