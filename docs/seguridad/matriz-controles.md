# Matriz de Controles de Seguridad

## Aislamiento y Acceso (Tenancy y Autorización)

| ID | Dominio | Control | Origen | Componente responsable | Verificación | Fase |
|---|---|---|---|---|---|---|
| SEC-001 | Aislamiento | Silo de datos por tenant (base de datos, buckets, llaves KMS y vector store separados). | Plan Maestro (6) | `tenant-service`, `document-service`, `chat-service` | Test de integración: credenciales de tenant A fallan en recursos de B. | F4 |
| SEC-002 | Aislamiento | Revalidación de tenant contra `ROLE_ASSIGNMENT` en cada petición, sin confiar ciegamente en JWT. | Plan Maestro (6) | `edge-gateway`, `security-lib` | Test unitario: JWT válido pero asignación revocada es rechazada. | F4 |
| SEC-003 | Aislamiento | Errores genéricos que no revelan existencia de recursos o documentos de otro tenant. | Plan Maestro (6) | `edge-gateway`, Todos | Test e2e: petición a ID de otro tenant retorna 404 genérico o 403. | F4 |
| SEC-004 | Aislamiento | Sesiones de chat aisladas estrictamente por usuario y tenant. | Plan Maestro (6) | `chat-service` | Test de integración: consulta de historial cruzado denegada. | F6 |
| SEC-005 | Aislamiento | Prueba de fuga entre tenants automatizada en CI. | Plan Maestro (6) | CI Pipeline | Ejecución exitosa del test en el pipeline. | F4 |
| SEC-006 | Acceso | Autenticación fuerte (MFA) requerida para roles internos y administradores. | Lev. Funcional (6) | Proveedor de Identidad | Verificación de configuración IdP. | F7 |
| SEC-007 | Acceso | Control de acceso a nivel de documento, no solo tenant (filtros por clasificación/área). | Lev. Funcional (5.2) | `chat-service`, `document-service` | Test de integración: búsqueda restringida a permisos del usuario. | F6 |
| SEC-008 | Acceso | Revocación de sesión efectiva e inmediata y tokens de corta duración. | Plan Maestro (6) | `security-lib`, IdP | Test unitario: token revocado genera 401. | F7 |
| SEC-009 | Acceso | Regla de cuatro ojos para aprobación de oficios y campos críticos (monto, identificación, cuenta, tipo). Requiere aprobador distinto. | Plan Maestro (6) | `review-service` | Test de integración: creador no puede auto-aprobar. | F5 |
| SEC-010 | Acceso | Acceso "break-glass" para soporte: requiere justificación, expiración (TTL), MFA, aprobador distinto SIEMPRE, auditoría visible al tenant. | Plan Maestro (6) | `security-lib`, Todos | Test e2e: escalamiento temporal verificado y auditado. | F7 |
| SEC-011 | Acceso | Separación de roles: administradores de llaves (OpenBao/KMS) no acceden al contenido y viceversa. | Lev. Funcional (5.6) | Plataforma / IAM | Revisión de políticas IAM. | F8 |
| SEC-012 | Acceso | Certificación periódica de accesos: reporte exportable generado por tenant-service trimestralmente con evidencia firmada. | Plan Maestro (6) | `tenant-service` | Reporte trimestral de certificación firmado. | F7 |
| SEC-013 | Acceso | Rol de auditoría no revocable por administradores regulares. | Plan Maestro (6) | IAM | Prueba de revocación fallida por admin regular. | F7 |

## Cifrado y Datos (Protección de Información)

| ID | Dominio | Control | Origen | Componente responsable | Verificación | Fase |
|---|---|---|---|---|---|---|
| SEC-014 | Cifrado | Cifrado en tránsito (TLS) en todos los tramos, mTLS entre microservicios. | Plan Maestro (6) | Kubernetes, cert-manager, `edge-gateway` | Análisis de tráfico interno y externo. | F3 |
| SEC-015 | Cifrado | KEK (Key Encryption Key) única por tenant, segregada entre datos y auditoría. | Plan Maestro (6) | `tenant-service`, `kms-port` | Test de integración: cifrado con llave correcta. | F4 |
| SEC-016 | Datos | Crypto-shredding: deshabilitar KEK de datos de inmediato e invalidar caché DEK; destrucción irreversible al fin de ventana del proveedor. | Lev. Funcional (4) | `tenant-service`, `kms-port` | Test e2e: acceso imposible post-destrucción de KEK. | F7 |
| SEC-017 | Datos | Bloqueo de crypto-shredding si el tenant tiene un "legal hold" activo. Llave de auditoría sobrevive al shredding de datos. | Plan Maestro (6) | `tenant-service`, `audit-service` | Test de integración: eliminación denegada bajo legal hold. | F7 |
| SEC-018 | Datos | Clasificación de documentos obligatoria (Público, Interno, Confidencial, Altamente Confidencial). Oficios son Confidenciales por defecto. Altamente Confidencial solo si el tenant lo marca, con aprobación de un Data Steward distinto. | Plan Maestro (6) | `document-service` | Test unitario: oficios sin clasificación se asumen Confidenciales. | F4 |
| SEC-019 | Datos | Detección de PII previo a la indexación vectorial (DLP). | Plan Maestro (6) | `document-service` | Test unitario: bloqueo/indexación segregada de PII detectado. | F6 |
| SEC-020 | Datos | Restricción de residencia de datos según configuración del tenant. | Lev. Funcional (4) | Plataforma, K8s | Verificación de afinidad de nodos/buckets. | F8 |
| SEC-021 | Datos | Obligación de retención mínima no reducible por los usuarios. | Plan Maestro (6) | `audit-service`, `document-service` | Test unitario: intento de reducción de umbral falla. | F7 |
| SEC-022 | Datos | Purga verificable (Habeas Data): eliminación física de blob, páginas, chunks de índice y caché. Emite evento `documento.purgado`. | Plan Maestro (6) | `document-service`, `chat-service` | Test de integración: eliminación física de todo rastro. | F7 |

