# Modelo de Amenazas (STRIDE)

Este documento detalla los principales vectores de ataque y fallos arquitectónicos contemplados para la plataforma IDP Bancario, agrupados por fronteras de confianza del sistema. 

El marco empleado es **STRIDE**: Spoofing (Suplantación), Tampering (Alteración), Repudiation (Repudio), Information Disclosure (Fuga de Información), Denial of Service (Denegación de Servicio), Elevation of Privilege (Elevación de Privilegio).

## 1. Frontera: Internet → Gateway

Esta frontera concentra los ataques dirigidos a la cara expuesta al público del servicio.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Spoofing | Un atacante forja un token JWT o roba uno válido e intenta acceder a la API de un tenant. | Fuga o inyección de oficios a nombre de otro tenant. | Revalidación del tenant por request; validación estricta de firmas JWT; tokens cortos; mTLS en microservicios internos. | SEC-002, SEC-008 |
| Denial of Service | Envío masivo de peticiones o de archivos gigantescos (ej. un PDF de 1GB o una zip-bomb). | Caída del gateway o agotamiento del ancho de banda y cómputo backend. | Limitación de tasa (rate limit) por IP y tenant; límite duro de tamaño de payload y páginas de PDF en el Edge. | SEC-023, SEC-030 |
| Information Disclosure | Error de la aplicación revela rutas del servidor, versiones de librerías o IDs de otros tenants (ej. error 500 verboso). | Reconocimiento por parte de un atacante para perfilar ataques futuros. | Enmascaramiento de errores (404 genérico en caso de autorización fallida), desactivación de stacktraces. | SEC-003 |

## 2. Frontera: Gateway → Servicios Internos

Flujo desde el perímetro hacia la red de microservicios, donde el tráfico ya superó la capa de validación inicial pero no asume un origen seguro internamente.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Elevation of Privilege | El gateway, o un servicio comprometido, intenta realizar acciones administrativas en el tenant-service o acceder a auditoría de todos los tenants. | Un componente asume rol de superadmin y afecta la integridad de la plataforma. | Arquitectura de red segmentada, revalidación local por tenant y rol, mTLS con cert-manager. | SEC-002, SEC-014 |
| Tampering | Peticiones HTTP internas modificadas en tránsito (Man-in-the-Middle) entre dos contenedores en el clúster. | Alteración del flujo de procesamiento o interceptación de PII de oficios. | Tráfico interno cifrado (mTLS en la malla de servicios). | SEC-014 |

## 3. Frontera: Document Service → Renderer (Sandbox)

Esta frontera lidia con datos controlados enteramente por usuarios potencialmente maliciosos.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Elevation of Privilege | Un PDF manipulado desencadena un exploit (ej. RCE) en la librería de procesamiento de imágenes PDFBox o Ghostscript. | Toma de control del contenedor renderer y salto lateral hacia la base de datos de documentos. | El renderer opera como un Sandbox sin credenciales de base de datos, sin egress a internet, fs de solo lectura, con ClamAV previo y privilegios mínimos (Kyverno no root). | SEC-024, SEC-025, SEC-045 |
| Information Disclosure | Un archivo SVG o XML manipulado intenta resolver entidades externas (XXE) leyendo tokens internos del Pod o archivos como `/etc/passwd`. | Exfiltración de secretos internos o metadatos del clúster de Kubernetes. | Deshabilitación completa de resolución de entidades externas XML (XXE) a nivel de parser. | SEC-026 |

## 4. Frontera: Servicios → LLM Externo

Flujo hacia y desde el proveedor del modelo fundacional, que implica pérdida de control directo sobre la memoria y ejecución del cómputo.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Tampering | **Prompt Injection Indirecto:** El usuario incrusta un texto invisible o confuso en un oficio que altera la directiva del sistema. | El modelo clasifica erróneamente un embargo o genera respuestas falsas. | Emisión de evento `seguridad.prompt_injection_detectado`. Prompts acotados, separación explícita de instrucciones vs datos, revisión manual de anomalías. | SEC-033, SEC-034 |
| Information Disclosure | **Fuga cruzada (Cross-Tenant) vía LLM:** Un tenant formula un prompt diseñado para exfiltrar datos del contexto histórico en un LLM compartido. | Divulgación de oficios de un banco competidor. | Zero retención/entrenamiento por parte del proveedor; índice vectorial segregado por tenant. | SEC-001, SEC-037 |
| Repudiation | **Alucinación y orfandad de configuración:** El LLM genera valores incorrectos o "inventa" respuestas sin trazabilidad de qué modelo o prompt exacto produjo el fallo. | Decisiones financieras basadas en datos fabricados (Habeas Data). Imposibilidad de reproducir el error. | Abstención obligatoria ("información insuficiente") sin inventar datos (SEC-048). Registro inmutable firmado del trío prompt+configuración+versión de modelo (SEC-049). Grounding forzado. | SEC-031, SEC-032, SEC-048, SEC-049 |

