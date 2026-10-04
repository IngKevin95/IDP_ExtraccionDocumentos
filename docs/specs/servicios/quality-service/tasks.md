# Tareas de Implementación: Quality Service

## Fase 1: Esqueleto y Configuración Base
* **T-01:** Crear módulo Maven `quality-service` con dependencias de Spring Boot, JPA, Kafka y Seguridad.
  * *Criterio de hecho:* El microservicio compila y arranca conectándose a PostgreSQL y Kafka locales.
* **T-02:** Definir el esquema de Flyway `V1__init_quality_schema.sql` con las tablas de métricas agregadas y Golden Set en la base de datos de control.
  * *Criterio de hecho:* La base levanta las tablas sin errores en un entorno de integración.

## Fase 2: Ingesta de Eventos de Calidad
* **T-03:** Implementar modelo de dominio (entidades JPA) para `QaMetricsDaily`, `QaFieldError` y `GoldenSetDocument`.
  * *Criterio de hecho:* Tests JPA guardan y recuperan registros exitosamente.
* **T-04:** Implementar el `QualityEventConsumer` para suscribirse a `extraccion.aprobada.v1`.
  * *Criterio de hecho:* (AC-02) Ingerir el evento incrementa el contador de STP para el `tenant_id` y día actual en base de datos.
* **T-05:** Ampliar `QualityEventConsumer` para suscribirse a `revision.completada.v1`.
  * *Criterio de hecho:* (AC-01, AC-05) Las correcciones incrementan contadores de error por campo SIN guardar los datos originales.
* **T-06:** Implementar lógica de Muestreo Ciego (*Blind Sampling*).
  * *Criterio de hecho:* (AC-03) Si el evento de revisión tiene el flag `blind_sample=true`, se incrementa la estadística exclusiva de `silent_error_count`.

## Fase 3: APIs y Detección de Deriva
* **T-07:** Exponer API REST `GET /v1/quality/reports/stp` y `GET /v1/quality/reports/silent-error`.
  * *Criterio de hecho:* (AC-06) Un usuario autorizado recibe agregaciones temporales JSON correctamente formateadas.
* **T-08:** Implementar seguridad RBAC en las APIs.
  * *Criterio de hecho:* (AC-07) Usuarios sin el rol de `Data Steward` reciben HTTP 403.
* **T-09:** Desarrollar el job/lógica de `DriftDetectionService`.
  * *Criterio de hecho:* (AC-08) El sistema genera flags de alarma si la tasa de error silente en un día rebasa el umbral configurable (ej. 5%).

## Fase 4: Golden Set
* **T-10:** Exponer CRUD REST para `GoldenSetDocument` bajo `/v1/quality/golden-set`.
  * *Criterio de hecho:* Los usuarios autorizados pueden cargar JSON de oficios ficticios.
* **T-11:** Implementar el orquestador asíncrono para `POST /v1/quality/golden-set/evaluate`.
  * *Criterio de hecho:* (AC-04) Responde 202 Inmediatamente. Lanza el proceso asíncrono que simula extracciones y graba resultados en `golden_set_evaluation`.