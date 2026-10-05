# Especificación: Servicio de Auditoría (audit-service)

## 1. Propósito
El `audit-service` es el componente responsable de garantizar la inmutabilidad, el no repudio y la trazabilidad criptográfica de todas las acciones operativas y de seguridad dentro del IDP Bancario. Funciona como el notario digital del sistema, ingiriendo eventos de todos los dominios, construyendo una cadena de hash ininterrumpida por tenant, anclando lotes en un almacenamiento WORM (Write Once Read Many) y generando expedientes firmados asimétricamente para su verificación forense o pública.

## 2. Alcance y no alcance

**Alcance:**
* Consumo continuo y bloqueante de todos los tópicos de dominio y del tópico exclusivo `auditoria.eventos`.
* Asignación de un número de secuencia estrictamente incremental por tenant al momento de la ingesta.
* Cálculo y mantenimiento de una cadena de hash (hash-chain) aislada por tenant.
* Empaquetado periódico de registros y su envío (anclaje) a un almacenamiento inmutable WORM.
* Generación de expedientes de auditoría digitalmente firmados usando llaves asimétricas (`ed25519` vía OpenBao).
* Exposición de endpoints para exportación de expedientes por parte del rol Auditor y validación criptográfica externa.
* Soporte para retención inmutable y preservación legal (Legal Hold).

**No alcance:**
* Recolección de logs operacionales o métricas de infraestructura (ej. latencia de CPU, errores de memoria), lo cual es responsabilidad de la capa de Observabilidad.
* Integración de envío de eventos push hacia un SIEM del banco (el SIEM debe extraer logs o consumir de Kafka).
* Gestión del ciclo de vida de los tenants (responsabilidad de `tenant-service`).
* Actuar como base de datos primaria transaccional para el estado del documento documental.

## 3. Requisitos cubiertos
* **RF-601 Trazabilidad inmutable:** Todo evento va a un tópico Kafka auditable y luego a almacenamiento WORM inmutable por tenant.
* **RF-602 Expediente firmado:** Generación de un ancla criptográfica con hash-chain del documento, usando una llave asimétrica para validación.
* **RF-603 Purga y Habeas Data:** Registro auditable de crypto-shredding preservando la integridad de la cadena.
* **RNF-101 Aislamiento Multitenant (Silo):** Cadenas de hash, llaves y WORM estrictamente segregados por tenant.
* **RNF-105 Resiliencia basada en eventos:** Consumo idempotente y secuencias inmutables garantizadas frente a caídas.

## 4. Reglas
1. **Unicidad y Secuencialidad:** Cada tenant posee exactamente una cadena de hash activa. La secuencia de los eventos (`sequence_id`) se asigna en el momento de la ingesta en la base de datos de control del `audit-service`, no por el offset de Kafka.
2. **Reintento Bloqueante:** A diferencia de otros servicios, si `audit-service` falla al persistir un registro en base de datos, el consumidor de Kafka se bloquea y reintenta indefinidamente. Nunca envía mensajes a un DLT silencioso, ya que esto rompería la integridad de la cadena.
3. **Inmutabilidad de Eventos:** Los eventos auditables almacenados, una vez persistidos y encadenados, no pueden sufrir operación `UPDATE` ni `DELETE` bajo ninguna circunstancia.
4. **Firma Asimétrica Exclusiva:** Las llaves privadas (`ed25519`) para la firma del expediente viven exclusivamente en OpenBao Transit (o el KMS equivalente). El `audit-service` nunca materializa la llave privada en su memoria, delegando la firma al KMS.
5. **No PII en la Cadena (Claim-Check):** El servicio audita hashes y metadatos contextuales. Los datos personales siempre se referencian mediante identificadores unívocos opacos o uris; los expedientes extraen la carga útil del silo solo al generarse la exportación, si está autorizada.
6. **Retención Legal Hold:** Un tenant o documento marcado con Legal Hold bloquea la destrucción de sus claves KEK asociadas y extiende la protección WORM indefinidamente.

## 5. Contrato