## 5. Frontera: Servicios → Kafka

Mensajería asíncrona mediante la que fluyen los estados de orquestación, notificaciones y auditoría.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Information Disclosure | Falla en los logs o retención excesiva de datos en Kafka expone información sensible. | Vulneración de la ley Habeas Data debido a la presencia perenne de datos en los logs inmutables del broker. | Patrón "Claim-Check" estricto: los eventos de Kafka no llevan PII, solo IDs y estados. | SEC-041, SEC-050 |
| Tampering | Un servicio productor genera el evento de orquestación pero falla al escribir su persistencia local, o viceversa (Double Spend). | Discrepancia entre la realidad auditada y la comunicada al cliente. | Patrón "Transactional Outbox": eventos a base de datos y negocio comparten transacción local por tenant; relay diferido al broker. | SEC-029 |

## 6. Frontera: Servicios → Almacenamiento por Tenant

Comunicación con bases de datos transaccionales, índices vectoriales (pgvector) y Object Storage de documentos.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Information Disclosure | Falla en query ORM permite que un bucket o búsqueda vectorial traiga documentos de otro tenant. | Filtración masiva a tenants equivocados. | Silo estricto por tenant (bases lógicas separadas vía CNPG, buckets propios), llaves OpenBao/KMS únicas por tenant impidiendo descifrado ajeno. | SEC-001, SEC-015 |
| Tampering | Eliminación de registros de un tenant activo para evitar respuestas legales incómodas. | Alteración de evidencia legal; respuesta falsa por insuficiencia de datos. | Crypto-shredding centralizado: al deshabilitar KEK, la llave de auditoría sobrevive para garantizar el expediente WORM histórico. | SEC-016, SEC-017 |

## 7. Frontera: Notification Service → Internet

Envío de estado o alarmas hacia infraestructuras cliente vía webhooks.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Spoofing | Un servicio en la nube (AWS/GCP) es engañado vía SSRF y DNS Rebinding (TOCTOU) para consultar metadatos internos (ej. `http://169.254.169.254`). | Fuga de credenciales cloud, compromiso de identidad del clúster de Kubernetes, ataque a servicios internos. | Bloqueo absoluto de IPs reservadas (RFC1918, CGNAT, link-local). DNS pinning explícito: resolución y conexión atada a la misma IP validada; sin redirecciones HTTP. | SEC-027 |
| Repudiation | El tenant niega haber recibido el evento de oficio aprobado o un atacante intercepta y reproduce un payload. | Procesos financieros duplicados; disputas legales. | Firmas HMAC en cada webhook cubriendo timestamp y cuerpo completo (prevención anti-replay). Dos llaves concurrentes en rotación. | SEC-028 |

## 8. Frontera: Administración → Tenant Service

Interfaz de administración central usada por la organización que opera la plataforma.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Tampering | Un administrador interno con malas intenciones, o comprometido, elimina un tenant de la base de datos o disminuye su umbral de retención (Habeas Data) sin justificación legal. | Incumplimiento regulatorio, destrucción ilícita de evidencia frente al ente supervisor o autoridades judiciales. | Retención mínima no reducible impuesta en base; offboarding en dos fases validando ausencias de "legal hold"; auditoría de llaves no revocable. | SEC-017, SEC-021, SEC-047 |
| Repudiation | Modificación sigilosa de los consumos o cuotas para fraude de facturación o venta paralela. | Afectación financiera al tenant o a la empresa operadora de la plataforma, imposible de probar contablemente. | Toda interacción registrada en el `audit-service` encadenada y WORM (Write Once, Read Many). Los administradores operan en roles segregados sin permiso de purga sobre registros contables y operacionales. | SEC-039, SEC-040 |

## 9. Frontera: Operadores Humanos (Revisor, Soporte, Break-Glass)

Personal interno que interviene ante fallas algorítmicas, revisión de oficios complejos o soporte de TI.

| Amenaza (STRIDE) | Escenario Concreto | Impacto | Mitigación | IDs SEC |
|---|---|---|---|---|
| Information Disclosure | Un soporte nivel 1 visualiza un oficio "Confidencial" durante un diagnóstico normal. | Fuga de información crítica y ruptura del modelo de privilegios. | Acceso mediante "Break-glass": requiere justificación auditada, aprobador distinto SIEMPRE, TTL corto, notifica al tenant y usa autenticación fuerte en Keycloak. | SEC-010 |
| Tampering | Un revisor altera cifras de un embargo a favor de terceros, o el creador auto-aprueba su subida. | Fraude financiero y fallas regulatorias graves. | Regla estricta de Cuatro Ojos (usuario distinto) en campos críticos. Trazabilidad inmutable en base y auditoría WORM firmada digitalmente. | SEC-009, SEC-042 |
