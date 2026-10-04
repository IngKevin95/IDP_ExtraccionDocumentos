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
* **Descripción:** Configurar un cliente HTTP (Netty/WebClient) con un `NameResolver` personalizado que aborte la conexión si la IP resuelta está en el rango RFC1918, 169.254.x.x o similares, y deshabilitar auto-redirecciones HTTP.
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
* **Descripción:** Implementar `@KafkaListener` que lee del tópico `dominio.documentos`. Realiza setup del contexto del tenant. Comprueba idempotencia (que el documento no se haya notificado previamente) y dispara la creación del `WebhookDelivery`.
* **Criterio de hecho:** Recepción del evento dispara proceso asíncrono.
* **Validación:** Prueba de integración con Kafka embebido (AC-02, AC-07).

**T-08: Patrón Outbox para Resultados**
* **Descripción:** Al finalizar la entrega (éxito o abandono tras reintentos), escribir a la tabla `outbox` los eventos `webhook.entregado` o `webhook.fallido`.
* **Criterio de hecho:** Se escriben los resultados asegurando la eliminación de PII en el JSON (solo ID documento y webhook ID).
* **Validación:** Test unitario validando la ausencia de datos sensibles (AC-08).
