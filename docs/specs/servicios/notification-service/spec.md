# Especificación: Notification Service

## 1. Propósito
Gestionar las suscripciones de webhooks por tenant y orquestar la notificación segura hacia los sistemas integradores (core bancario) sobre el estado de extracción de los documentos.

## 2. Alcance y No Alcance
**Alcance:**
* API REST para CRUD de suscripciones de webhooks.
* Consumo asíncrono e idempotente de eventos de dominio (`extraccion.aprobada`, `documento.rechazado`, `revision.completada`).
* Despacho HTTP seguro a endpoints de terceros con estricta prevención SSRF y firmado criptográfico (HMAC).
* Manejo de reintentos con backoff exponencial configurable por tenant y DLT (entregas `FALLIDO` con reintento manual).
* Allowlist de hosts receptores y parámetros de reintento por tenant (`/v1/webhooks/policy`).
* Rotación de secretos HMAC con dos secretos activos concurrentes.
* Registro de entregas (historial consultable) y fallos en la base de datos de tenant y publicación de eventos de resultado.

**No Alcance:**
* Orquestación directa de correos electrónicos o SMS (solo webhooks HTTP).
* Recepción de respuestas funcionales desde el core bancario (fuego y olvida seguro).
* Consulta masiva del historial de envíos (solo registro transaccional; la analítica va a otra capa).

## 3. Requisitos cubiertos
* **RF-501 Webhooks seguros:** Notifica `extraccion.aprobada` y `documento.rechazado`. Salida filtrada por anti-SSRF, firmas HMAC rotativas.

## 4. Reglas de Negocio
1. Un tenant puede registrar múltiples webhooks, suscribiéndose a tipos de eventos específicos.
2. Cada solicitud saliente debe incluir una cabecera `X-Hub-Signature-256` calculada sobre el `timestamp` y el cuerpo (payload) concatenados.
3. El payload saliente usa un modelo claim-check: incluye el estado y el ID del documento, obligando al consumidor a recuperar la PII mediante API.
4. Los reintentos siguen retroceso exponencial (exponential backoff). Tras agotar los reintentos (ej. 5), el mensaje se marca como fallido y se emite un evento `webhook.fallido`.
5. Se permite la rotación de secretos: cada webhook puede tener dos secretos concurrentes.

## 5. Contrato
* **API REST:** `contracts/openapi/notification-service.yaml` (Rutas bajo `/v1/webhooks`)
* **Eventos Consumidos:**
  * `extraccion.aprobada` (desde `documentos.eventos`)
  * `documento.rechazado` (desde `documentos.eventos`)
  * `revision.completada` (desde `revision.eventos`; se notifica solo el resultado `APROBADO`/`RECHAZADO`, nunca revisor ni tarea)
* **Eventos Publicados:**
  * `webhook.entregado` (hacia `notificaciones.eventos` mediante outbox)
  * `webhook.fallido` (hacia `notificaciones.eventos` mediante outbox; `reasonCode` `SECRET_UNAVAILABLE` cuando el secreto no se puede descifrar)
  * Referencia: `contracts/events/webhook.entregado.v1.schema.json`, `contracts/events/webhook.fallido.v1.schema.json`.

## 6. Modelo de Datos
La persistencia reside en la base de datos lógica o física por tenant (aislada vía `AbstractRoutingDataSource`).

| Tabla | Propósito | Columnas Clave | Índices | Base de datos |
|---|---|---|---|---|
| `webhook_subscription` | Configuración de la URL y secretos (cifrados en sobre con la KEK del tenant) | `id`, `url`, `events`, `secret_current`, `secret_previous`, `secret_previous_expires_at`, `active` | `idx_wh_active` | Tenant |
| `webhook_tenant_policy` | Allowlist de hosts y reintentos del tenant | `tenant_id`, `allowed_hosts`, `max_attempts`, `initial_backoff_ms`, `backoff_multiplier`, `max_backoff_ms` | PK | Tenant |
| `webhook_delivery` | Historial de entregas (FALLIDO = DLT) | `id`, `webhook_id`, `document_id`, `source_event_id`, `event_type`, `payload` (claim-check), `status`, `attempts`, `next_attempt_at`, `last_http_status`, `error_code` | `idx_wh_delivery_doc`, `idx_wh_status`, único `(webhook_id, source_event_id)` | Tenant |
| `outbox` / `processed_event` | Bandeja de salida transaccional (Kafka) e idempotencia del consumidor (libs/events) | ver libs/events | `idx_outbox_pending` | Tenant |

