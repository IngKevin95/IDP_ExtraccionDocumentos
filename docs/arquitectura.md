# Arquitectura del Sistema: IDP Bancario

Este documento describe la arquitectura del sistema de Procesamiento Inteligente de Documentos (IDP) para la extracción de oficios de embargo/desembargo y chat documental.

## 1. Vista General

La plataforma está diseñada en una arquitectura de microservicios guiada por eventos, orientada a separar responsabilidades según fronteras de confianza.

```mermaid
graph TD
    Client[Cliente/API] --> GW[edge-gateway]
    GW --> TS[tenant-service]
    GW --> DS[document-service]
    GW --> CS[chat-service]
    GW --> RS[review-service]
    GW --> NS[notification-service]
    GW --> AS[audit-service]
    GW --> QS[quality-service]
    
    DS -- S3 Bucket --> Storage[(Tenant Storage)]
    DS -- HTTP mTLS --> RND[renderer]
    
    ES[extraction-service] -- LLM --> LlmProvider[(LLM Provider)]
    
    Kafka((Kafka KRaft))
    DS -.-> Kafka
    ES -.-> Kafka
    RS -.-> Kafka
    TS -.-> Kafka
    
    Kafka -.-> DS
    Kafka -.-> ES
    Kafka -.-> RS
    Kafka -.-> NS[notification-service]
    Kafka -.-> AS[audit-service]
    Kafka -.-> QS[quality-service]
    Kafka -.-> CS
    
    AS -- WORM --> AuditStorage[(Audit WORM)]
    CS -- pgvector --> VectorDB[(Vector DB)]
    TS -- Admin --> Platform[(K8s/Cloud)]
```

## 2. Componentes

### 2.1 `edge-gateway`
- **Responsabilidad:** Terminación TLS, validación de firma y expiración de JWT, rate limit, ruteo, límites de tamaño.
- **Datos:** Redis compartido de plataforma SOLO con contadores (sin datos de tenant), en alta disponibilidad con réplica. Si Redis cae, se usa limitador local en memoria por réplica (degradación segura). Sin base de datos.
- **API:** Expone rutas públicas. Exporta rechazos (401/429) como logs de seguridad hacia SIEM/WORM vía colector de logs.
- **Eventos:** Ninguno directamente.
- **Privilegios:** Único componente expuesto a internet.
- **Escalado:** Horizontal por CPU/memoria.
- **Controles SEC:** SEC-002, SEC-003, SEC-005, SEC-011, SEC-014, SEC-023, SEC-030.

### 2.2 `document-service`
- **Responsabilidad:** Orquestador del pipeline, carga idempotente, versiones.
- **Datos:** Base de datos relacional (silo por tenant).
- **API:** `POST /documents`, `GET /documents/{id}`.
- **Llamadas síncronas:** Llamada HTTP síncrona hacia `renderer` (mTLS).
- **Eventos:** Consume `documento.renderizado` (propio), `extraccion.completada` (de extraction-service), `extraccion.requiere_revision`, `revision.completada`. Publica `extraccion.solicitada` (comando), `extraccion.aprobada` (único productor autorizado), `documento.recibido`, `documento.rechazado`, `documento.purgado`.
- **Privilegios:** Escritura en el bucket del tenant. Guarda PNG y texto nativo extraído.
- **Escalado:** Horizontal. Equidad controlada por tope de concurrencia por tenant (bulkhead Resilience4j).
- **Controles SEC:** SEC-001, SEC-018, SEC-019, SEC-021, SEC-022, SEC-029, SEC-050.

### 2.3 `renderer`
- **Responsabilidad:** Validación (magic bytes), escaneo antivirus, rasterizado de PDF a PNG y extracción de capa de texto nativa si existe. Convierte DOCX a PDF con LibreOffice headless dentro del mismo sandbox (sin red, timeout y límite de tamaño y páginas, macros deshabilitadas) y luego rasteriza; imágenes JPG/PNG/TIFF pasan directo.
- **Datos:** Efímeros.
- **API:** HTTP síncrona (mTLS).
- **Eventos:** No usa Kafka.
- **Privilegios:** Sandbox aislado. Sistema de archivos de solo lectura. Sin credenciales. NetworkPolicy: ingress solo desde document-service, egress denegado (excepto CronJob freshclam hacia mirror interno).
- **Antivirus:** clamd como sidecar en el pod.
- **Escalado:** Horizontal intensivo en CPU.
- **Controles SEC:** SEC-024, SEC-025.

