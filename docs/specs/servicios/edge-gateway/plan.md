# Plan de Implementación: edge-gateway

## Módulo Maven
* El componente existirá en `services/edge-gateway`.

## Tipo de Aplicación
* Aplicación basada en Spring Cloud Gateway (Spring WebFlux / Project Reactor) debido a la naturaleza no bloqueante y alta eficiencia para proxies.
* Compilación a imagen nativa GraalVM opcional para bajo consumo de memoria y arranque instantáneo.

## Dependencias Principales
* `spring-cloud-starter-gateway`: Motor de enrutamiento.
* `spring-boot-starter-oauth2-resource-server`: Validación JWT vía JWKS.
* `spring-boot-starter-data-redis-reactive`: Limitación de tasa distribuida.
* `spring-boot-starter-actuator`: Healthchecks y métricas Prometheus.
* `resilience4j-spring-boot3`: Limitador de tasa en memoria (fallback local) y Circuit Breaker.
* Biblioteca corporativa interna (opcional para trazas de observabilidad, aunque `micrometer-tracing` cubrirá la mayoría).

## Paquetes y Clases
* `com.banco.idp.gateway`
    * `EdgeGatewayApplication`: Clase principal de Spring Boot.
* `com.banco.idp.gateway.config`
    * `SecurityConfig`: Configuración de Spring Security WebFlux (habilitando Resource Server JWT y CORS).
    * `RouteConfig`: Definición de rutas o proxy mediante `application.yml` (preferido para flexibilidad).
    * `RateLimitConfig`: Configuración de RedisRateLimiter y fallback con Resilience4j.
* `com.banco.idp.gateway.filters`
    * `SecurityAuditGlobalFilter`: Filtro global que captura peticiones rechazadas o fallidas (401, 403, 429) y vuelca logs estructurados seguros (sin payloads ni JWTs completos).
    * `FallbackRateLimitFilter`: Filtro personalizado que, ante fallo del bean `RedisRateLimiter`, delega a la implementación de límite por réplica.

## Configuración de Enrutamiento (`application.yml`)
* Predicados de ruta (Path) para delegar hacia:
    * `/v1/documents/**` -> `http://document-service`
    * `/v1/chat/sessions/**` -> `http://chat-service`
    * `/v1/tenant/users/**` -> `http://tenant-service`
    * `/v1/webhooks/**` -> `http://notification-service`
    * `/v1/review/tasks/**` -> `http://review-service`
* Filtros aplicados a rutas de subida (ej. documentos) usando predicados y filtros para limitar el tamaño: `RequestSize`.
* Filtro global `TokenRelay` para reenviar el JWT inalterado al microservicio (que hará la revalidación).

## Bases de Datos y Migraciones
* Este servicio carece de estado persistente y base de datos relacional. Por consiguiente, no posee Flyway.

## Estrategia de Tests
* **Unitarios:**
  * Tests sobre `SecurityAuditGlobalFilter` validando formato de log (mock de Logger).
  * Validar lógica de fallback del Rate Limiter local.
* **Integración (Testcontainers):**
  * `@SpringBootTest(webEnvironment = RANDOM_PORT)` con un contenedor WireMock simulando un IdP (JWKS) y los microservicios de backend.
  * Verificar enrutamiento correcto hacia WireMock basado en configuración YAML.
  * `RedisContainer` para validar que el `RedisRateLimiter` deniega tráfico por encima de la configuración. Bajar contenedor Redis durante el test para probar la degradación a Resilience4j en memoria.
* **Contrato:** No requiere publicar contrato de consumo a nivel REST.

## Seguridad y Controles
* Spring Security intercepta en primera capa; si falla la validación del emisor o firma contra JWKS, ni siquiera entra en el chain de filtros de enrutamiento.
* Deshabilitar cabecera `Server` y añadir cabeceras estrictas (HSTS, etc.) vía directivas del framework.
