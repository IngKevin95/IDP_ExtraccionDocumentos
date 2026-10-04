# Plan de Implementación: Servicio de Auditoría (audit-service)

## 1. Configuración del Módulo
* **Módulo Maven:** `services/audit-service`
* **Dependencias Principales:**
  * `spring-boot-starter-web`
  * `spring-boot-starter-data-jpa`
  * `spring-boot-starter-security`
  * `spring-boot-starter-validation`
  * `spring-kafka`
  * `libs/tenant-context` (gestión de contexto e identificación del tenant)
  * `libs/storage-port` (puerto `ImmutableStore` para anclaje WORM en almacenamiento S3/Blob inmutable)
  * `libs/kms-port` (puerto `KeyService` e integración con OpenBao Transit para firma asimétrica ed25519)
  * `libs/security` (validación de JWT y roles de auditoría `ROLE_AUDITOR`)

## 2. Estructura de Paquetes
* `com.banco.idp.audit`
  * `.config`: Configuración de Kafka Listeners (error handler bloqueante), Spring Security, Swagger/OpenAPI, beans de KMS y WORM Storage.
  * `.domain.model`: Entidades JPA `AuditEntry`, `WormAnchor`, `LegalHoldRecord`, enums `LegalHoldStatus`, `EventType`.
  * `.domain.repository`: Interfaces Spring Data JPA (`AuditEntryRepository`, `WormAnchorRepository`, `LegalHoldRecordRepository`).
  * `.application.service`: Lógica principal (`AuditIngestionService`, `HashChainService`, `WormAnchorService`, `DossierService`, `AuditVerificationService`, `LegalHoldService`).
  * `.application.port.in`: Casos de uso (`IngestEventUseCase`, `ExportDossierUseCase`, `VerifyChainUseCase`, `ManageLegalHoldUseCase`).
  * `.application.port.out`: Interfaces hacia infraestructura externa (`WormStoragePort`, `AsymmetricSigningPort`, `AuditEventPublisher`).
  * `.infrastructure.in.web`: Controladores REST (`AuditController`, `LegalHoldController`, DTOs de solicitud y respuesta).
  * `.infrastructure.in.event`: Consumidor Kafka bloqueante (`AuditDomainEventConsumer`).
  * `.infrastructure.out.storage`: Adaptador WORM Object Lock (`S3WormStorageAdapter`).
  * `.infrastructure.out.kms`: Adaptador OpenBao Transit (`OpenBaoTransitSigningAdapter`).

## 3. Clases Principales y Responsabilidades
* **`AuditIngestionService`**: Ingesta síncrona y bloqueante de eventos. Mantiene la secuencia strictly incremental (`sequence_id`) por tenant. Recupera el `previous_hash` del último registro del tenant, calcula el `current_hash` mediante `HashChainService`, persiste en la tabla `audit_entries` y confirma el offset a Kafka. Si detecta discrepancia en la cadena o falla en la persistencia, bloquea el procesamiento (AC-01, AC-02, AC-06).
* **`HashChainService`**: Componente de cálculo criptográfico. Aplica SHA-256 a la estructura canónica formada por `payload_json` + `sequence_id` + `previous_hash` + `tenant_id` + `event_type`.
* **`WormAnchorService`**: Componente programado (Scheduled) o por umbral de eventos. Agrupa registros donde `worm_anchored = false`, genera un manifiesto comprimido JSON, solicita la firma asimétrica (ed25519) a `AsymmetricSigningPort` (OpenBao Transit), sube el archivo al bucket WORM del tenant en modo Compliance (SEC-021), registra el `WormAnchor` y actualiza `worm_anchored = true` (AC-03).
* **`DossierService`**: Compila la traza histórica completa para un `documentId`. Extrae la subsecuencia de eventos, valida la continuidad de los hashes, solicita la firma digital del compilado al KMS y retorna el expediente en formato JSON firmado (AC-04).
* **`AuditVerificationService`**: Endpoint de auditoría forense (`/v1/audit/verify`). Recorre de forma secuencial los registros de la base de datos y los anclajes WORM, recalculando la hash-chain. Si detecta alteración o salto en los datos, emite la alerta `seguridad.alerta_integridad` y retorna un reporte detallado del fallo (AC-02, SEC-039).
* **`LegalHoldService`**: Gestiona las solicitudes de preservación legal (`/v1/audit/legal-holds`). Persiste el estado de Legal Hold por documento o tenant completo en `legal_hold_records` y emite `legalhold.aplicado` o `legalhold.liberado` (AC-08, SEC-042).
* **`AuditDomainEventConsumer`**: Consumidor de Kafka multi-tópico (`dominio.documentos`, `auditoria.eventos`). Configurado con `DefaultErrorHandler` de Spring Kafka sin Dead Letter Topic (DLT), obligando al reintento infinito y detención de la partición ante fallos.