* **Eventos Consumidos (Referencia `contracts/events/*.v1.schema.json`):**
  * Todos los de dominio (`dominio.documentos`): `documento.recibido`, `extraccion.completada`, `extraccion.aprobada`, `revision.completada`, `documento.purgado`, etc.
  * Tópico de Seguridad (`auditoria.eventos`): `seguridad.acceso_denegado`, `seguridad.prompt_injection_detectado`, `breakglass.otorgado`, `breakglass.expirado`, `legalhold.aplicado`.

* **Eventos Publicados:**
  * Ninguno directamente al flujo transaccional para no crear ciclos. Excepcionalmente alertas internas de seguridad (vía métricas u observabilidad).

* **Endpoints (Referencia `contracts/openapi/audit-service.yaml`):**
  * `GET /v1/audit/dossiers/{documentId}`: Exporta expediente completo firmado de un oficio.
  * `GET /v1/audit/verify`: Ejecuta verificación forense de la hash-chain del tenant contra los anclajes WORM.
  * `POST /v1/audit/legal-holds`: Aplica estado de preservación legal (Legal Hold) a documentos específicos o un tenant completo.

## 6. Modelo de Datos
La persistencia principal ocurre en la **Base de Control** (esquema compartido, físicamente separado por `tenant_id` en las filas) y en **Storage WORM** en el bucket del tenant.

* **Tabla `audit_entries` (Base de Control, esquema `audit_schema`):**
  * `sequence_id` (BIGINT, Clave Primaria, particionada/aislada por tenant): Número incremental.
  * `tenant_id` (UUID, Índice): Identificador del tenant.
  * `correlation_id` / `document_id` (UUID, Índice): Vínculo con transacciones distribuidas o documentos.
  * `event_type` (VARCHAR): Tipo de evento procesado (ej. `documento.recibido`).
  * `actor_id` (VARCHAR): Identidad de quien ejecutó la acción.
  * `payload` (JSONB): Payload del evento bajo claim-check.
  * `current_hash` (VARCHAR): Hash SHA-256 de (`payload` + `sequence_id` + `previous_hash`).
  * `previous_hash` (VARCHAR): Hash SHA-256 del registro inmediatamente anterior.
  * `worm_anchored` (BOOLEAN, Índice): Indica si este registro ya subió al WORM.

* **Tabla `worm_anchors` (Base de Control):**
  * `anchor_id` (UUID, PK)
  * `tenant_id` (UUID)
  * `start_sequence_id` (BIGINT)
  * `end_sequence_id` (BIGINT)
  * `file_uri` (VARCHAR): Ruta en el almacenamiento WORM.
  * `created_at` (TIMESTAMP)

## 7. Controles de Seguridad
* **SEC-021 (Escritor único y WORM):** Único componente autorizado a escribir en la partición inmutable de almacenamiento; utiliza APIs de Object Lock en S3/Blob con modo Compliance para retención inalterable, incluso por administradores de nube.
* **SEC-039 (Auditoría Forense Integral):** Toda transacción crítica y acceso de emergencia (Break-glass) deja traza encadenada matemáticamente; si un atacante inserta registros, `previous_hash` y el recálculo alertan inmediatamente.
* **SEC-040 (Firma Digital No Repudiable):** Empleo de OpenBao Transit (ed25519) asegurando que el banco o perito externo valide autenticidad de cada expediente exportado, con imposibilidad técnica de forjar la firma desde el IDP al no poseer el material criptográfico.
* **SEC-042 (Legal Hold y Preservación):** Capacidades WORM permiten bloqueos legales requeridos por regulación bancaria evitando expurgos automatizados.
* **SEC-050 (Aislamiento de PII en Auditoría):** Cero PII transita hacia el hash-chain (los payloads JSONB son punteros o metadatos opacos).

## 8. Escenarios de Aceptación

* **AC-01 [Camino Feliz]: Ingesta secuencial de eventos.**
  * *Given* un tenant con cadena de hash iniciada,
  * *When* se recibe el evento `extraccion.aprobada` desde Kafka,
  * *Then* se calcula `current_hash` concatenando los datos con el `previous_hash` del tenant, se guarda en `audit_entries` incrementando el `sequence_id`, y se devuelve ACK a Kafka.

