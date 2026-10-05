# Tareas de Implementación: edge-gateway

## T-01: Configuración de esqueleto de Spring Cloud Gateway
* **Descripción:** Crear el proyecto en `services/edge-gateway` con Spring Boot WebFlux, Cloud Gateway, y dependencias básicas (Actuator).
* **Criterios de Hecho:**
  * Contexto de Spring inicia correctamente.
  * Puerto expuesto por defecto en 8080.
  * Endpoints de Actuator (`/actuator/health`) funcionales.
* **Verificación:** Iniciar servicio y hacer GET a `/actuator/health` con 200 OK.

## T-02: Configuración de Rutas Base y Límite de Tamaño
* **Descripción:** Configurar `application.yml` para mapear los paths `/v1/documents/**`, `/v1/chat/sessions/**`, `/v1/tenant/users/**`, `/v1/webhooks/**`, `/v1/review/tasks/**` usando URIs abstractas (ej. `http://document-service`). Configurar filtro `RequestSize` para las rutas de carga.
* **Criterios de Hecho:**
  * Propiedades de ruteo y `RequestSize` (ej. max 20MB para documentos) definidas y cargadas.
* **Verificación:** Test de integración con WireMock que demuestre que una petición válida se reenvía (AC-03) y una de más de 20MB retorna 413 (AC-06).

## T-03: Implementación de Seguridad JWT (Resource Server)
* **Descripción:** Habilitar `spring-boot-starter-oauth2-resource-server`. Configurar `SecurityWebFilterChain` para requerir autenticación JWT (validando firmas contra JWKS uri externa).
* **Criterios de Hecho:**
  * Toda petición requiere token Bearer válido, exceptuando endpoints de Actuator.
  * El JWT es reenviado a los backends.
* **Verificación:** Test de integración de WireMock como JWKS. Petición sin token -> 401 (AC-01). Token expirado o mal firmado -> 401 (AC-02). Token válido -> Reenvío exitoso.

## T-04: Implementación de Rate Limiting con Redis
* **Descripción:** Configurar `RedisRateLimiter` usando la dependencia de spring data redis. Añadir el filtro `RequestRateLimiter` a las rutas globales o específicas, resolviendo la llave (`KeyResolver`) desde la IP del cliente o el `client_id` extraíble del Principal JWT.
* **Criterios de Hecho:**
  * Límite de tasa funcional limitando ráfagas masivas.
* **Verificación:** Test con Testcontainers de Redis. Realizar peticiones en bucle para provocar la obtención de HTTP 429 (AC-04).

## T-05: Degradación Graciosa de Rate Limit (Fallback en memoria)
* **Descripción:** Implementar un filtro (o `KeyResolver` y bean fallback alternativo) utilizando Resilience4j localmente en memoria. Este mecanismo debe entrar en acción si el bean de Redis arroja error (ej. timeout o desconexión).
* **Criterios de Hecho:**
  * El gateway no colapsa ni deniega todas las peticiones si Redis cae, sino que aplica límite local.
* **Verificación:** Test de integración con Redis bajado en mitad de la prueba. Peticiones deben seguir siendo procesadas o limitadas apropiadamente con 429 local (AC-05).

## T-06: Filtro Global de Auditoría de Seguridad (SecurityAuditGlobalFilter)
* **Descripción:** Desarrollar un `GlobalFilter` en reactor que intercepte la respuesta; si el status HTTP es 401, 403 o 429, genere un log estructurado (SLF4J/JSON) advirtiendo del bloqueo.
* **Criterios de Hecho:**
  * Logs en stdout carentes de body sensible o token JWT completo. El log muestra path, IP y causa de bloqueo.
* **Verificación:** Logs generados correctamente para flujos 401 y 429 (verificados mediante extensión de captura de logs de prueba) y filtro de errores de backend (AC-07).

## T-07: Configuración de Cabeceras de Seguridad y CORS
* **Descripción:** Ajustar `SecurityWebFilterChain` y configuración del gateway para anexar HSTS, remover `Server`, y establecer X-Content-Type-Options.
* **Criterios de Hecho:**
  * Las cabeceras están presentes en todas las respuestas.
* **Verificación:** Test de integración validando las cabeceras resultantes (AC-08).