### 2.4 `extraction-service`
- **Responsabilidad:** Clasificación, extracción con LLM, validadores, calibración de score por campo, ruteo en cascada.
- **Datos:** Base local temporal por lote.
- **API:** Worker de eventos.
- **Eventos:** Consume `extraccion.solicitada`. Publica `extraccion.completada`, `extraccion.requiere_revision`, `ia.ejecucion_registrada`.
- **Privilegios:** Credenciales LLM.
- **Escalado:** Horizontal según cuota y límites de concurrencia por tenant hacia el LLM.
- **Controles SEC:** SEC-031, SEC-032, SEC-033, SEC-034, SEC-036, SEC-049, SEC-050.

### 2.5 `review-service`
- **Responsabilidad:** Cola de revisión manual (HITL), regla de cuatro ojos para campos críticos (monto, identificación, cuenta/producto, tipo de medida).
- **Datos:** `review_task`, `correction`.
- **API:** `GET /tasks`, `POST /tasks/{id}/approve`.
- **Eventos:** Consume `extraccion.requiere_revision`. Publica `revision.completada`.
- **Privilegios:** Validación de rol revisor y segundo aprobador distinto para campos críticos.
- **Escalado:** Bajo/Medio.
- **Controles SEC:** SEC-009, SEC-034, SEC-050.

### 2.6 `quality-service`
- **Responsabilidad:** Golden set con oficios sintéticos, muestreo ciego, deriva, calibración.
- **Datos:** Métricas QA.
- **API:** Interna y exportación de reportes (rol riesgo de modelo).
- **Eventos:** Consume `extraccion.aprobada` y `revision.completada`.
- **Privilegios:** Lectura golden set, evaluación independiente.
- **Escalado:** Bajo.
- **Controles SEC:** SEC-035.

### 2.7 `chat-service`
- **Responsabilidad:** RAG, pgvector, citación verificable. Abstención obligatoria ante información insuficiente. Búsqueda exacta por `document_id`.
- **Datos:** `chat_session`, `chunk`, `citation`.
- **API:** `POST /chat/{sessionId}/message`.
- **Eventos:** Consume `extraccion.aprobada`. Publica `chat.respuesta_bloqueada`, `chat.respuesta_desde_cache`, `seguridad.prompt_injection_detectado`.
- **Privilegios:** Lectura documentos y credenciales LLM.
- **Escalado:** Horizontal. Bulkhead por tenant.
- **Controles SEC:** SEC-001, SEC-004, SEC-007, SEC-031, SEC-033, SEC-038, SEC-048, SEC-050.

### 2.8 `notification-service`
- **Responsabilidad:** Webhooks HMAC seguros, reintentos DLT, validación anti-SSRF rigurosa (bloqueo RFC1918, loopback, CGNAT, metadata; DNS pinning; redirects deshabilitados).
- **Datos:** Configuración webhooks.
- **API:** CRUD de webhooks por tenant.
- **Eventos:** Consume `extraccion.aprobada`. Publica `webhook.entregado`, `webhook.fallido`.
- **Privilegios:** Única salida a internet (egress controlado).
- **Escalado:** Medio.
- **Controles SEC:** SEC-027, SEC-028, SEC-050.

### 2.9 `audit-service`
- **Responsabilidad:** Cadena de hash, anclaje WORM por tenant, firma asimétrica (ed25519) del expediente.
- **Datos:** WORM S3, metadatos hash.
- **API:** Exportación de expedientes (rol auditor).
- **Eventos:** Consume todos los eventos de dominio de forma bloqueante para mantener el orden.
- **Privilegios:** Escritor único de auditoría WORM y gestor de llave asimétrica de firma. Rol no revocable.
- **Escalado:** Alto.
- **Controles SEC:** SEC-021, SEC-039, SEC-040, SEC-042.

