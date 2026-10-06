# Plan de Implementación: Quality Service

## Módulo Maven
* **Ruta:** `services/quality-service`
* **Dependencias Core:** `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-kafka`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`.
* **Dependencias Internas:** `libs/tenant-context`, `libs/security-utils`, `libs/kafka-events` (para consumir esquemas compartidos).

## Paquetes Principales
* `com.banco.idp.quality.controller`: Controladores REST para la API de métricas y administración de Golden Set.
* `com.banco.idp.quality.service`: Lógica de agregación de métricas, cálculo de deriva y gestión del Golden Set.
* `com.banco.idp.quality.kafka`: *Listeners* Kafka para eventos de extracción y revisión.
* `com.banco.idp.quality.repository`: Repositorios Spring Data JPA para las entidades de calidad.
* `com.banco.idp.quality.model`: Entidades JPA y DTOs (Métricas diarias, Golden Set).
* `com.banco.idp.quality.config`: Configuración de Kafka (consumer groups), seguridad web y cache de métricas.

## Clases Principales
* `MetricsAggregatorService`: Calcula y acumula las tasas de STP y Error Silente, ignorando PII de los payloads crudos.
* `GoldenSetEvaluator`: Gestiona el ciclo asíncrono para probar *prompts* contra oficios ficticios.
* `QualityEventConsumer`: Listener idempotente (`@KafkaListener`) para los tópicos.
* `DriftDetectionService`: Evalúa caídas de precisión comparando métricas recientes vs datos históricos.

## Configuración Spring (`application.yml`)
* Configuración estándar de base de datos apuntando a la base de Control compartida (`spring.datasource.url`).
* Configuración de propiedades Kafka:
  * `spring.kafka.consumer.group-id: quality-service-group`
  * `spring.kafka.consumer.auto-offset-reset: earliest`
  * Deserialización JSON.
* Roles de seguridad JWT configurados para requerir rol `DATA_STEWARD` en la API REST.

## Publicación de eventos (outbox)
* `quality-service` publica `calidad.muestra_ciega_solicitada.v1` con `libs/events` (`OutboxRepository`, `JdbcOutboxPublisher`, `OutboxRelay`). Como no tiene silo por tenant, el outbox vive en la base de control (esquema `quality`) y `TenantOutboxAccess` opera siempre sobre esa base; el relay (`quality.relay.enabled`, `quality.relay.interval`) publica en `dominio.documentos` con clave `documentId` y cabecera `tenantId`.
* `MetricsIngestService` registra la selección (`qa_blind_sample.sample_id`) y publica en la misma transacción del consumidor idempotente.

## Migraciones Flyway
* **Ruta:** `services/quality-service/src/main/resources/db/migration`
* `V1__init_quality_schema.sql`: Creación de tablas `qa_metrics_daily`, `qa_field_error`, `golden_set_document`, `golden_set_evaluation`.
* `V2__outbox_and_sample_id.sql`: tabla `outbox` y columna `qa_blind_sample.sample_id`.
* Creación de índices en las tablas de métricas por `tenant_id` y `fecha` para consultas rápidas de los dashboards.

## Adaptadores
* **Kafka Consumer Adapter:** Deserializa eventos del dominio y maneja reintentos locales si la DB de control está temporalmente inaccesible.
* **LLM Provider Adapter (Opcional):** Si la evaluación del Golden Set dispara ejecuciones, se conectará al puerto interno de abstracción de LLM para pruebas determinísticas de calibración.

## Estrategia de Tests
* **Unitarios:**
  * Cobertura de cálculos estadísticos (precisión, recall, tasas).
  * Validación de que la lógica de parseo de eventos filtra y descarta cualquier información personal en texto plano.
* **Integración (Testcontainers):**
  * **Kafka:** Enviar mensajes simulados de `revision.completada` y validar que se incrementan los contadores en base de datos.
  * **PostgreSQL:** Validar persistencia y agrupamiento (`GROUP BY`) en las consultas de series de tiempo.
* **Contrato:**
  * Uso de OpenAPI Validator para asegurar que el `GET /v1/quality/reports/*` expone exactamente los esquemas declarados en `contracts/openapi/quality-service.yaml`.