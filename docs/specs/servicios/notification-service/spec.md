# Especificación: Notification Service

## 1. Propósito
Gestionar las suscripciones de webhooks por tenant y orquestar la notificación segura hacia los sistemas integradores (core bancario) sobre el estado de extracción de los documentos.

## 2. Alcance y No Alcance
**Alcance:**
* API REST para CRUD de suscripciones de webhooks.
* Consumo asíncrono y bloqueante de eventos de dominio (`extraccion.aprobada`, `documento.rechazado`).
* Despacho HTTP seguro a endpoints de terceros con estricta prevención SSRF y firmado criptográfico (HMAC).
* Manejo de reintentos (retry policy) y Dead Letter Topic (DLT) para webhooks fallidos.
* Registro de entregas y fallos en la base de datos de tenant y publicación de eventos de resultado.

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
  * `extraccion.aprobada` (desde `dominio.documentos`)
  * `documento.rechazado` (desde `dominio.documentos`)
* **Eventos Publicados:**
  * `webhook.entregado` (hacia `dominio.documentos` mediante outbox)
  * `webhook.fallido` (hacia `dominio.documentos` mediante outbox)
  * Referencia: `contracts/events/webhook.entregado.v1.schema.json`, `contracts/events/webhook.fallido.v1.schema.json`.

## 6. Modelo de Datos
La persistencia reside en la base de datos lógica o física por tenant (aislada vía `AbstractRoutingDataSource`).

| Tabla | Propósito | Columnas Clave | Índices | Base de datos |
|---|---|---|---|---|
| `webhook_subscription` | Configuración de la URL y secretos | `id`, `url`, `events`, `secret`, `secondary_secret`, `active` | `idx_wh_active` | Tenant |
| `webhook_delivery` | Registro de intentos y estados | `id`, `webhook_id`, `document_id`, `event_type`, `status`, `attempts`, `last_attempt_at`, `error_msg` | `idx_wh_delivery_doc`, `idx_wh_status` | Tenant |
| `outbox` | Bandeja de salida transaccional (Kafka) | `id`, `topic`, `payload`, `created_at` | `idx_outbox_created` | Tenant |

## 7. Controles de Seguridad
* **SEC-027 (Prevención SSRF/TOCTOU):** El servicio valida la resolución DNS de la URL destino previo al envío, fijando la IP (DNS pinning). Se aborta si la IP pertenece a RFC1918 (privadas), loopback, 169.254/16 (metadata cloud), 100.64/10 o IPv6 ULA/link-local. Las redirecciones HTTP están explícitamente deshabilitadas en el WebClient.
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
* **Given** que se publica el evento `webhook.entregado` al tópico `dominio.documentos`
* **When** el auditor revisa el JSON enviado
* **Then** se verifica que solo contiene `webhookId`, `documentId`, `tenantId`, sin datos extraídos de la persona.

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
