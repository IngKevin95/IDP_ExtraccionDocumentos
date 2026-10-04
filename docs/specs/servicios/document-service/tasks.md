# Tareas de Implementación: Document Service

| ID Tarea | Descripción | Criterio de Hecho | Escenario / Test de Validación |
|---|---|---|---|
| `T-01` | Estructura esqueleto Maven `services/document-service` y dependencias de `libs/`. | Módulo compila exitosamente con Java 21 y Spring Boot. | `mvn clean compile` en `services/document-service`. |
| `T-02` | Migración Flyway `V1__init_document_schema.sql` (tablas `document`, `page_artifact`, `outbox_event`). | Tablas e índices creados correctamente en PostgreSQL. | Test de integración de Flyway con Testcontainers Postgres. |
| `T-03` | Entidades JPA y repositorios para `DocumentEntity`, `PageArtifactEntity` y `OutboxEventEntity`. | Mapeo JPA válido con restricciones de unicidad compuestas e índices. | `DocumentRepositoryTest` (guardado, búsqueda por hash y tupla de negocio). |
| `T-04` | Servicio de validación fail-fast de archivos (Magic Bytes, mime-type, limite 50MB). | Archivos no permitidos o corruptos son rechazados inmediatamente. | `FileValidationServiceTest` y `AC-02`. |
| `T-05` | Ingesta de documentos con cálculo de hash SHA-256 e idempotencia por hash y negocio (RN-01, SEC-029). | Carga de documento duplicado retorna HTTP 200 con metadata existente. | `DocumentIngestionServiceTest` y `AC-01`. |
| `T-06` | Adaptador síncrono HTTP hacia `renderer` sandbox con Retry / Circuit Breaker. | Invocación exitosa persiste artefactos de páginas; fallas transitan a estado `FALLIDO`. | `HttpRendererAdapterTest`, `AC-03`, `AC-04`. |
| `T-07` | Máquina de estados del documento y outbox pattern transaccional. | Transiciones válidas emiten eventos a `outbox_event`; transiciones inválidas lanzan excepción. | `DocumentStateMachineServiceTest`, `AC-03`. |
| `T-08` | Oyentes de eventos Kafka (`extraccion.completada`, `extraccion.requiere_revision`, `revision.completada`). | Actualización automática de estado a `APROBADO` o `EN_REVISION` según el evento recibido. | `ExtractionEventListenerTest`, `AC-05`, `AC-06`. |
| `T-09` | Endpoint `/v1/documents/{documentId}/approve-confidential` para flujo de Data Steward. | Documento "Altamente Confidencial" transita a `APROBADO` solo con rol adecuado. | `DocumentControllerSecurityTest`, `AC-07`. |
| `T-10` | Endpoint `DELETE /v1/documents/{documentId}` (Purga Habeas Data SEC-022). | Borrado físico en `ObjectStore`, tombstone en BD y emisión de `documento.purgado`. | `DocumentPurgeServiceTest`, `AC-09`. |
| `T-11` | Endpoints de consulta (`GET /v1/documents/{id}`, `GET /v1/documents/{id}/download`, `GET /v1/documents`). | Respuestas paginadas, URL firmada temporal, aislamiento por tenant (404 estricto SEC-003). | `DocumentControllerIntegrationTest`, `AC-08`. |
| `T-12` | Verificación de Contratos OpenAPI y Eventos JSON Schema. | OpenAPI YAML y esquemas JSON Schema pasan validación estricta en CI. | OpenAPI Validator Test y EventSchemaValidatorTest. |
