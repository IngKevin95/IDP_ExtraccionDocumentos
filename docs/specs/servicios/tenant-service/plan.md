# Plan de Implementación: tenant-service

## Módulo Maven
Se ubicará en `services/tenant-service` dentro del repositorio padre.

## Paquetes
* `com.idp.tenant.api`: Controladores REST.
* `com.idp.tenant.application`: Casos de uso (crear tenant, baja, legal hold).
* `com.idp.tenant.domain`: Entidades (`Tenant`, `TenantConfig`, `RoleAssignment`) y repositorios.
* `com.idp.tenant.infrastructure`: Adaptadores.
  * `.persistence`: JPA / Repositorios Spring Data.
  * `.messaging`: Patrón Outbox hacia Kafka.
  * `.platform`: Clientes para K8s API, CNPG y KMS (OpenBao).

## Clases principales
* `TenantController`: Endpoints REST administrativos.
* `TenantProvisioningService`: Orquesta la creación lógica en BD de control y la física en infraestructura.
* `LegalHoldService`: Activa o libera retenciones.
* `OffboardingJob`: Job asíncrono para purgar tenants en `PENDING_DELETION` vencidos (shredding).
* `PlatformClient` / `KmsClient`: Puertos hacia la infraestructura.

## Configuración Spring
* `application.yml` configurado para conectar a la base de datos global (Control).
* Configuración de cliente Kubernetes y KMS con RestTemplate/WebClient.

## Migraciones Flyway
* `V1__init_schema.sql`: Creación de tablas `tenants`, `planes`, `tenant_config`, `role_assignment` y tabla `outbox`.
* `V2__seed_plans.sql`: Inserción de planes base (Standard, Dedicated).

## Estrategia de Tests
* **Unitarios:** Lógica de negocio (casos de uso de baja con legal_hold, cálculo de cuotas). Mockeo de clientes de infraestructura.
* **Integración (Testcontainers):**
  * PostgreSQL para verificar las transacciones y Flyway.
  * Kafka/Redpanda para validar la inserción correcta en tabla outbox y el relay simulado.
  * WireMock para simular respuestas de la API de Kubernetes y OpenBao.
* **Contrato:** Verificación de OpenAPI (`tenant-service.yaml`) mediante Spring Cloud Contract o pactos con el frontend de admin.
