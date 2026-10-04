# Especificación: tenant-service

## 1. Propósito
Gestionar el ciclo de vida de los tenants en la plataforma, abarcando su aprovisionamiento físico y lógico, configuración de planes, cuotas, llaves de cifrado (KEK) independientes, y los procesos de retención legal (legal hold) y baja. También es responsable de generar los reportes de certificación de accesos.

## 2. Alcance y no alcance
* **Alcance:**
  * Creación y baja de tenants.
  * Gestión de configuración, cuotas y planes por tenant.
  * Solicitud de creación de recursos físicos (bases de datos lógicas, namespaces, llaves en KMS) comunicándose con APIs de plataforma/Kubernetes.
  * Gestión del estado de *legal hold* sobre un tenant.
  * Asignación base de roles a nivel plataforma (`role_assignment`).
  * Emisión de eventos de auditoría y ciclo de vida de tenants.
* **No alcance:**
  * Procesar o almacenar documentos (oficios).
  * Autenticar directamente usuarios (de esto se encarga el IdP y el edge-gateway).
  * Operaciones de chat o extracción de IA.

## 3. Requisitos cubiertos
* Gestión multi-tenant con aislamiento físico de datos.
* Rotación y destrucción de llaves criptográficas.
* Control de cuotas y consumo.

## 4. Reglas
* El servicio operará en la red de administración y no será accesible directamente por clientes finales, solo por administradores de plataforma.
* Todo aprovisionamiento físico debe ser asíncrono y tolerante a fallos, reconciliándose con la base de control.
* No se permite la baja de un tenant si tiene un `legal_hold` activo.
* Todo evento publicado debe seguir el patrón claim-check sin PII.

## 5. Contrato
* **API REST:** Definida en `contracts/openapi/tenant-service.yaml`.
  * `POST /v1/admin/tenants`
  * `PUT /v1/admin/tenants/{id}/config`
  * `POST /v1/admin/tenants/{id}/legal-holds`
  * `DELETE /v1/admin/tenants/{id}` (soft-delete iniciando proceso de baja)
* **Eventos Publicados:** (JSON Schema, draft 2020-12, referenciados en `contracts/events/`)
  * `tenant.aprovisionado`
  * `tenant.aprovisionamiento_fallido`
  * `tenant.baja_iniciada`
  * `consumo.registrado`
  * `cuota.umbral_alcanzado`
  * `acceso.revocado`
  * `breakglass.otorgado`
  * `breakglass.expirado`
  * `legalhold.aplicado`
  * `legalhold.liberado`
* **Eventos Consumidos:** Ninguno directamente de los flujos de documentos.

## 6. Modelo de datos
Base de datos: **Control** (Silo global de plataforma, no de tenant).
* `tenants`: `id` (PK, UUID), `name`, `status`, `plan_id`, `created_at`, `updated_at`.
* `planes`: `id` (PK, UUID), `name`, `features`, `quota_limits`.
* `tenant_config`: `tenant_id` (PK/FK), `data_kek_id`, `audit_kek_id`, `legal_hold` (boolean), `settings` (JSONB).
* `role_assignment`: `id` (PK), `tenant_id`, `user_id`, `role`, `expires_at`.

## 7. Controles de seguridad
* **SEC-001 (Aislamiento):** Coordina la creación de silos separados (base de datos lógica en CNPG, buckets, llaves KEK) por tenant.
* **SEC-012 (Certificación):** Genera reporte trimestral de accesos y roles (`role_assignment`).
* **SEC-015 (KEK segregada):** Aprovisiona KEKs de datos y auditoría distintas vía KMS en OpenBao.
* **SEC-016 (Crypto-shredding):** Llama a la API del KMS para deshabilitar la KEK de datos al ejecutar la baja.
* **SEC-017 (Bloqueo legal hold):** Verifica la bandera `legal_hold` en `tenant_config` antes de permitir la baja o shredding.
* **SEC-047 (Offboarding):** Inicia la baja cambiando el estado del tenant y emitiendo `tenant.baja_iniciada`, garantizando retención según `legal_hold`.
* **SEC-050 (Claim-check en eventos):** Los eventos que emite solo contienen IDs de tenant o IDs de plan, nunca datos sensibles.

## 8. Escenarios de Aceptación (AC)
* **AC-01 (Alta exitosa):** Given un payload válido de nuevo tenant, When se llama a `POST /v1/admin/tenants`, Then se crea el registro en BD de control, se encolan tareas de aprovisionamiento físico y se emite `tenant.aprovisionado`.
* **AC-02 (Fallo físico):** Given un tenant en creación, When falla el aprovisionamiento del bucket, Then se emite `tenant.aprovisionamiento_fallido` y se marca estado a `FAILED`.
* **AC-03 (Legal Hold previene baja):** Given un tenant con `legal_hold = true`, When un administrador intenta `DELETE /v1/admin/tenants/{id}`, Then retorna HTTP 409 Conflict.
* **AC-04 (Baja exitosa):** Given un tenant sin legal hold, When un admin invoca la baja, Then el estado cambia a `PENDING_DELETION` y se emite `tenant.baja_iniciada`.
* **AC-05 (Configuración de cuota):** Given un tenant activo, When se actualiza su plan, Then los límites se aplican y la base se actualiza.
* **AC-06 (Alerta de cuota):** Given un tenant, When su consumo reportado supera el 80% del límite, Then se emite `cuota.umbral_alcanzado`.
* **AC-07 (Break-glass):** Given una solicitud break-glass aprobada por otro admin, When se registra, Then se crea asignación con TTL en `role_assignment` y emite `breakglass.otorgado`.
* **AC-08 (Shredding):** Given un tenant en `PENDING_DELETION` con plazo cumplido, When se ejecuta el job de shredding, Then se instruye a KMS la destrucción de la KEK de datos (manteniendo la de auditoría).
* **AC-09 (Certificación de accesos):** Given un requerimiento trimestral, When el administrador solicita la certificación de accesos, Then se genera un reporte exportable con la evidencia firmada de las asignaciones de roles (SEC-012).

## 9. Métricas y SLO
* Disponibilidad API: 99.9%.
* Latencia API P95: < 200ms.
* Tiempo de aprovisionamiento de tenant: < 2 minutos.

## 10. Dependencias
* Kubernetes API (namespaces, configuraciones).
* CNPG Operator API / Control Plane de BD (base lógica).
* OpenBao / KMS (creación de llaves asimétricas).
* Kafka (Outbox en la base de control).