### 2.10 `tenant-service`
- **Responsabilidad:** Alta/baja tenants, KEK de datos y auditoría separadas, planes, cuotas, aprovisionamiento de recursos. Reporte trimestral de certificación de accesos.
- **Datos:** `tenants`, `planes`, `role_assignment` (base de control).
- **API:** `POST /tenants`, `PUT /tenants/{id}/config`.
- **Eventos:** Publica `tenant.aprovisionado`, `tenant.aprovisionamiento_fallido`, `tenant.baja_iniciada`, `consumo.registrado`, `cuota.umbral_alcanzado`.
- **Privilegios:** Red de administración. Llama a API de Kubernetes/Cloud para crear namespaces y silos físicos.
- **Escalado:** Bajo.
- **Controles SEC:** SEC-001, SEC-012, SEC-015, SEC-016, SEC-017, SEC-047, SEC-050.


### 2.11 Plataforma transversal (libs/security, CI, Kubernetes, observabilidad)
- **Responsabilidad:** Seguridad base, control de accesos, resiliencia, despliegue, observabilidad y auditoría general.
- **Controles SEC:** SEC-006, SEC-008, SEC-010, SEC-013, SEC-020, SEC-026, SEC-037, SEC-041, SEC-043, SEC-044, SEC-045, SEC-046.

## 3. Seguridad y Autorización Común

- **Identidad:** Keycloak actúa como broker OIDC en todos los destinos, federado con el IdP del banco. El token JWT emitido incluye el claim `tenant_id` y los roles.
- **Validación en servicios:** Cada servicio revalida tenant y rol contra `role_assignment` en cada request vía la biblioteca `libs/security`. Esta validación consulta la base de control de forma síncrona con una caché local máxima de 30 segundos, que se invalida inmediatamente al recibir un evento `acceso.revocado`. Ante fallos de autorización, se publica `seguridad.acceso_denegado`.
- **Break-glass:** Aprobador distinto requerido siempre. Genera eventos `breakglass.otorgado` y `breakglass.expirado`. Separación estricta entre administrador de llaves y acceso a contenido.
- **Clasificación de Documentos:** Público, Interno, Confidencial, Altamente Confidencial. Los oficios son Confidencial por defecto. Altamente Confidencial requiere aprobación de un Data Steward distinto del cargador.

## 4. Catálogo de Eventos

Patrón claim-check estricto: cero PII en eventos Kafka. Se usan identificadores y metadatos (SEC-050). Todo userId y recurso en eventos son identificadores opacos (sin PII).

