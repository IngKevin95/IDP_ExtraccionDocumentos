# Tareas: Notification Service

**T-01: Setup del módulo y migraciones**
* **Descripción:** Crear estructura de Maven para `notification-service`, configurar `application.yml` y dependencias (Spring, Kafka, idp-tenant-context). Crear `V1__create_webhook_tables.sql` para el tenant.
* **Criterio de hecho:** La aplicación levanta sin errores de contexto y Flyway inicializa correctamente las tablas en una base de datos local de desarrollo.
* **Validación:** Ejecución de contexto básico de Spring Boot.

**T-02: Entidades JPA y Repositorios**
* **Descripción:** Implementar las entidades `WebhookSubscription`, `WebhookDelivery` y los repositorios Spring Data JPA correspondientes.
* **Criterio de hecho:** Los repositorios permiten guardar y consultar suscripciones activas por eventos (CRUD básico en BD).
* **Validación:** Tests de integración locales con `@DataJpaTest` y `Testcontainers`.

**T-03: Implementar API CRUD para Suscripciones**
* **Descripción:** Desarrollar `WebhookController` basado en el contrato `notification-service.yaml`. Implementar generación segura de secreto HMAC al crear.
* **Criterio de hecho:** Endpoint `/v1/webhooks` permite alta, baja y listado aislando por tenant.
* **Validación:** Test MVC contra el controlador (MockMvc) validando AC-01.

**T-04: Filtro de Prevención SSRF y DNS Pinning (SEC-027)**
* **Descripción:** Configurar un cliente HTTP (Apache HttpClient 5) con un `DnsResolver` personalizado que aborte la conexión si la IP resuelta está en el rango RFC1918, 169.254.x.x o similares, y deshabilitar auto-redirecciones HTTP.
* **Criterio de hecho:** Peticiones salientes a metadatos de AWS/GCP o a la red local del cluster son bloqueadas a nivel de Socket antes de abrir la conexión TCP.
* **Validación:** Pruebas unitarias de resolución (AC-03, AC-04) pasadas exitosamente.

**T-05: Implementar HmacSignatureService (SEC-028)**
* **Descripción:** Crear el servicio utilitario para generar la firma `HMAC-SHA256` concatenando `timestamp` + `payload.toString()`.
* **Criterio de hecho:** El servicio produce una cabecera `X-Hub-Signature-256` verificable.
* **Validación:** Test unitario validando formato y robustez frente a ataques de timing (AC-05).

**T-06: Despachador de Webhooks y Lógica de Retry**
* **Descripción:** Crear `WebhookDispatcher` que toma el registro de entrega y ejecuta el POST asíncrono con el cliente seguro, gestionando timeouts y reintentos (ej. `@Retryable` o política de Reactor).
* **Criterio de hecho:** Errores transitorios de red o HTTP 5xx provocan reintento exponencial. Errores de cliente (400, 401, SSRF) abortan inmediatamente.
* **Validación:** Testcontainers + Wiremock (AC-06).

**T-07: Consumidor Kafka Idempotente (`extraccion.aprobada`)**
* **Descripción:** Implementar `@KafkaListener` que lee de `document.events` y `review.events` (ignora con alerta SECURITY los eventos que no llegan por el tópico de su productor, SEC-052). Realiza setup del contexto del tenant. Comprueba idempotencia (que el documento no se haya notificado previamente) y dispara la creación del `WebhookDelivery`.
* **Criterio de hecho:** Recepción del evento dispara proceso asíncrono.
* **Validación:** Prueba de integración con Kafka embebido (AC-02, AC-07).

**T-08: Patrón Outbox para Resultados**
* **Descripción:** Al finalizar la entrega (éxito o abandono tras reintentos), escribir a la tabla `outbox` los eventos `webhook.entregado` o `webhook.fallido`.
* **Criterio de hecho:** Se escriben los resultados asegurando la eliminación de PII en el JSON (solo ID documento y webhook ID).
* **Validación:** Test unitario validando la ausencia de datos sensibles (AC-08).

**T-09: Política del tenant, allowlist y validación de URL**
* **Descripción:** `webhook_tenant_policy` (allowlist de hosts, reintentos), `WebhookUrlPolicy` (HTTPS, sin credenciales, sin IP literal ni nombres internos) y `/v1/webhooks/policy`.
* **Criterio de hecho:** El alta rechaza destinos fuera de política con códigos estables y el despachador revalida antes de cada envío.
* **Validación:** `WebhookUrlPolicyTest`, `WebhookApiIntegrationTest`, `WebhookHttpsOnlyIntegrationTest` (AC-10, AC-16).

**T-10: Secretos cifrados y rotación**
* **Descripción:** `WebhookSecrets` (SecureRandom, sobre con KEK del tenant, AAD tenant+webhook), `secret/rotate` y `secret/previous`, dos firmas durante la rotación.
* **Criterio de hecho:** El secreto se muestra una vez, se guarda cifrado y la rotación no interrumpe a los receptores.
* **Validación:** `WebhookApiIntegrationTest`, `DeliveryFlowIntegrationTest`, `HmacSignatureServiceTest` (AC-09, AC-11, AC-17).

**T-11: Historial, DLT y reintento manual**
* **Descripción:** `GET .../deliveries`, `POST .../deliveries/{id}/retry`, estado `FALLIDO` como DLT, arrendamiento con `SKIP LOCKED`.
* **Criterio de hecho:** Las entregas agotadas quedan consultables y reencolables sin duplicar el id de evento.
* **Validación:** `DeliveryFlowIntegrationTest`, `NotificationPostgresKafkaIntegrationTest` (AC-12).

**T-12: Anti-SSRF exhaustivo y E2E**
* **Descripción:** Pruebas con resolvedor falso (rebinding, registros mixtos, rangos IPv4/IPv6, redirects) y E2E con PostgreSQL y Kafka reales.
* **Criterio de hecho:** Ningún destino prohibido abre conexión; el consumidor es idempotente y los mensajes inválidos van al DLT.
* **Validación:** `AddressPolicyTest`, `SsrfGuardTest`, `ApacheWebhookTransportTest`, `SsrfFlowIntegrationTest` (AC-03, AC-04, AC-13, AC-14, AC-15).