## 4. Migraciones Flyway (`src/main/resources/db/migration/control`)
Ubicadas en la Base de Control compartida (esquema `audit_schema`):
* `V1__create_audit_tables.sql`:
  * Tabla `audit_entries`:
    * `id` (UUID, PK)
    * `sequence_id` (BIGINT, NOT NULL)
    * `tenant_id` (UUID, NOT NULL)
    * `correlation_id` (UUID)
    * `document_id` (UUID)
    * `event_type` (VARCHAR(100), NOT NULL)
    * `actor_id` (VARCHAR(100))
    * `payload` (JSONB, NOT NULL)
    * `current_hash` (VARCHAR(64), NOT NULL)
    * `previous_hash` (VARCHAR(64), NOT NULL)
    * `worm_anchored` (BOOLEAN DEFAULT FALSE, NOT NULL)
    * `created_at` (TIMESTAMP WITH TIME ZONE NOT NULL)
    * Índice único: `idx_audit_entries_tenant_seq` ON `(tenant_id, sequence_id)`
    * Índice: `idx_audit_entries_tenant_doc` ON `(tenant_id, document_id)`
    * Índice: `idx_audit_entries_tenant_worm` ON `(tenant_id, worm_anchored)`
  * Tabla `worm_anchors`:
    * `anchor_id` (UUID, PK)
    * `tenant_id` (UUID, NOT NULL)
    * `start_sequence_id` (BIGINT, NOT NULL)
    * `end_sequence_id` (BIGINT, NOT NULL)
    * `file_uri` (VARCHAR(500), NOT NULL)
    * `manifest_hash` (VARCHAR(64), NOT NULL)
    * `signature` (TEXT, NOT NULL)
    * `created_at` (TIMESTAMP WITH TIME ZONE NOT NULL)
    * Índice: `idx_worm_anchors_tenant` ON `(tenant_id, start_sequence_id)`
  * Tabla `legal_hold_records`:
    * `id` (UUID, PK)
    * `tenant_id` (UUID, NOT NULL)
    * `document_id` (UUID)
    * `reason` (TEXT, NOT NULL)
    * `applied_by` (VARCHAR(100), NOT NULL)
    * `status` (VARCHAR(30), NOT NULL)
    * `created_at` (TIMESTAMP WITH TIME ZONE NOT NULL)
    * Índice: `idx_legal_hold_tenant_doc` ON `(tenant_id, document_id)`

## 5. Estrategia de Tests

### 5.1 Unitarios (`src/test/java/.../unit`)
* **`HashChainServiceTest`**: Verificación de inmutabilidad y consistencia del cálculo SHA-256.
* **`AuditIngestionServiceTest`**: Prueba de encadenamiento secuencial y rechazo si el hash previo no corresponde a la secuencia activa.
* **`LegalHoldServiceTest`**: Verificación de reglas de aplicación y liberación de preservación legal.

### 5.2 Integración y Componente (`src/test/java/.../integration`)
* **Testcontainers (PostgreSQL & Kafka & LocalStack/S3 WORM Mock)**: Levantar contexto Spring completo.
* **Consumo Bloqueante (AC-06)**: Simular fallo temporal en la BBDD de control y verificar que Kafka no avanza el offset ni descarta mensajes.
* **Aislamiento Multitenant (AC-05)**: Procesamiento concurrente de eventos para Tenant A y Tenant B, verificando que sus `sequence_id` y cadenas `previous_hash` no se interfieran.
* **Anclaje WORM (AC-03)**: Ejecutar el trigger de anclaje, verificar creación del archivo JSON en S3 WORM Mock, llamada al cliente de firma ed25519 y actualización de estado `worm_anchored`.
* **Exportación y Firma de Expediente (AC-04)**: Consultar endpoint `/v1/audit/dossiers/{documentId}` y validar la firma asimétrica devuelta en la respuesta.
* **Verificación Forense de Integridad (AC-02)**: Alterar deliberadamente una fila en la base de datos de pruebas y ejecutar `/verify`, confirmando que el endpoint reporta la corrupción y emite la alerta correspondiente.

### 5.3 Pruebas de Contrato
* Validar que la API cumple con el contrato OpenAPI `contracts/openapi/audit-service.yaml`.
* Validar los esquemas de eventos publicados (`legalhold.aplicado.v1.schema.json` y `auditoria.alerta_integridad.v1.schema.json`) garantizando que no contengan PII (SEC-050).