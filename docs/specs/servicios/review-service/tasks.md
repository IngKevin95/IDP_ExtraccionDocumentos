# Tareas de Implementación: Review Service

| ID | Tarea | Criterio de Hecho | AC / Test |
|---|---|---|---|
| T-01 | Setup del módulo base | Módulo `review-service` creado con dependencias Maven (Spring Web, Data JPA, Security, Kafka, `tenant-context`). `application.yml` base configurado. | El módulo compila y arranca el contexto Spring vacío en test. |
| T-02 | Definir contratos OpenAPI y JSON Schema | Archivos `contracts/openapi/review-service.yaml` y `contracts/events/revision.completada.v1.schema.json` creados en el repositorio de contratos. | Validación de linting (Spectral o similar) sobre el YAML y JSON Schema. |
| T-03 | Migraciones Flyway de base de datos | Creado `V1__create_review_task_tables.sql` con las tablas `review_task` y `correction`. | Ejecución exitosa de Flyway en Testcontainers durante test. |
| T-04 | Capa de Dominio (Entidades y Repositorios) | Entidades `ReviewTask` y `Correction` mapeadas con JPA. Repositorios de Spring Data creados. | Test de integración de JPA guarda y recupera datos. |
| T-05 | Lógica de Transiciones (Service) y Regla 4 Ojos | Implementado `ReviewTaskService` con reglas para detección de campos críticos y prevención de auto-aprobación en segundo paso (SEC-009). | Tests unitarios para todos los flujos de estado de AC-03, AC-04, AC-05, AC-06. |
| T-06 | API REST: Endpoints de Consulta y Correcciones | Controladores `ReviewTaskController` con endpoints `GET /v1/review/tasks`, `GET /v1/review/tasks/{taskId}`, y `POST /v1/review/tasks/{taskId}/corrections` con soporte multitenant vía JWT. | Test MVC devuelve 200 OK y 404 para tareas de otro tenant (AC-08). |
| T-07 | API REST: Endpoints de Aprobación y Rechazo | Controladores para `/approve`, `/approve-secondary`, `/reject` (bajo `/v1/review/tasks/{taskId}`). Llaman al service y manejan excepciones HTTP 403 y 400. | Tests MVC con MockMvc verifican estados HTTP correspondientes (AC-06 y AC-07). |
| T-08 | Patrón Outbox Transaccional | Implementada la entidad `OutboxEvent` y guardado atómico del evento `revision.completada` dentro del Service. | Test verifica que al aprobar, se crea el registro en la tabla outbox del tenant. |
| T-09 | Consumidor Kafka (Creación de Tareas) | `ExtractionEventConsumer` escucha `extraccion.requiere_revision`, procesa idempotentemente y crea `review_task`. | Test de integración inyecta mensaje en Kafka de prueba y verifica AC-01 y AC-02 (Idempotencia). |
| T-11 | Revisión ciega de muestras de calidad | `calidad.muestra_ciega_solicitada` crea tarea con `blind_sample=true`; enmascarado de salida del modelo y score; cierre sin cuatro ojos con `revision.completada` `blindSample=true`; independencia del revisor. | AC-09, AC-10, AC-11, AC-12 (`ReviewBlindSampleIntegrationTest`). |
| T-10 | Relay Outbox (Opcional/Configuración) | Configuración de tarea programada (Scheduler) o componente Debezium/Kafka Connect para extraer del outbox y publicar en el tópico global. | Evento llega al tópico `revision.completada` en test End-to-End. |
