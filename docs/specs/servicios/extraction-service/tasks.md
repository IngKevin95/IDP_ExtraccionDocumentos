# Tareas de Implementación: Extraction Service

## T-01: Configuración base del módulo y dependencias
* **Criterio de hecho:** El servicio arranca como aplicación Spring Boot, integra los módulos compartidos (`tenant-context`, `events`, `security`, `llm-port`) y pasa el health check básico.
* **Validación:** Arranca sin errores y expone `/actuator/health`.

## T-02: Scripts de migración Flyway
* **Criterio de hecho:** Scripts SQL en `db/migration/tenant` creados para `extraction` y `field_value`.
* **Validación:** Test de integración de Testcontainers con PostgreSQL levanta el esquema dinámicamente y crea las tablas. (Cubre AC-07 parcialmente).

## T-03: Entidades de Dominio y Persistencia
* **Criterio de hecho:** Implementación de las clases `Extraction`, `FieldValue`, junto con entidades JPA (`ExtractionEntity`, `FieldValueEntity`) y sus mappers (MapStruct o manual).
* **Validación:** Repositorio JPA guarda y lee la entidad correctamente con cascada de `field_value`.

## T-04: Listener de Kafka y Comando Inicial
* **Criterio de hecho:** `ExtractionKafkaListener` consume el comando `extraccion.solicitada` del tópico `documentos.eventos` (valida que llegó por el tópico de su productor, SEC-052); publica en `extraccion.eventos` según `EventTopology`. Configuración de idempotencia básica y deserialización correcta del payload.
* **Validación:** Inyectar un mensaje de prueba con KafkaTemplate y verificar que el log de recepción y parseo del comando es exitoso. (Cubre AC-09).

## T-05: Adaptador de Storage y Extracción de Contexto
* **Criterio de hecho:** Integración con `DocumentStoragePort` para descargar de S3 las imágenes (PNG) y la capa de texto nativa dado un `documentId` y `tenantId`.
* **Validación:** Test unitario simulando el storage retorna arreglos de bytes válidos o referencias correctas a enviar al LLM.

## T-06: Motor de Validación Determinística (ValidationEngine)
* **Criterio de hecho:** Implementación de `NitValidator` (módulo 11), `AmountMatchValidator` (letras vs numérico), y `TableSumValidator`. 
* **Validación:** Pruebas unitarias al 100% en casos positivos, negativos y casos límite para RN-02, RN-03, RN-05. (Cubre AC-03).

## T-07: Orquestador LLM (Primera pasada y Prompt Injection)
* **Criterio de hecho:** Clase `LlmOrchestrator` conectada a `Spring AI` que construye el prompt (sistema + usuario aislado). Incluye el filtro previo heurístico de prompt injection.
* **Validación:** Test mockeando al `LlmProvider` para retornar un JSON estructurado. Test verificando inyección rechaza y arroja `SecurityException`. (Cubre AC-05, AC-08).

## T-08: Procesador Principal (ExtractionProcessor) y Cascada
* **Criterio de hecho:** Orquestación E2E en `ExtractionProcessor`. Evalúa score general vs $T_{auto}$ y $T_{revisar}$. Implementa la lógica de segunda pasada (Second Pass) si cae en franja de duda.
* **Validación:** Test unitarios con mocks de scores (95 -> directo, 70 -> cascada -> 90 -> éxito, 40 -> fallo directo a revisión). (Cubre AC-01, AC-02, AC-04).

## T-09: Circuit Breaker y Resiliencia (Resilience4j)
* **Criterio de hecho:** Aplicación de anotaciones `@CircuitBreaker` y `@Bulkhead` sobre el adaptador LLM con fallback methods documentados.
* **Validación:** Test de integración mockeando servidor LLM con errores HTTP 500 y confirmando apertura del circuito e intento alternativo. (Cubre AC-06).

## T-10: Outbox y Publicación de Eventos
* **Criterio de hecho:** El servicio guarda el resultado final en DB y, en la misma transacción, inserta en la tabla `outbox` el payload JSON de `extraccion.completada` o `extraccion.requiere_revision`. 
* **Validación:** Test de integración que inserta el evento y valida mediante JSON Schema que la estructura (sin PII, patrón claim-check) es exacta.

## T-11: Pruebas End-to-End locales
* **Criterio de hecho:** Testcontainers combinando PostgreSQL, Kafka y un WireMock simulando el LLM. Envío de `extraccion.solicitada` y recolección de `extraccion.completada` desde el tópico de salida.
* **Validación:** Assert de que los registros se asientan en la DB y el mensaje exacto fluye por Kafka hacia `document-service` y `review-service`.
