# Tareas: tenant-service

## T-01: Estructura del proyecto e infraestructura inicial
* **Descripción:** Crear módulo Maven `services/tenant-service`, configurar `pom.xml`, dependencias Spring Boot, Flyway y Testcontainers. Crear scripts de Flyway iniciales.
* **Criterio de hecho:** La aplicación arranca y ejecuta migraciones en Testcontainers.
* **Validación:** Test de contexto de Spring.

## T-02: Dominio y Persistencia (Tenants y Config)
* **Descripción:** Crear entidades JPA (`Tenant`, `TenantConfig`, `Plan`) y repositorios de Spring Data.
* **Criterio de hecho:** Operaciones CRUD básicas a la base de control funcionales.
* **Validación:** Tests de integración de repositorios.

## T-03: Controlador REST (Alta de Tenant)
* **Descripción:** Implementar `POST /v1/admin/tenants` con validación OpenAPI.
* **Criterio de hecho:** Endpoint acepta payloads válidos y persiste el Tenant.
* **Validación:** `AC-01`.

## T-04: Adaptadores de Plataforma (KMS y K8s)
* **Descripción:** Implementar clientes `KmsClient` (OpenBao) y `KubernetesClient`.
* **Criterio de hecho:** Clientes pueden realizar llamadas exitosas (mockeadas) para provisionar llaves KEK (Datos y Auditoría) y namespaces.
* **Validación:** Tests unitarios y WireMock.

## T-05: Flujo de Aprovisionamiento y Outbox
* **Descripción:** Conectar controlador con lógica de negocio y emitir `tenant.aprovisionado` o `tenant.aprovisionamiento_fallido` a través del patrón Outbox (tabla de base de datos).
* **Criterio de hecho:** Tenant se marca activo o fallido según respuesta de clientes. Evento en tabla outbox.
* **Validación:** `AC-01`, `AC-02`.

## T-06: Legal Hold
* **Descripción:** Implementar `POST /v1/admin/tenants/{id}/legal-holds` y estado interno en `TenantConfig`.
* **Criterio de hecho:** Se puede activar/desactivar y emite `legalhold.aplicado` / `legalhold.liberado`.
* **Validación:** Tests de integración.

## T-07: Proceso de Baja (Offboarding)
* **Descripción:** Implementar `DELETE /v1/admin/tenants/{id}`. Verificar legal_hold. Marcar `PENDING_DELETION`.
* **Criterio de hecho:** Baja rechazada si hay legal hold, exitosa si no. Emisión de `tenant.baja_iniciada`.
* **Validación:** `AC-03`, `AC-04`.

## T-08: Gestión de Planes y Cuotas
* **Descripción:** Implementar `PUT /v1/admin/tenants/{id}/config` y lógica para registrar consumo (emisión `cuota.umbral_alcanzado`).
* **Criterio de hecho:** Actualización de plan y alertas de umbral al 80%.
* **Validación:** `AC-05`, `AC-06`.

## T-09: Roles y Break-glass
* **Descripción:** Gestión sobre `role_assignment`. Endpoint para aprovisionar permisos temporales de acceso (Break-glass).
* **Criterio de hecho:** Registro de acceso con expiración temporal y emisión de evento `breakglass.otorgado`.
* **Validación:** `AC-07`.

## T-10: Shredding de Datos
* **Descripción:** Job asíncrono para invalidar KEK de datos en KMS al cumplirse el plazo de `PENDING_DELETION`.
* **Criterio de hecho:** Destrucción de llave comunicada al KMS (mock) si no hay legal hold.
* **Validación:** `AC-08`.

## T-11: Reporte de Certificación de Accesos
* **Descripción:** Implementar la generación del reporte trimestral de certificación de accesos y roles asignados, con firma digital.
* **Criterio de hecho:** Endpoint administrativo genera y exporta el reporte de certificación.
* **Validación:** `AC-09`.
