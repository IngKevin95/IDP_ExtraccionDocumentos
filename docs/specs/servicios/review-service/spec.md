# Especificación: Review Service

## 1. Propósito
El `review-service` es responsable de gestionar el flujo de revisión humana (Human-in-the-Loop - HITL) de los oficios procesados. Asegura la calidad de la extracción de datos mediante la intervención manual de operadores, implementando estrictamente la regla de cuatro ojos para modificaciones sobre campos críticos antes de liberar el documento.

## 2. Alcance y No Alcance

**Alcance:**
- Escucha de eventos de extracción que requieren revisión y creación de tareas (`review_task`).
- Gestión del ciclo de vida de la tarea de revisión (Pendiente, Pendiente de Segunda Aprobación, Aprobado, Rechazado). Aclaración: estos estados corresponden a la tarea de revisión en sí, y son distintos de los estados canónicos del documento (ej. EN_REVISION, APROBADO) gestionados por document-service.
- Registro de correcciones a nivel de campo.
- Aplicación de reglas de negocio para exigir un segundo revisor (diferente al primero) si se alteran campos críticos.
- Notificación de la finalización de la revisión a través de eventos.

**No Alcance:**
- No consolida las correcciones en la base de datos principal de campos (`extraction-service` se encarga de esto consumiendo la API de correcciones).
- No visualiza ni renderiza el documento original (responsabilidad de la UI a través de `renderer`).
- No contiene datos personales en los eventos de Kafka (cumpliendo con SEC-050).

## 3. Requisitos Cubiertos
* **RF-301 Corrección acotada:** El revisor corrige campos dudosos.
* **RF-302 Cuatro Ojos en críticos:** Corregir campos críticos (monto, identificación, cuenta/producto, tipo de medida) exige aprobación de un segundo revisor (usuario distinto).
* **SEC-009 Regla de cuatro ojos:** Control de acceso para aprobación de oficios y campos críticos. Requiere aprobador distinto.
* **SEC-034 Control de alucinaciones:** Mediante revisión humana forzada si el score de calibración es bajo.
* **SEC-050 Auditoría / Claim-Check:** Cero PII en eventos Kafka.

## 4. Reglas de Negocio
1. **Creación de tareas:** Una tarea se crea automáticamente al consumir `extraccion.requiere_revision`.
2. **Definición de Campos Críticos:** Los campos `monto`, `identificacion`, `cuenta`, `producto` y `tipo_medida` se consideran críticos.
3. **Flujo de una sola revisión:** Si el `Revisor 1` aprueba la tarea sin corregir campos críticos (es decir, solo aprueba, o corrige campos no críticos), la tarea pasa a `APPROVED`.
4. **Flujo de cuatro ojos:** Si el `Revisor 1` corrige al menos un campo crítico, la tarea pasa al estado `PENDING_SECOND_APPROVAL`.
5. **Segunda aprobación:** Solo un usuario distinto al `Revisor 1` (validado mediante su ID en el token JWT) puede aprobar una tarea en `PENDING_SECOND_APPROVAL`.
6. **Rechazo:** Cualquier revisor (en primera o segunda instancia) puede rechazar el documento, lo cual pasa la tarea a `RECHAZADO` y aborta el flujo.
7. **Idempotencia:** Los eventos consumidos deben procesarse de forma idempotente para evitar tareas duplicadas por reintentos de Kafka.

## 5. Contrato

### 5.1 API REST
El contrato completo se encuentra en `contracts/openapi/review-service.yaml`.
* `GET /v1/review/tasks`: Listado de tareas (paginación, filtros por estado).
* `GET /v1/review/tasks/{taskId}`: Detalle de una tarea.
* `GET /v1/review/tasks/{taskId}/corrections`: Obtiene la lista de correcciones de una tarea (usado por `extraction-service` vía claim-check).
* `POST /v1/review/tasks/{taskId}/corrections`: Añade o sobrescribe correcciones a una tarea.
* `POST /v1/review/tasks/{taskId}/approve`: Primer revisor envía su decisión de aprobación.
* `POST /v1/review/tasks/{taskId}/approve-secondary`: Segundo revisor aprueba la tarea.
* `POST /v1/review/tasks/{taskId}/reject`: Rechaza el documento.

### 5.2 Eventos Consumidos
* `extraccion.requiere_revision` (Tópico global particionado por `documentId`). Crea la tarea de revisión.

### 5.3 Eventos Publicados
* `revision.completada` (Tópico global particionado por `documentId`). Esquema definido en `contracts/events/revision.completada.v1.schema.json`. Se emite a través de Outbox transaccional.

## 6. Modelo de Datos
El almacenamiento utiliza el patrón de Silo por Tenant (`libs/tenant-context`) sobre PostgreSQL.