## Entrada No Confiable y Resiliencia

| ID | Dominio | Control | Origen | Componente responsable | Verificación | Fase |
|---|---|---|---|---|---|---|
| SEC-023 | Entrada | Validación de magic bytes, límites de tamaño y rechazo de zip-bombs. | Plan Maestro (6) | `edge-gateway`, `document-service` | Test de integración: carga de archivo malicioso falla. | F4 |
| SEC-024 | Entrada | Rasterizado de PDF a imagen, rechazando JavaScript y archivos adjuntos en el origen. Conversión DOCX con LibreOffice headless en sandbox sin red, macros deshabilitadas. | Plan Maestro (6) | `renderer` | Test unitario: PDF interactivo es limpiado/rechazado. | F4 |
| SEC-025 | Entrada | Análisis de antivirus (ClamAV) en sandbox sin salida a internet ni credenciales. | Plan Maestro (6) | `renderer` | Test e2e: archivo infectado EICAR es detectado. | F4 |
| SEC-026 | Entrada | Deshabilitación absoluta de entidades externas XML (XXE) en parsers. | Plan Maestro (6) | Todas las libs | Test unitario: parser rechaza entidades DTD. | F4 |
| SEC-027 | Entrada | Prevención SSRF/TOCTOU: DNS pinning. Bloqueo de RFC1918, loopback, 169.254/16, 100.64/10, IPv6 ULA/link-local y metadata. Sin redirects. | Plan Maestro (6) | `notification-service` | Test unitario: webhook a 169.254.169.254 falla. | F5 |
| SEC-028 | Entrada | Firmas HMAC sobre timestamp+cuerpo; ventana anti-replay; dos secretos activos concurrentes durante rotación. | Plan Maestro (6) | `notification-service` | Test de integración: validación de firma y timestamp. | F5 |
| SEC-029 | Resiliencia | Idempotencia estricta en procesamiento (evita oficios duplicados/embargos dobles). | Plan Maestro (6) | `document-service` | Test unitario: reintento de carga retorna mismo estado. | F4 |
| SEC-030 | Resiliencia | Rate limiting, ráfaga controlada (burst) y cuotas por tenant/usuario. | Lev. Funcional (5.3) | `edge-gateway`, `tenant-service` | Test de integración: peticiones en exceso retornan 429. | F7 |

## IA y Model Risk Management

| ID | Dominio | Control | Origen | Componente responsable | Verificación | Fase |
|---|---|---|---|---|---|---|
| SEC-031 | IA | Grounding obligatorio: toda respuesta debe tener citación verificable a la fuente nativa. | Plan Maestro (6) | `chat-service`, `extraction-service`| Test unitario: respuesta sin cita es filtrada. | F5, F6 |
| SEC-032 | IA | Fidelidad absoluta en cifras, montos y fechas extraídas (sin redondeos). | Lev. Funcional (5.2) | `extraction-service` | Test unitario: extracción de montos coincide carácter a carácter. | F4 |
| SEC-033 | IA | Protección contra prompt injection directa e indirecta. Emite evento `seguridad.prompt_injection_detectado`. | Plan Maestro (6) | `chat-service`, `extraction-service`| Test e2e: payload inyectado en oficio no altera instrucción. | F6 |
| SEC-034 | IA | Control de alucinaciones mediante revisión humana forzada si score de calibración es bajo. | Plan Maestro (6) | `extraction-service`, `review-service` | Test e2e: oficio complejo es enrutado a revisión manual. | F5 |
| SEC-035 | IA | Gate de riesgo en CI: regresiones en precisión de modelo rechazan despliegue. | Plan Maestro (6) | `quality-service`, CI | Test en pipeline: ejecución de golden set. | F5 |
| SEC-036 | IA | Versión de prompt y modelo estrictamente inmutables por transacción, con capacidad de rollback. | Lev. Funcional (5.8) | `extraction-service`, config | Verificación de trazabilidad del modelo en el log de auditoría. | F4 |
| SEC-037 | IA | Uso exclusivo de proveedores de LLM homologados (cero entrenamiento con datos de clientes). | Plan Maestro (6) | `llm-port` | Revisión contractual/Términos de servicio de nube. | F8 |
| SEC-038 | IA | Señalización obligatoria de contradicciones documentales sin resolución silenciosa del LLM. | Lev. Funcional (5.2) | `chat-service` | Test unitario: fuentes en conflicto emiten alerta. | F6 |