## 7. Controles de Seguridad
* **SEC-027 (Prevención SSRF/TOCTOU):** El servicio valida la resolución DNS de la URL destino previo al envío, fijando la IP (DNS pinning). Se aborta si la IP pertenece a RFC1918 (privadas), loopback, 169.254/16 (metadata cloud), 100.64/10 o IPv6 ULA/link-local. Las redirecciones HTTP están explícitamente deshabilitadas en el cliente HTTP (ADR 0027).
* **SEC-028 (Firmas HMAC):** Se calcula `HMAC-SHA256(secret, timestamp + payload)`. El receptor puede validar la ventana de tiempo (anti-replay). La concurrencia de dos secretos facilita la rotación sin downtime.
* **SEC-050 (Sin PII en Eventos - Claim-Check):** Ni el payload enviado al webhook externo ni los eventos publicados en Kafka (`webhook.entregado`, etc.) contienen datos del documento extraído (PII). Solo viajan identificadores y estados.

## 8. Escenarios de Aceptación (Gherkin)

**AC-01: Registro de suscripción exitoso**
* **Given** que el administrador del tenant tiene credenciales válidas
* **When** envía un POST a `/v1/webhooks` con una URL HTTPS válida y la lista de eventos
* **Then** la suscripción se guarda activa y se devuelve el secreto generado automáticamente.

**AC-02: Envío de webhook exitoso (Claim-Check)**
* **Given** una suscripción activa a `extraccion.aprobada`
* **When** el servicio consume el evento `extraccion.aprobada` para un documento del tenant
* **Then** se resuelve la URL, se firma el payload (solo ID y estado) con HMAC y se envía un POST exitoso al cliente, publicando `webhook.entregado`.

**AC-03: Bloqueo de SSRF por IP privada (SEC-027)**
* **Given** una suscripción con URL `https://api.banco.com/webhook`
* **When** el motor DNS resuelve la URL a la IP `10.0.5.5`
* **Then** el WebClient bloquea la conexión por regla RFC1918 y marca el envío como fallido, registrando alerta de seguridad.

**AC-04: Bloqueo de SSRF por metadatos Cloud (SEC-027)**
* **Given** una suscripción con URL `http://169.254.169.254/latest/meta-data/`
* **When** el servicio intenta preparar el despacho
* **Then** aborta inmediatamente al detectar el CIDR prohibido y no emite la solicitud HTTP.

**AC-05: Validación de firma y timestamp (SEC-028)**
* **Given** un despacho de webhook
* **When** se arma la solicitud saliente
* **Then** la cabecera `X-Hub-Timestamp` refleja el epoch actual y la cabecera `X-Hub-Signature-256` contiene el hash correcto; las cabeceras nunca son sobreescritas por redirecciones (redirecciones deshabilitadas).

**AC-06: Reintentos y pase a DLT tras fallos**
* **Given** un webhook saliente
* **When** el servidor destino responde 503 cinco veces consecutivas (con backoff exponencial)
* **Then** se desiste de la entrega, se marca en `webhook_delivery` como FALLIDO y se publica el evento outbox `webhook.fallido`.

**AC-07: Aislamiento estricto de tenants**
* **Given** el tenant A y el tenant B con sus webhooks
* **When** se aprueba un documento del tenant A
* **Then** el `AbstractRoutingDataSource` selecciona el silo de A, lee la suscripción de A, envía el webhook de A e ignora completamente la configuración de B.

**AC-08: Ausencia de PII en eventos publicados (SEC-050)**
* **Given** que se publica el evento `webhook.entregado` al tópico `notificaciones.eventos`
* **When** el auditor revisa el JSON enviado
* **Then** se verifica que solo contiene `webhookId`, `documentId`, `tenantId`, sin datos extraídos de la persona.

**AC-09: Rotación de secretos con dos secretos activos (SEC-028)**
* **Given** un webhook con secreto S1 y una rotación iniciada que genera S2 (S1 queda activo hasta `previousSecretExpiresAt`)
* **When** se despacha una entrega durante la rotación
* **Then** `X-Hub-Signature-256` lleva dos firmas (S2 y S1) y un receptor con cualquiera de los dos secretos valida; al cerrar la rotación (`DELETE .../secret/previous`) o vencer el solapamiento solo se firma con S2, y una rotación ya en curso devuelve 409.

