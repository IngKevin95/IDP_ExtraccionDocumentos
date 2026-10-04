# Especificación: Document Service

## Propósito
El `document-service` es el orquestador principal del ciclo de vida de los oficios dentro del IDP Bancario. Centraliza la ingesta (con validación de idempotencia), gestiona el estado canónico de los documentos, interactúa de forma síncrona con el sandbox de rasterizado (`renderer`), controla el almacenamiento en el bucket de cada tenant, y emite los eventos orquestadores tras resoluciones positivas o negativas en el resto del pipeline. También es responsable de aplicar purgas por Habeas Data y aplicar la protección de niveles de confidencialidad.

## Alcance y no alcance

**En alcance:**
- Ingesta idempotente de oficios y almacenamiento en el silo de objetos del tenant.
- Gestión de versiones de documentos en caso de reproceso justificado o asociación documental.
- Orquestación de estados de dominio (máquina de estados).
- Invocación síncrona al microservicio `renderer` vía mTLS.
- Gestión de clasificación de confidencialidad y aprobación de documentos "Altamente Confidencial".
- Purga completa por Habeas Data (eliminación física del binario, representaciones y datos de la base).
- Paginación, listado y búsqueda básica de metadatos (no full-text search).

**Fuera de alcance:**
- Renderizado de PDF/Imágenes (delegado a `renderer`).
- Extracción con LLM o clasificación de tipología (delegado a `extraction-service`).
- Flujo de colas de revisión humana (delegado a `review-service`).
- Trazabilidad criptográfica WORM (delegado a `audit-service`).
- Autenticación o control de cuotas/rate limiting general (delegado a IdP, `edge-gateway` y `tenant-service`).

## Requisitos cubiertos
- **RF-101:** Estados canónicos (RECIBIDO, RECHAZADO, RENDERIZADO, EN_EXTRACCION, EN_REVISION, APROBADO, FALLIDO).
- **RF-102:** Ingesta idempotente y validación fail-fast (tipología, radicado, versión, hash).
- **RF-103:** Sandbox de rasterizado (llamada al pod aislado).
- **RF-603:** Purga y Habeas Data (evento `documento.purgado`).
- **RNF-101:** Aislamiento Multitenant (bucket de objetos por tenant).
- **RN-01:** Unicidad estricta.

## Reglas
- **Regla 1 (Idempotencia):** Todo oficio ingresado es sujeto a verificación de hash (SHA-256) y tupla (tipo, radicado, versión). Si ya existe, se devuelve el estado actual sin reprocesar.
- **Regla 2 (Máquina de Estados Inmutable):** Un documento no puede transitar de APROBADO a EN_REVISION. Las transiciones son unidireccionales dentro del pipeline.
- **Regla 3 (Aislamiento de Carga):** No se lee el binario del documento para fines analíticos; se inyecta directamente como flujo al bucket temporal o se envía de forma síncrona al `renderer` validando su límite (magic bytes, tamaño).
- **Regla 4 (Altamente Confidencial):** Si el payload de ingestión marca o si posteriormente el sistema clasifica al documento como "Altamente Confidencial", no transitará a APROBADO sin que un `Data Steward` distinto al cargador emita una aprobación explícita.
- **Regla 5 (Tolerancia a fallos de Renderer):** El fallo en la llamada a `renderer` envía el documento a FALLIDO, tras reintentos exponenciales locales.

## Contrato

**Endpoints (referencia: `contracts/openapi/document-service.yaml`)**
- `POST /v1/documents`: Ingesta un nuevo oficio. Retorna HTTP 201 o HTTP 200 (si es idempotente).
- `GET /v1/documents/{documentId}`: Retorna metadatos del oficio (claim-check).
- `GET /v1/documents/{documentId}/download`: Retorna URL firmada temporal (presigned URL) para descargar el binario (si el rol lo permite).
- `POST /v1/documents/{documentId}/approve-confidential`: Endpoint para el Data Steward.
- `DELETE /v1/documents/{documentId}`: Dispara purga por Habeas Data.
- `GET /v1/documents`: Listado con paginación (offset/limit) y filtrado básico (estado, fecha).

**Eventos Publicados (referencia: `contracts/events/`):**
- `documento.recibido`: Payload inicial con metadatos (estructura plana, sin PII, `eventId`, `tenantId`).
- `documento.renderizado`: Incluye el array de object keys de las páginas PNG generadas en el bucket.
- `documento.rechazado`: Por fallo de antivirus, magic bytes inválidos o límites (motivos en `reasonCode`).
- `extraccion.aprobada`: Estado terminal positivo, habilita notificación e indexación.
- `documento.purgado`: Evento de tombstone para disparar el crypto-shredding.

**Eventos Consumidos:**
- `extraccion.completada` (desde `extraction-service`).
- `extraccion.requiere_revision` (desde `extraction-service`).
- `revision.completada` (desde `review-service`).

*Nota:* Todos los eventos cumplen con SEC-050 (sin PII, uso de UUIDs como claim-check).

## Modelo de datos

Base de datos: **Silo Lógico por Tenant (PostgreSQL)**

| Tabla | Columna Clave | Descripción | Índices |
|---|---|---|---|
| `document` | `id` (UUID PK) | Tabla maestra del oficio | PK. Índice en `(tenant_id, status)` |
| `document` | `hash_sha256` (Varchar 64) | Validación de duplicados por archivo | Índice único parcial por tenant |
| `document` | `typology`, `radicado`, `version` | Tupla de negocio (RN-01) | Índice único parcial por tenant (compuesto) |
| `document` | `status` (Varchar 20) | Estado canónico del pipeline | |
| `document` | `classification` (Varchar 20)| Público, Interno, Confidencial, Alt. Conf. | |
| `document` | `object_store_key` (Varchar) | Referencia al bucket S3/Blob | |
| `outbox_event`| `id` (UUID) | Patrón outbox para Kafka | PK. Índice en `(processed, created_at)` |