## Auditoría y Operación Continua

| ID | Dominio | Control | Origen | Componente responsable | Verificación | Fase |
|---|---|---|---|---|---|---|
| SEC-039 | Auditoría | Registro inmutable WORM de cada interacción (incluso bloqueadas y desde caché). Fallas generan `seguridad.acceso_denegado`. | Plan Maestro (6) | `audit-service` | Verificación de políticas S3 Object Lock/Compliance. | F4 |
| SEC-040 | Auditoría | Auditoría implementada como cadena de hashes para prevenir eliminación subrepticia por administradores. | Plan Maestro (6) | `audit-service` | Test unitario: validación de continuidad de la cadena criptográfica. | F4 |
| SEC-041 | Auditoría | Logs sanitizados sin PII ni tokens. Integración con SIEM y WORM para eventos de seguridad y rechazos del gateway. | Plan Maestro (6) | `observability-lib`, `edge-gateway` | Análisis automatizado de logs buscando patrones PII. | F3 |
| SEC-042 | Auditoría | Expediente de auditoría generado firmado digitalmente mediante llave asimétrica dedicada (OpenBao Transit ed25519). | Plan Maestro (6) | `audit-service` | Test de integración: validación de firma del exporte. | F7 |
| SEC-043 | Operación | Gestión de secretos dinámica con credenciales de vida corta (ej. pools Hikari con OpenBao). Sin credenciales fijas. | Plan Maestro (6) | Plataforma, `tenant-service` | Inspección de pods (ausencia de env vars sensibles). | F3 |
| SEC-044 | Operación | Seguridad cadena de suministro: SAST (CodeQL/Semgrep, SpotBugs), SCA e imágenes (Trivy, Dependabot), SBOM CycloneDX, cosign. | Plan Maestro (6) | CI Pipeline | Ejecución de Trivy, registro de artefactos firmados. | F3 |
| SEC-045 | Operación | Admisión Kyverno (verifyImages, no root, sin hostPath). OPA descartado. | Plan Maestro (6) | Kubernetes | Test de despliegue de pod malicioso (rechazado). | F3 |
| SEC-046 | Operación | Separación física o lógica estricta de ambientes de Desarrollo, Pruebas y Producción. | Plan Maestro (6) | Plataforma | Verificación de clústeres/namespaces independientes. | F3 |
| SEC-047 | Operación | Offboarding en dos fases, garantizando legal hold antes del borrado. Emite `tenant.baja_iniciada`. | Lev. Funcional (5.11)| `tenant-service` | Test e2e: tenant inactivo mantiene auditoría hasta fecha límite. | F7 |
| SEC-048 | IA | Abstención obligatoria del chat ("información insuficiente") sin inventar datos cuando el contexto no alcanza. | Plan Maestro (8) | `chat-service` | Test unitario: preguntas fuera de contexto son rechazadas. | F6 |
| SEC-049 | IA | Registro inmutable y firmado del conjunto prompt + configuración + versión de modelo usado por cada extracción/respuesta. | Plan Maestro (8) | `audit-service`, `extraction-service` | Verificación de logs de auditoría por transacción. | F5 |
| SEC-050 | Auditoría | Cero PII en eventos Kafka (patrón claim-check). Los consumidores obtienen datos por API REST u outbox. | Plan Maestro (8) | Todos los servicios | Test unitario: esquemas JSON de eventos sin PII. | F4 |

## Mapeo Normativo (Pendiente de validación por Cumplimiento)

* **Ley 1581 de 2012 / Decreto 1377 de 2013 (Habeas Data Colombia):**
  * Derechos de los titulares (acceso, rectificación, eliminación): SEC-022.
  * Medidas de seguridad y confidencialidad: SEC-014, SEC-015, SEC-018.
  * Privacidad por diseño en el almacenamiento: SEC-019.
* **Ley 1266 de 2008 (Habeas Data Financiero Colombia):**
  * Veracidad de la información y fidelidad de reportes: SEC-032.
  * Trazabilidad de origen y custodia documental: SEC-039, SEC-040.
* **Circular Básica Jurídica SFC CE 007/2018 (Ciberseguridad):**
  * Segregación de funciones y mínimos privilegios: SEC-009, SEC-011.
  * Gestión de incidentes y trazabilidad: SEC-039, SEC-041.
  * Auditoría y revisiones de acceso: SEC-012, SEC-013.
  * Monitoreo y protección contra código malicioso: SEC-025.
* **Circular Básica Jurídica SFC CE 005/2019 (Computación en la Nube):**
  * Cifrado e independencia de datos del cliente respecto al CSP: SEC-015, SEC-016.
  * Portabilidad y eliminación segura (shredding): SEC-016, SEC-047.
  * Ubicación y jurisdicción de los datos: SEC-020.