| Nombre de Evento | Tipo | Productor Principal | Payload Claim-Check |
|---|---|---|---|
| `tenant.aprovisionado` | Evento | `tenant-service` | `tenantId`, `planId` |
| `tenant.aprovisionamiento_fallido`| Evento | `tenant-service` | `tenantId`, `razon` |
| `tenant.baja_iniciada` | Evento | `tenant-service` | `tenantId` |
| `documento.recibido` | Evento | `document-service` | `documentId`, `tenantId`, `hash` |
| `documento.renderizado` | Evento | `document-service` | `documentId`, `tenantId`, `pageCount` |
| `documento.rechazado` | Evento | `document-service` | `documentId`, `tenantId`, `motivo` |
| `documento.purgado` | Evento | `document-service` | `documentId`, `tenantId` |
| `extraccion.solicitada` | Comando | `document-service` | `documentId`, `tenantId`, `versionId` |
| `extraccion.completada` | Evento | `extraction-service` | `documentId`, `tenantId` |
| `extraccion.requiere_revision`| Evento | `extraction-service` | `documentId`, `taskId`, `tenantId` |
| `ia.ejecucion_registrada` | Evento | `extraction-service` | `documentId`, `modelVersion`, `promptVersion`, `configHash`, `signatureRef` (SEC-049) |
| `revision.completada` | Evento | `review-service` | `documentId`, `taskId`, `action` |
| `extraccion.aprobada` | Evento | `document-service` | `documentId`, `tenantId`, `finalScore` |
| `seguridad.acceso_denegado` | Evento | (Cualquier servicio) | `tenantId`, `userId`, `recurso` |
| `seguridad.prompt_injection_detectado`| Evento| `extraction-service`, `chat-service` | `documentId`, `tenantId` |
| `acceso.revocado` | Evento | `tenant-service` | `userId`, `tenantId` |
| `breakglass.otorgado` | Evento | `tenant-service` | `userId`, `approverId` |
| `breakglass.expirado` | Evento | `tenant-service` | `userId` |
| `legalhold.aplicado` | Evento | `tenant-service` | `documentId` / `tenantId` |
| `legalhold.liberado` | Evento | `tenant-service` | `documentId` / `tenantId` |
| `webhook.entregado` | Evento | `notification-service` | `webhookId`, `documentId` |
| `webhook.fallido` | Evento | `notification-service` | `webhookId`, `documentId`, `motivo` |
| `chat.respuesta_bloqueada` | Evento | `chat-service` | `sessionId`, `tenantId` |
| `chat.respuesta_desde_cache`| Evento | `chat-service` | `sessionId`, `tenantId` |
| `consumo.registrado` | Evento | `tenant-service` | `tenantId`, `unidades` |
| `cuota.umbral_alcanzado` | Evento | `tenant-service` | `tenantId`, `porcentaje` |

## 5. Topología Kafka y Patrón Outbox

- **Infraestructura:** Kafka en modo KRaft (sin Zookeeper).
- **Tópicos y Particiones:** Tópicos globales, particionados por `documentId`. Sin tópicos ni credenciales por tenant.
- **Tópico de Auditoría:** `auditoria.eventos` usa clave `tenantId`. Su consumidor implementa reintento BLOQUEANTE (sin `@RetryableTopic`) para no romper el orden de la hash-chain, disparando alerta en caso de fallo.
- **Seguridad (ACL):** Acceso estricto por `KafkaUser` de Strimzi para cada servicio.
- **Producción (Outbox):** Se usa una tabla `outbox` en la base de datos de cada tenant. Un proceso relay por servicio recorre las bases de datos de los tenants con `FOR UPDATE SKIP LOCKED`. Los tenants se reparten por hash entre las réplicas usando ShedLock. Se implementa Debezium diferido.
- **Consumo:** Idempotencia en cada consumidor.


### 5.1 Tabla de Tópicos Kafka

| Nombre de Tópico | Tipo | Clave | Particiones | Retención | Productores | Consumidores |
|---|---|---|---|---|---|---|
| `dominio.documentos` | Pipeline | `documentId` | 12 | 7 días | `document-service`, `extraction-service`, `review-service`, `tenant-service`, `notification-service`, `chat-service` | `document-service`, `extraction-service`, `review-service`, `notification-service`, `chat-service`, `quality-service`, `audit-service` |
| `auditoria.eventos` | Auditoría | `tenantId` | 6 | 365 días | Todos (para eventos exclusivos de seguridad) | `audit-service` |

El `audit-service` consume todos los tópicos de dominio con su propio consumer group independiente. Los eventos exclusivos de seguridad van al tópico `auditoria.eventos`. El orden de la cadena de hash (hash-chain) está determinado por el orden de ingesta en el `audit-service` y es serializado por tenant en su base de datos, no depende del orden de llegada o retención en Kafka. Al ingerir cada evento, `audit-service` asigna el número de secuencia de la cadena por tenant; esa secuencia (no el offset de Kafka) es la que entra al hash y se firma.

## 6. Modelo de Datos y Silos

- **Silos:** `libs/tenant-context` utiliza `AbstractRoutingDataSource`. Se crea un pool Hikari por tenant bajo demanda con credenciales dinámicas de OpenBao (TTL corto) y un tope de pools activos.
- **Bases de datos (PostgreSQL/pgvector):** Se utiliza `Cluster` CNPG compartido (nivel estándar) asignando una `Database` lógica por tenant. Para nivel dedicado, se aprovisiona un `Cluster` CNPG dedicado completo. CNPG gestiona instancias e integra Barman Cloud para archivado continuo de WAL (garantizando RPO).
- **Aprovisionamiento:** Los recursos de tenant se crean vía API administrativa en namespaces excluidos de Argo CD. La base de control es la fuente de verdad y un job de reconciliación mantiene el estado.