* **AC-02 [Seguridad/Integridad]: Detención ante inconsistencia.**
  * *Given* la cadena de un tenant,
  * *When* llega un nuevo evento pero el `previous_hash` recuperado de la base de datos no coincide con la huella esperada en memoria de la cadena en curso,
  * *Then* el sistema bloquea el consumo de esa partición, rechaza el commit del offset de Kafka y emite una alerta CRÍTICA de seguridad (manipulación de BD).

* **AC-03 [Anclaje]: Consolidación y cierre de lote a WORM.**
  * *Given* una política configurada (ej. cada 1,000 eventos o 24 horas) y eventos pendientes `worm_anchored = false`,
  * *When* se activa el trigger de anclaje,
  * *Then* el lote se exporta a un archivo JSON, se firma asimétricamente, se sube al bucket WORM del tenant, se registra en `worm_anchors` y se marcan los registros origen como `worm_anchored = true`.

* **AC-04 [Auditoría]: Generación de expediente forense.**
  * *Given* un `documentId` completamente tramitado,
  * *When* un usuario con rol `Auditoría` consulta el endpoint `/v1/audit/dossiers/{documentId}`,
  * *Then* el servicio consolida la traza de todos los eventos del documento, firma el compilado asimétricamente con OpenBao (ed25519) y retorna un archivo verificable.

* **AC-05 [Seguridad]: Aislamiento multitenant del hash-chain.**
  * *Given* la llegada simultánea de eventos para Tenant A y Tenant B,
  * *When* se procesan concurrentemente,
  * *Then* las secuencias y cálculos de `previous_hash` no se mezclan, manteniendo el rigor criptográfico intacto para cada silo.

* **AC-06 [Resiliencia]: Reintento bloqueante (Failure Handling).**
  * *Given* una desconexión temporal de la base de control,
  * *When* el consumidor de Kafka recibe un evento crítico (`breakglass.otorgado`),
  * *Then* el `audit-service` retiene el mensaje sin avanzar el offset ni mandarlo a un DLT silencioso, y reintenta la conexión hasta lograr la inserción y encadenado, asegurando 0 pérdida de logs.

* **AC-07 [Seguridad/Privacidad]: Exportación con ofuscación PII.**
  * *Given* la generación de un expediente,
  * *When* el payload subyacente indica un crypto-shredding parcial de ofuscación de datos (`documento.purgado`),
  * *Then* el expediente firmado refleja la constancia del evento purgado, demostrando que fue eliminado según cumplimiento, pero reteniendo la validez matemática de la cadena.

* **AC-08 [Legal Hold]: Bloqueo de purga.**
  * *Given* un documento marcado con Legal Hold,
  * *When* llega una solicitud de expurgo o expira la política de retención,
  * *Then* el `audit-service` verifica el estado `legal_hold` y rechaza la destrucción de datos vinculados y las KEKs, generando un evento de rechazo por retención legal.

## 9. Métricas y SLO

**Métricas expuestas (Prometheus):**
* `audit.chain.length`: Longitud de la cadena por tenant (gauge).
* `audit.worm.unanchored_count`: Eventos pendientes de anclaje por tenant (gauge).
* `audit.ingestion.lag`: Tiempo transcurrido entre la ocurrencia del evento y su encadenamiento criptográfico (histogram).
* `audit.dossier.generation_time`: Tiempo de latencia para firmar y exportar un expediente (histogram).

**SLO:**
* Disponibilidad del servicio de auditoría >= 99.99%.
* Lag máximo de consolidación en WORM: ≤ 24 horas (todos los eventos del día deben estar anclados en WORM antes de medianoche).
* Cero pérdida de eventos de dominio o seguridad (100% de fiabilidad en entrega y encadenamiento).

## 10. Dependencias
* **Infraestructura:** CloudNativePG (Base de Control), Strimzi Kafka (Tópicos `dominio.documentos`, `auditoria.eventos`), ObjectStore compatible con WORM (S3 Object Lock / Azure Blob Immutability).
* **Servicios Externa/Plataforma:** OpenBao Transit (para firmas ed25519), `tenant-service` (para validación de estado de tenant/Legal Hold).
* **Librerías Base:** `libs/security`, `libs/events`, `libs/kms-port`.
