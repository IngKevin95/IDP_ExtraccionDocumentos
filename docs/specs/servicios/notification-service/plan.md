# Plan de Implementación: Notification Service

## 1. Módulo Maven
* **Ruta:** `services/notification-service`
* **Parent:** Proyecto base Spring Boot 3.x con Java 21 LTS.
* **Dependencias Principales:**
  * `spring-boot-starter-web` (API de gestión)
  * `httpclient5` (Apache HttpClient 5: cliente saliente con `DnsResolver` propio, ver ADR 0027; sustituye a WebClient)
  * `spring-boot-starter-jdbc` (JDBC por silo, como document-service; sustituye a JPA)
  * `spring-boot-starter-data-jpa` e `hibernate-core`
  * `spring-kafka`
  * `flyway-core` y `flyway-database-postgresql`
  * `idp-tenant-context` (Librería propia para multi-tenant y `AbstractRoutingDataSource`)

## 2. Paquetes y Clases Principales
* **`com.idp.notification.api`**
  * `WebhookController`: Endpoints REST para CRUD de suscripciones (usando OpenAPI annotations).
  * `WebhookMapper`: MapStruct para conversión Entity <-> DTO.
* **`com.idp.notification.domain`**
  * `WebhookSubscription` (Entity JPA): Datos de suscripción, eventos suscritos y secretos HMAC.
  * `WebhookDelivery` (Entity JPA): Registro histórico de intentos por documento.
  * `WebhookEvent` (Enum): Tipos de eventos soportados.
* **`com.idp.notification.event`**
  * `DocumentEventConsumer`: `@KafkaListener` idempotente para `document.events` y `review.events` (valida el tópico de origen, SEC-052).
  * `OutboxEventPublisher`: Interfaz para guardar eventos `webhook.entregado` y `webhook.fallido` en la tabla outbox (transactional).
* **`com.idp.notification.infrastructure.http`**
  * `WebhookDispatcher`: Revalida la URL, firma y clasifica el resultado del envío (`ApacheWebhookTransport` es el transporte seguro).
  * `net.SsrfGuard` (DnsResolver) + `net.AddressPolicy` + `net.WebhookUrlPolicy`: lógica SEC-027. El resolvedor valida y fija las IP (RFC1918, CGNAT, link-local, IPv6, etc.); sin redirects ni reutilización de conexiones.
  * `HmacSignatureService`: Clase utilitaria para firmar payload (SEC-028).
* **`com.idp.notification.infrastructure.persistence`**
  * `WebhookSubscriptionRepository`, `WebhookDeliveryRepository`.

## 3. Configuración Spring
* **`application.yml`**:
  * Configuración del cliente HTTP con timeouts de conexión estrictos (ej. 3s connect, 5s read) para mitigar tarpitting.
  * Propiedades de Kafka (Consumer group específico: `notification-service-cg`).
  * Integración con la librería `idp-tenant-context` para proveer los pools dinámicos (HikariCP) según la llave del tenant en el ThreadLocal.

## 4. Migraciones Flyway
Las migraciones operan en el esquema aislado de cada tenant (aplicadas al aprovisionar el tenant).
* **`V1__create_webhook_tables.sql`**:
  * Tabla `webhook_subscription` (ID UUID, URL, secreto, activo).
  * Tabla `webhook_delivery` (ID, FK webhook_subscription, status, reintentos).
  * Tabla `outbox` (requerida por el patrón de publicación confiable).

## 5. Estrategia de Pruebas
* **Unitarias:**
  * `HmacSignatureServiceTest`: Validar vectores de prueba HMAC exactos para firmas conocidas.
  * `SsrfProtectionTest`: Comprobar que IPs como `10.x.x.x` o `169.254.169.254` lanzan `SsrfViolationException`.
* **Integración (Testcontainers):**
  * **WireMock:** Para simular el endpoint del core bancario (respuestas 200 OK y 503 Service Unavailable para probar retries). Validar que la cabecera HMAC llega correctamente a WireMock.
  * **Kafka Container:** Producir mensaje sintético de `extraccion.aprobada` y verificar que el consumidor se activa y procesa en el contexto de BD correcto.
  * **PostgreSQL Container:** Validar que el `OutboxEventPublisher` guarda la fila en la misma transacción que actualiza `webhook_delivery`.
* **Contrato:**
  * Verificación de la API generada a partir de `notification-service.yaml` (Springdoc).