**AC-10: HTTPS y allowlist por tenant**
* **Given** un tenant con `allowedHosts` en su política (sin política no se admite ningún destino)
* **When** registra un webhook con `http://`, con un host fuera de la allowlist, con IP literal/nombre interno o con credenciales embebidas
* **Then** se responde 400 con código estable (`WEBHOOK_URL_INSECURE_SCHEME`, `WEBHOOK_URL_HOST_NOT_ALLOWED`, `WEBHOOK_URL_BLOCKED_*`, `WEBHOOK_URL_INVALID_URL`); la misma validación se repite antes de cada envío (un cambio de política bloquea entregas pendientes).

**AC-11: Secreto mostrado una sola vez y cifrado en reposo**
* **Given** un webhook recién creado
* **When** se consulta la base de datos y la API
* **Then** el secreto solo aparece en la respuesta de creación/rotación (`Cache-Control: no-store`), se almacena cifrado en sobre ligado a tenant y webhook, y si la KEK del tenant se destruye la entrega no se envía.

**AC-12: Historial, DLT y reintento manual**
* **Given** una entrega `FALLIDO` (DLT)
* **When** el administrador consulta `GET .../deliveries` y llama `POST .../deliveries/{id}/retry`
* **Then** el historial muestra estado, intentos, código HTTP y error sin payload; el reintento la devuelve a `PENDIENTE` con contador en cero conservando el mismo `X-Hub-Event-Id`; solo se reintentan entregas `FALLIDO` del propio webhook y tenant (409/404 en otro caso).

**AC-13: Consumo idempotente**
* **Given** un evento ya procesado o fuera de contrato
* **When** llega de nuevo al consumidor
* **Then** un `eventId` repetido no duplica la entrega (`processed_event` y unicidad `(webhook_id, source_event_id)`), un evento fuera de JSON Schema va directo al DLT de Kafka sin reintentos y un tipo ajeno se ignora.

**AC-14: Anti-SSRF exhaustivo (SEC-027)**
* **Given** un resolvedor DNS falso
* **When** el host resuelve a RFC1918, loopback, link-local/metadata, CGNAT, IPv6 ULA/link-local/multicast, NAT64 o 6to4 con IPv4 privada, o a registros mixtos público/privado, o cambia de público a privado entre dos resoluciones (DNS rebinding), o el receptor responde con un redirect
* **Then** no se abre conexión (el nombre se resuelve una vez por intento y se conecta a esa IP), el bloqueo es definitivo (`SSRF_BLOCKED`, sin reintentos) y los redirects (301/302/307/308) no se siguen.

**AC-15: Otros eventos notificables**
* **Given** una suscripción a `revision.completada` o `documento.rechazado`
* **When** se consume el evento
* **Then** se notifica como claim-check (`status` `REVISION_APROBADO`/`REVISION_RECHAZADO` o `RECHAZADO` + `reasonCode`) sin identidad del revisor ni tarea.

**AC-16: Backoff configurable por tenant**
* **Given** una política de tenant con `maxAttempts`, `initialBackoffSeconds`, `backoffMultiplier` y `maxBackoffSeconds`
* **When** el receptor falla con errores reintentables
* **Then** la espera es `min(max, inicial x multiplicador^(n-1))` y la entrega pasa a `FALLIDO` al agotar `maxAttempts`; los parámetros fuera de rango se rechazan con 400.

**AC-17: Anti-replay**
* **Given** un receptor que valida firma y ventana de tiempo
* **When** recibe un envío (o un reintento)
* **Then** `X-Hub-Timestamp` es el instante del intento, `X-Hub-Event-Id` e `id` del cuerpo son estables entre reintentos (deduplicación) y la firma cubre `timestamp + cuerpo`, de modo que un envío repetido fuera de ventana o con cuerpo alterado no valida.

## 9. Métricas y SLO
* **Latencia de entrega de Webhooks:** P95 < 2 segundos (excluyendo tiempo de respuesta del receptor externo).
* **Tasa de éxito de Webhooks (SLO):** > 99.5% excluyendo errores 4xx del cliente bancario.
* **Métricas Actuator:**
  * `webhook.delivery.success` (contador por tenant)
  * `webhook.delivery.failure` (contador por tenant y causa, ej. `ssrf_blocked`, `timeout`)

## 10. Dependencias
* **Kafka KRaft:** Para consumir `extraccion.aprobada` y emitir estado.
* **Base de datos de Tenant:** PostgreSQL local para configurar webhooks (vía Datasource de enrutamiento).
* **Edge Gateway:** Para autenticación entrante de la API de gestión.
