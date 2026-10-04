# Plan de Implementación: Review Service

## 1. Configuración del Módulo
* **Módulo Maven:** `services/review-service`
* **Dependencias Principales:**
  * `spring-boot-starter-web`
  * `spring-boot-starter-data-jpa`
  * `spring-boot-starter-security`
  * `spring-boot-starter-validation`
  * `spring-kafka`
  * `libs/tenant-context` (gestión de silos y DataSource routing)
  * `libs/kafka-outbox` (librería compartida para patrón outbox, si existe, o implementación local)

## 2. Estructura de Paquetes
* `com.banco.idp.review`
  * `.config`: Configuración de Kafka, Seguridad y Swagger/OpenAPI.
  * `.domain.model`: Entidades JPA `ReviewTask`, `Correction`, enum `TaskStatus`.
  * `.domain.repository`: Interfaces de Spring Data JPA.
  * `.application.service`: Lógica de negocio (ej. `ReviewTaskService`).
  * `.application.port.in`: Interfaces de casos de uso (ej. `ApproveTaskUseCase`).
  * `.application.port.out`: Interfaces hacia infraestructura externa (ej. `ReviewEventPublisher`).
  * `.infrastructure.in.web`: Controladores REST (`ReviewTaskController`, DTOs).
  * `.infrastructure.in.event`: Consumidores de Kafka (`ExtractionEventConsumer`).
  * `.infrastructure.out.event`: Publicación de eventos en tabla Outbox.

## 3. Clases Principales y Responsabilidades
* **`ReviewTaskService`**: Implementa los flujos de estado de las tareas. Orquesta la validación de la regla de cuatro ojos (SEC-009) comparando campos críticos.
* **`CriticalFieldsConfig`**: Componente que mantiene el listado (o configuración en `application.yml`) de los campos considerados críticos (`monto`, `identificacion`, `cuenta`, `producto`, `tipo_medida`).
* **`ExtractionEventConsumer`**: Consumidor idempotente (usa `taskId` como constraint o lógica de verificación previa en BBDD) para el evento `extraccion.requiere_revision`.
* **`ReviewTaskController`**: Expone los endpoints, maneja el mapeo a DTOs y propaga el ID del usuario extraído de Spring Security `JwtAuthenticationToken`.
* **`OutboxEventEntity` / `OutboxRepository`**: Entidad para grabar atómicamente el evento `revision.completada` dentro de la transacción JPA, cumpliendo el patrón transaccional Outbox.

## 4. Migraciones Flyway (`src/main/resources/db/migration/tenant`)
Se deben crear los scripts de Flyway en la ruta designada por `tenant-context` para que se ejecuten por cada silo.
* `V1__create_review_task_tables.sql`: Crea tabla `review_task`, `correction` y los índices necesarios.
* `V2__create_outbox_table.sql`: Crea la tabla para eventos outbox, si no la provee una librería base.

## 5. Estrategia de Tests

### 5.1 Unitarios (`src/test/java/.../unit`)
* **Lógica de Estado (`ReviewTaskServiceTest`):** Verificar transiciones de estado, especialmente el enrutamiento a `PENDING_SECOND_APPROVAL` cuando se incluyen campos críticos, y el pase directo a `APPROVED` en caso contrario.
* **Control de 4 Ojos (SEC-009):** Verificar que se lance una excepción (ej. `AccessDeniedException` o `BusinessRuleException`) cuando `first_reviewer_id` coincide con el aprobador secundario.

### 5.2 Integración y Componente (`src/test/java/.../integration`)
* **Testcontainers (PostgreSQL & Kafka):** Levantar el contexto de Spring.
* **API y Base de Datos:** `MockMvc` para simular llamadas de un Revisor 1 y luego Revisor 2, asegurando que se persisten correctamente los datos en base de datos.
* **Aislamiento Multitenant (AC-08):** Inyectar un JWT con `tenantId=tenant-A` y crear una tarea. Inyectar otro JWT con `tenantId=tenant-B` y verificar que la consulta por el mismo UUID retorna 404 (o no se encuentra el schema).

### 5.3 Pruebas de Contrato
* Validar que los eventos consumidos y producidos cumplen estructuralmente con `extraccion.requiere_revision.v1.schema.json` y `revision.completada.v1.schema.json` sin contener PII.