### 6.1 Base de Control (Compartida)
- `tenants`, `planes`, `cuotas`, `role_assignment`, `consumption_record`, `quota_period`.

### 6.2 Base por Tenant (Silo Físico o Lógico)
- `document`, `document_version`, `page`, `outbox`.
- `extraction`, `field_value`.
- `review_task`, `correction`.
- `chat_session`, `chunk`, `citation`, `webhook_subscription`, `webhook_delivery`.

## 7. Diagramas de Flujo (Mermaid)

### 7.1 Carga y Renderizado

```mermaid
sequenceDiagram
    participant Usuario as Usuario
    participant GW as edge-gateway
    participant DS as document-service
    participant RND as renderer
    
    Usuario->>GW: POST /documents (Carga PDF)
    GW->>DS: Ruteo válido (JWT OK)
    DS->>Storage: Guarda objeto PDF
    DS->>RND: POST /render (síncrono mTLS)
    RND-->>DS: Devuelve PNGs y texto nativo
    DS->>Storage: Guarda PNGs y texto
    DS->>Kafka: documento.recibido / documento.renderizado
```

### 7.2 Orquestación y Extracción

```mermaid
sequenceDiagram
    participant DS as document-service
    participant ES as extraction-service
    participant LLM as LLM Provider
    participant RS as review-service
    
    DS->>Kafka: extraccion.solicitada (Comando)
    Kafka->>ES: Consume comando
    ES->>LLM: Analiza con score por campo
    LLM-->>ES: JSON con datos
    ES->>ES: Calibración y segunda pasada (cascada)
    ES->>Kafka: extraccion.completada (y requiere_revision si falla validador/score)
    Kafka->>DS: Consume estado
    Kafka->>RS: Si requiere revisión
    RS->>RS: Revisión 4 ojos (campos críticos)
    RS->>Kafka: revision.completada
    Kafka->>DS: Actualiza estado general
    DS->>Kafka: extraccion.aprobada (único productor)
```

## 8. Máquina de Estados de Documentos

Los estados canónicos definidos en el producto:
`RECIBIDO` -> `RENDERIZADO` -> `EN_EXTRACCION` -> `EN_REVISION` -> `APROBADO` (o `RECHAZADO` / `FALLIDO`). Los documentos `ALTAMENTE_CONFIDENCIAL` pasan por `APROBADO_PENDIENTE_STEWARD` antes de `APROBADO`: el Data Steward (distinto de quien cargó) lo aprueba con `POST /v1/documents/{id}/approve-confidential`.

## 9. Puertos Java, Adaptadores y Cadena de Suministro

- `ObjectStore`: AWS S3, GCS, Blob nativo, o `s3-compatible-adapter` (Ceph RGW, SeaweedFS) on-prem.
- `ImmutableStore`: S3 Object Lock, Bucket Lock (GCP), Blob Immutability.
- `KeyService`: AWS KMS, Cloud KMS (GCP), OpenBao Transit.
- `LlmProvider`: Spring AI contra el modelo vigente homologado (Bedrock, Vertex AI, vLLM).
- **Crypto-shredding:** Para expurgar datos de un tenant o cumplir Habeas Data, se deshabilita la KEK de inmediato y se invalida la caché de DEK. Se purgan físicamente blob, páginas, chunks de índice y caché, publicando `documento.purgado`.
- **Frameworks:** Spring Boot (versión estable vigente). El sistema no usa JTA; las transacciones se manejan localmente con outbox.
- **Cadena de Suministro:** SAST con CodeQL o Semgrep y SpotBugs; SCA para código e imágenes con Trivy y Dependabot; SBOM formato CycloneDX; firmas con cosign; admisión con Kyverno (`verifyImages`).
