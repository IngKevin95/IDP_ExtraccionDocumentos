# Especificación: Auditoría Criptográfica Hash-Chain

## 1. Propósito
Garantizar la inmutabilidad y el no repudio de todas las acciones operativas y de sistema en el IDP, proporcionando un registro incontrovertible que demuestre quién operó sobre un oficio, en qué instante, y qué aprobaciones ocurrieron, protegiendo al banco ante disputas legales.

## 2. Alcance y No Alcance
**Alcance:**
* Captura de eventos críticos (ingesta de oficios, revisiones, exportaciones, cambios de configuración).
* Mantenimiento de una secuencia criptográfica (hash-chain) por cada tenant.
* Anclaje periódico de bloques (lotes) a un almacenamiento inmutable de tipo WORM.
* Generación de expedientes de auditoría firmados asimétricamente y verificables de manera autónoma o pública.

**No Alcance:**
* Reemplazo de logs operacionales o de infraestructura (eso corresponde a Observabilidad).
* Integración directa con un SIEM del banco (el SIEM se alimentará vía streaming u observabilidad estándar, pero el anclaje criptográfico vive aquí).

## 3. Requisitos Cubiertos
* **RNF-101:** Aislamiento Multitenant (Silo). La cadena de auditoría se mantiene de forma estrictamente separada por tenant.
* **RNF-105:** Resiliencia basada en eventos (Claim-check para eventos pesados/con PII).

## 4. Reglas
1. **Unicidad de Cadena:** Existe una única cadena de hash activa por tenant.
2. **Dependencia Secuencial:** El consumo de eventos de auditoría desde Kafka debe ser estrictamente secuencial por partición/tenant (consumidor con reintento bloqueante).
3. **Inmutabilidad Absoluta:** Un registro insertado no puede ser modificado ni borrado por ninguna operación del sistema, bajo ninguna circunstancia.
4. **Validación Forense:** Cualquier salto o alteración en los hashes detectado en tiempo de lectura debe levantar una alerta de seguridad de nivel CRÍTICO.

## 5. Contrato
* **Eventos Consumidos:**
  * `contracts/events/AuditEntryCreated.v1.schema.json` (usando claim-check si el payload de contexto es grande, sin PII directa en el tópico).
* **Endpoints (Referencia `contracts/openapi/auditoria.yaml`):**
  * `GET /api/v1/audit/{tenantId}/dossier/{documentId}`: Genera un expediente firmado con todos los eventos de un oficio.
  * `GET /api/v1/audit/{tenantId}/verify`: Verifica criptográficamente la integridad de la cadena hasta el último anclaje WORM.

## 6. Modelo de Datos
La persistencia principal ocurre en un esquema dedicado por tenant (`tenant_schema`) en la base de datos de control.
* **Tabla `audit_entries`:**
  * `sequence_id` (BIGINT, Primary Key): Número de secuencia estrictamente creciente asignado al ingerir.
  * `tenant_id` (UUID): Identificador del tenant.
  * `correlation_id` (UUID): Para relacionar con transacciones distribuidas.
  * `action` (VARCHAR): Tipo de acción realizada (ej. `DOCUMENT_EXTRACTED`, `HUMAN_REVIEW_APPROVED`).
  * `actor_id` (VARCHAR): Identidad de quien ejecutó la acción.
  * `payload` (JSONB): Datos contextuales del evento (sin PII, usando referencias Claim-Check si aplica).
  * `current_hash` (VARCHAR): Hash SHA-256 del registro actual + `previous_hash`.
  * `previous_hash` (VARCHAR): Hash SHA-256 del registro inmediatamente anterior.
  * `worm_anchored` (BOOLEAN): Bandera que indica si el registro ya fue incluido en un lote WORM.

## 7. Controles de Seguridad
* **SEC-050 (Sin PII en Eventos):** Se implementa Claim-Check. Los payloads de auditoría en Kafka contienen punteros (URIs seguras), no datos en texto claro del deudor u oficio.
* **SEC-WORM:** Los anclajes de bloque se envían a un servicio con retención inmutable garantizada (S3 Object Lock o equivalente).
* **SEC-FIRMA:** Se emplea OpenBao Transit (ed25519) para la firma del expediente. Las llaves asimétricas de auditoría nunca se destruyen mientras la retención WORM siga vigente.

## 8. Escenarios de Aceptación

* **AC-01 [Camino Feliz]: Registro secuencial correcto.** Given un tenant con cadena iniciada, When llega un nuevo evento de auditoría, Then se calcula `current_hash = SHA256(data + previous_hash)` y se persiste el evento incrementando la secuencia en 1.
* **AC-02 [Integridad]: Rechazo por falta de secuencia.** Given un tenant, When llega un evento pero el `previous_hash` en memoria/BD no coincide con el último registro persistido, Then se bloquea el procesamiento y se dispara una alerta de desincronización.
* **AC-03 [Anclaje]: Cierre de lote hacia WORM.** Given una política de anclaje cada 1000 registros o 24 horas, When se cumple la condición, Then los registros se empaquetan, se firman, se envían al bucket WORM, y se marcan como `worm_anchored = true`.
* **AC-04 [Auditoría]: Expediente firmado.** Given un conjunto de eventos para un `documentId`, When un auditor solicita el dossier, Then el sistema devuelve un documento estructurado (JSON o PDF) firmado por la llave asimétrica del tenant, incluyendo la ruta del hash hacia el ancla WORM.
* **AC-05 [Seguridad]: Detección de alteración.** Given un atacante con acceso a la BD que modifica un `payload`, When se ejecuta el proceso de validación forense (`/verify`), Then el recálculo de hashes falla y se dispara la alerta de manipulación.
* **AC-06 [Seguridad]: Reintento bloqueante.** Given una falla en la base de datos, When el consumidor Kafka intenta guardar el registro, Then no salta el mensaje ni lo envía a un DLQ silencioso, sino que reintenta indefinidamente alertando al equipo operativo para no romper la cadena.
* **AC-07 [Silo]: Aislamiento multitenant.** Given dos tenants A y B, When el tenant A procesa eventos, Then no afecta la secuencia ni interactúa con la cadena de hashes del tenant B.
* **AC-08 [Validación Externa]:** Given un expediente firmado, When se verifica offline usando la llave pública del banco, Then la firma debe ser válida y asegurar que el expediente no fue alterado tras su emisión.

## 9. Métricas y SLO
* **Métricas:**
  * `audit.chain.length`: Longitud actual de la cadena por tenant.
  * `audit.worm.unanchored_count`: Número de registros pendientes de anclar.
  * `audit.event.processing.time`: Latencia desde que se lee en Kafka hasta que se guarda el hash.
* **SLO:**
  * Tiempo máximo de eventos no anclados en WORM <= 24 horas.
  * Disponibilidad del servicio de auditoría >= 99.99%.

## 10. Dependencias
* **Infraestructura:** CloudNativePG (persistencia de secuencia), Kafka (fuente de eventos), ObjectStore con soporte WORM (S3 Object Lock / Azure Immutable Storage).
* **Servicios Externos:** OpenBao Transit (para firma de expedientes y anclajes).