## Controles de seguridad

- **SEC-001 (Silo de datos por tenant):** La persistencia usa un `tenant-context` (ThreadLocal o Reactor Context) para resolver el datasource lógico y el prefijo de KEK/Bucket.
- **SEC-003 (Errores genéricos):** Solicitar un documento de otro tenant vía GET devuelve HTTP 404 estricto.
- **SEC-018 (Clasificación):** Todo oficio ingresa por defecto como `Confidencial`. La consulta al binario valida permisos a nivel de controlador.
- **SEC-022 (Purga verificable):** El DELETE ejecuta borrado en Object Storage y actualización física a null de los campos (tombstone) antes de emitir el evento `documento.purgado`.
- **SEC-023 (Magic bytes y tamaño):** El pre-procesamiento del `POST /v1/documents` corta la conexión si el InputStream excede 50MB o si la cabecera mágica no corresponde a PDF, DOCX, PNG, JPG, o TIFF.
- **SEC-029 (Idempotencia):** Garantizada vía restricción unique a nivel DB (`hash_sha256`) combinada con un UPSERT silencioso (ON CONFLICT DO NOTHING o retorno del existente).
- **SEC-050 (Claim check en Kafka):** Los eventos generados solo contienen el `documentId` y el `tenantId`.

## Escenarios de aceptación

- **AC-01 (Idempotencia exitosa):** 
  - *Given* un oficio con hash X previamente procesado en estado EN_EXTRACCION.
  - *When* se intenta crear de nuevo el documento con el mismo binario.
  - *Then* se retorna HTTP 200 (en vez de 201), el identificador original, y no se dispara ningún procesamiento nuevo.
- **AC-02 (Validación magic bytes - Archivo malicioso):** 
  - *Given* un archivo de texto renombrado a `.pdf`.
  - *When* se intenta cargar vía la API.
  - *Then* el sistema rechaza la petición por error de magic bytes (HTTP 415 o 422), no persiste en bucket y el oficio queda o ni nace como RECHAZADO.
- **AC-03 (Orquestación del renderer):** 
  - *Given* un archivo PDF válido recibido.
  - *When* el documento se guarda en el bucket.
  - *Then* invoca síncronamente al `renderer`, persiste las URLs temporales de las imágenes resultantes en BD, transita a RENDERIZADO, y emite `documento.renderizado` por el outbox.
- **AC-04 (Manejo de caída del renderer):**
  - *Given* el `renderer` está indisponible (Circuit Breaker abierto o 500s).
  - *When* se ingresa un documento.
  - *Then* tras agotar los reintentos transita a FALLIDO y emite `documento.rechazado` con taxonomía de error interna.
- **AC-05 (Consumo de extraccion completada con auto-aprobación):** 
  - *Given* un documento en EN_EXTRACCION.
  - *When* se consume el evento `extraccion.completada`.
  - *Then* actualiza su estado a APROBADO y emite `extraccion.aprobada`.
- **AC-06 (Consumo de requiere revisión):** 
  - *Given* un documento en EN_EXTRACCION.
  - *When* se consume el evento `extraccion.requiere_revision`.
  - *Then* actualiza su estado a EN_REVISION y cesa su actividad hasta una resolución externa.
- **AC-07 (Aprobación Altamente Confidencial restringida):** 
  - *Given* un documento clasificado como Altamente Confidencial en estado APROBADO_PENDIENTE_STEWARD.
  - *When* el Data Steward aprueba mediante `/v1/documents/{documentId}/approve-confidential`.
  - *Then* transita definitivamente a APROBADO y emite `extraccion.aprobada`.
- **AC-08 (Aislamiento de lectura cruzada):** 
  - *Given* un usuario autenticado perteneciente al Tenant A.
  - *When* intenta acceder al endpoint `GET /v1/documents/UUID-DEL-TENANT-B`.
  - *Then* la respuesta es un 404 estricto (SEC-003).
- **AC-09 (Purga verificable por Habeas Data):**
  - *Given* una solicitud de eliminación `DELETE /v1/documents/{documentId}` autorizada.
  - *When* se procesa la solicitud.
  - *Then* se borra físicamente del Object Storage, se actualizan los campos a null en BD (tombstone) y se emite `documento.purgado` (SEC-022).

## Métricas y SLO
- **SLO Latencia de API (POST):** p95 < 2.5s (incluyendo la subida al bucket y la llamada síncrona al renderer en la ruta crítica inicial).
- **SLO de Disponibilidad API:** 99.9%.
- **Métrica personalizada:** Tasa de oficios rechazados por tamaño / formato incorrecto (`idp_document_rejected_total`).
- **Métrica personalizada:** Tiempos de transición entre estados (`idp_document_transition_time_seconds`).

## Dependencias
- `renderer` (Llamada HTTP síncrona).
- `ObjectStore` (Puerto abstracto hacia S3/GCS/Azure Blob).
- DB PostgreSQL local (Tenant).
- `Kafka` (publicación mediante `Outbox`).
- `tenant-service` (contexto de seguridad / llave KMS al arranque de la petición, operado mediante caché local y JWT filters).