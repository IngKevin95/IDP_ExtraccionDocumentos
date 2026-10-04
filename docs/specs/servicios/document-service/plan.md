# Plan de Implementación: Document Service

## 1. Módulo Maven y Estructura de Paquetes

- **Ubicación:** `services/document-service`
- **Artefacto:** `com.banco.idp.document:document-service`
- **Módulos Libs dependientes:**
  - `libs/tenant-context` (gestión de tenant actual en ThreadLocal/Reactor)
  - `libs/events` (modelos base para outbox y eventos de dominio)
  - `libs/storage-port` (puerto `ObjectStore` y adaptadores S3/Blob)
  - `libs/security` (filtros JWT, resolución de `TenantContext`, utilidades de seguridad)

### Paquetes Principales:
```
com.banco.idp.document/
├── config/
│   ├── SecurityConfig.java
│   ├── StorageConfig.java
│   ├── KafkaConfig.java
│   └── WebClientConfig.java
├── domain/
│   ├── model/
│   │   ├── Document.java
│   │   ├── DocumentStatus.java
│   │   ├── ClassificationLevel.java
│   │   └── PageArtifact.java
│   ├── port/
│   │   ├── RendererClientPort.java
│   │   └── EventPublisherPort.java
│   └── service/
│       ├── DocumentIngestionService.java
│       ├── DocumentStateMachineService.java
│       ├── DocumentPurgeService.java
│       └── DocumentQueryService.java
├── infrastructure/
│   ├── adapter/
│   │   ├── renderer/
│   │   │   ├── HttpRendererAdapter.java
│   │   │   └── dto/
│   │   │       ├── RenderRequest.java
│   │   │       └── RenderResponse.java
│   │   └── messaging/
│   │       ├── KafkaOutboxEventPublisher.java
│   │       └── listener/
│   │           ├── ExtractionCompletedListener.java
│   │           ├── ExtractionRequiresReviewListener.java
│   │           └── ReviewCompletedListener.java
│   └── persistence/
│       ├── entity/
│       │   ├── DocumentEntity.java
│       │   ├── PageArtifactEntity.java
│       │   └── OutboxEventEntity.java
│       └── repository/
│           ├── DocumentJpaRepository.java
│           └── OutboxEventJpaRepository.java
└── web/
    ├── controller/
    │   └── DocumentController.java
    ├── dto/
    │   ├── DocumentIngestRequest.java
    │   ├── DocumentResponse.java
    │   └── ErrorResponse.java
    └── exception/
        ├── GlobalExceptionHandler.java
        ├── DuplicateDocumentException.java
        ├── InvalidFileFormatException.java
        └── DocumentNotFoundException.java
```

---

## 2. Configuración Spring y Adaptadores

### Configuración General (`application.yml`):
- `spring.datasource`: PostgreSQL multi-tenant resolve vía `TenantContextHolder` empleando esquema/silo de base de datos por tenant o esquema discriminado según directriz de `libs/tenant-context`.
- `spring.kafka`: Producción transaccional de outbox con serialización JSON de metadatos del evento ( claim-check SEC-050).
- `services.renderer.url`: Endpoint mTLS del servicio `renderer`.
- `storage.provider`: Adaptador de `ObjectStore` según nube (AWS, GCP, Azure, Self-Hosted).

### Adaptadores:
- `HttpRendererAdapter`: Cliente REST/WebClient con mTLS y resiliencia (CircuitBreaker y Retry con Resilience4j) para enviar el InputStream/bucket key del documento y recibir las referencias a imágenes de páginas rasterizadas.
- `KafkaOutboxEventPublisher`: Persiste el evento en la tabla `outbox_event` en la misma transacción de BD de los cambios en `document`. Un Scheduler procesa eventos pendientes y los envía a los tópicos Kafka correspondientes (`documento.recibido.v1`, `documento.renderizado.v1`, etc.).

---

## 3. Migraciones Flyway

- **Ubicación:** `services/document-service/src/main/resources/db/migration/V1__init_document_schema.sql`

```sql
CREATE TABLE document (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    hash_sha256 VARCHAR(64) NOT NULL,
    typology VARCHAR(32) NOT NULL,
    radicado VARCHAR(64) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    classification VARCHAR(32) NOT NULL DEFAULT 'CONFIDENCIAL',
    object_store_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_doc_tenant_hash ON document (tenant_id, hash_sha256);
CREATE UNIQUE INDEX idx_doc_tenant_business ON document (tenant_id, typology, radicado, version);
CREATE INDEX idx_doc_tenant_status ON document (tenant_id, status);

CREATE TABLE page_artifact (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    page_number INT NOT NULL,
    object_store_key VARCHAR(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_page_document ON page_artifact (document_id);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_processed_created ON outbox_event (processed, created_at);
```

---

## 4. Estrategia de Tests

- **Tests Unitarios:**
  - `DocumentIngestionServiceTest`: Verificación de idempotencia, detección de duplicados (hash y tupla de negocio), manejo de fallos del `renderer`.
  - `DocumentStateMachineServiceTest`: Verificación de reglas de transición inmutable (ej. no permitir APROBADO -> EN_REVISION).
  - `FileValidationServiceTest`: Comprobación de magic bytes (PDF, DOCX, PNG, JPG, TIFF) y restricción de 50MB.

- **Tests de Integración con Testcontainers:**
  - `DocumentControllerIntegrationTest`: End-to-end simulado usando PostgreSQL y MockServer/WireMock para el servicio `renderer`.
  - `TenantIsolationIntegrationTest`: Verificación de aislamiento donde un tenant A no puede consultar o manipular documentos del tenant B (HTTP 404 estricto SEC-003).
  - `HabeasDataPurgeIntegrationTest`: Comprobación de borrado físico del registro y binarios en Object Storage ficticio + emisión de `documento.purgado.v1`.

- **Tests de Contrato:**
  - Verificación del contrato OpenAPI `contracts/openapi/document-service.yaml` mediante OpenAPI Validator / RestAssured.
  - Verificación de esquemas de eventos contra `contracts/events/*.v1.schema.json`.