### Base de Datos por Tenant
| Tabla | Columna | Tipo | Restricciones | Descripción |
|---|---|---|---|---|
| `review_task` | `id` | UUID | PK | Identificador único de la tarea |
| `review_task` | `document_id` | UUID | Index | Identificador del documento (Claim-check) |
| `review_task` | `status` | VARCHAR | Not Null | `PENDING`, `PENDING_SECOND_APPROVAL`, `APPROVED`, `REJECTED` |
| `review_task` | `first_reviewer_id`| VARCHAR | Nullable | ID (del JWT) del primer revisor |
| `review_task` | `second_reviewer_id`| VARCHAR | Nullable | ID del segundo revisor (4 ojos) |
| `review_task` | `created_at` | TIMESTAMP| Not Null | Fecha de creación |
| `review_task` | `updated_at` | TIMESTAMP| Not Null | Fecha de última actualización |
| `correction` | `id` | UUID | PK | Identificador de la corrección |
| `correction` | `task_id` | UUID | FK (`review_task`) | Tarea asociada |
| `correction` | `field_name` | VARCHAR | Not Null | Nombre del campo corregido |
| `correction` | `original_value` | TEXT | Nullable | Valor original (puede venir vacío) |
| `correction` | `corrected_value`| TEXT | Not Null | Nuevo valor provisto por el revisor |
| `correction` | `is_critical` | BOOLEAN | Not Null | Flag que indica si el campo alterado es crítico |
| `correction` | `created_at` | TIMESTAMP| Not Null | Fecha de la corrección |
| `correction` | `created_by` | VARCHAR | Not Null | ID del revisor que hizo la corrección |

## 7. Controles de Seguridad Aplicados
* **SEC-009:** La API `POST /tasks/{taskId}/approve-secondary` verifica que el ID del token JWT del usuario actual sea diferente a `first_reviewer_id`.
* **SEC-050:** Ni `extraccion.requiere_revision` ni `revision.completada` contienen datos extraídos, solo UUIDs (claim-check). Las correcciones se leen mediante API REST protegida.

## 8. Escenarios de Aceptación (Given/When/Then)

* **AC-01 (Creación Exitosa):** Given un evento `extraccion.requiere_revision` válido, When es consumido, Then se crea un registro en `review_task` en estado `PENDING` en la base del tenant correspondiente, y el ack del mensaje es enviado a Kafka.
* **AC-02 (Idempotencia):** Given un evento procesado previamente, When se re-consume el mismo evento (mismo `taskId` o `eventId`), Then se ignora sin crear tareas duplicadas ni lanzar errores.
* **AC-03 (Aprobación Simple sin campos críticos):** Given una tarea en estado `PENDING`, y un usuario envía correcciones sobre campos NO críticos (ej. `direccion`), When el usuario llama a `POST /v1/review/tasks/{taskId}/approve`, Then el estado de la tarea cambia a `APPROVED`, se guarda `first_reviewer_id`, y se emite el evento `revision.completada` con action `APROBADO` en la tabla outbox.
* **AC-04 (Aprobación requiere 4 Ojos):** Given una tarea en estado `PENDING`, y un usuario envía una corrección sobre un campo crítico (ej. `monto`), When el usuario llama a `POST /v1/review/tasks/{taskId}/approve`, Then el estado de la tarea cambia a `PENDING_SECOND_APPROVAL` y se guarda su ID en `first_reviewer_id`. NO se emite evento de completada.
* **AC-05 (Segunda Aprobación Exitosa):** Given una tarea en `PENDING_SECOND_APPROVAL`, When un usuario DISTINTO al `first_reviewer_id` llama a `POST /v1/review/tasks/{taskId}/approve-secondary`, Then el estado cambia a `APPROVED`, se guarda el `second_reviewer_id`, y se emite el evento `revision.completada` con action `APROBADO`.
* **AC-06 (Prevención de Auto-Aprobación SEC-009):** Given una tarea en `PENDING_SECOND_APPROVAL`, When el MISMO usuario que fue `first_reviewer_id` intenta llamar a `POST /v1/review/tasks/{taskId}/approve-secondary`, Then la API retorna `403 Forbidden` y el estado no cambia.
* **AC-07 (Rechazo del documento):** Given una tarea en `PENDING` o `PENDING_SECOND_APPROVAL`, When un usuario llama a `POST /v1/review/tasks/{taskId}/reject`, Then el estado cambia a `REJECTED` y se emite el evento `revision.completada` con action `RECHAZADO`.
* **AC-08 (Aislamiento de Tenants):** Given un JWT de un usuario perteneciente al Tenant A, When intenta acceder a una tarea `taskId` que pertenece al Tenant B, Then la API retorna `404 Not Found` (ya que la búsqueda se realiza exclusivamente en el pool/schema del Tenant A).

## 9. Métricas y SLO
* **SLI Latencia API:** 99% de las peticiones de lectura `< 100ms`.
* **SLI Procesamiento:** 99% de los eventos procesados (creación de tarea o emisión de completado vía outbox-relay) en `< 500ms`.
* **Métrica de Negocio:** Tiempo promedio de tarea en la cola (Diferencia entre `created_at` de la tarea y emisión de `revision.completada`).

## 10. Dependencias
* `libs/tenant-context`: Para resolución de multitenancy.
* PostgreSQL: Silo DB para almacenar tareas y patrón Outbox.
* Kafka: Consumo y emisión de eventos.
