# Tareas de Implementación: chat-service

## T-01: Configuración base del módulo
* **Descripción:** Crear el proyecto Maven `chat-service` dentro de `services/`, agregando dependencias base (Web, JPA, Kafka, Spring AI, libs internas). Configurar `application.yml` y dependencias Testcontainers.
* **Criterio de hecho:** La aplicación levanta el contexto de Spring en un test unitario vacío.
* **Validación:** Test `ChatServiceApplicationTests.contextLoads()` en verde.

## T-02: Migraciones Flyway y Entidades de Dominio
* **Descripción:** Escribir scripts SQL Flyway para esquema `tenant` habilitando la extensión `vector` y creando tablas `document_index_status`, `chunk`, `chat_session`, `chat_message`, `citation`. Crear las clases Entity JPA mapeadas con anotaciones Hibernate para pgvector.
* **Criterio de hecho:** Flyway ejecuta correctamente contra un Testcontainer de Postgres-pgvector y los repositorios guardan/recuperan entidades.
* **Validación:** Test de persistencia de JPA (DataJpaTest) para guardar y consultar un `ChatSession` y un `Chunk`.

## T-03: Caso de uso: Indexación de Documentos
* **Descripción:** Implementar `IndexDocumentUseCase` que descargue texto del ObjectStore, divida en segmentos (Text Splitter), genere embeddings vía `LlmEmbeddingAdapter`, y guarde masivamente en la tabla `chunk`.
* **Criterio de hecho:** Al recibir texto, se generan correctamente los vectores y se registran en BD asociados al `document_id`.
* **Validación:** Test de integración Mockeando LLM para embeddings, verificando que N chunks se guardan en BD (valida AC-01).

## T-04: Consumidor Kafka: `extraccion.aprobada`
* **Descripción:** Implementar listener Kafka con configuración idempotente. Al recibir el evento, extrae el `document_id` e invoca `IndexDocumentUseCase`.
* **Criterio de hecho:** El evento entrante dispara la indexación de forma asíncrona pero tolerante a fallos.
* **Validación:** Test con KafkaContainer y Mock del UseCase (valida AC-01 y SEC-050).

## T-05: Seguridad y Detección Prompt Injection
* **Descripción:** Implementar `PromptInjectionDetector`. Si detecta ataque, lanza excepción controlada que se mapea a un error 400 y publica transaccionalmente en outbox el evento `seguridad.prompt_injection_detectado`.
* **Criterio de hecho:** Textos maliciosos predefinidos lanzan la excepción y encolan el evento de auditoría.
* **Validación:** Test unitario y de integración que verifica la denegación (valida AC-04 y SEC-033).

## T-06: Caso de uso: Chat RAG
* **Descripción:** Implementar `SendMessageUseCase`. Ejecuta: 1) detector de inyección, 2) búsqueda vectorial filtrando por `document_id`, 3) armado de prompt RAG con instrucciones estrictas de citación y abstención, 4) invocación LLM, 5) parseo de citas a IDs de chunk, 6) almacenamiento en historial.
* **Criterio de hecho:** Genera respuestas precisas con `citation` en base de datos. Se abstiene si no recupera chunks relevantes.
* **Validación:** Mock LLM devolviendo texto con etiquetas predefinidas; verificación de extracción de citas y test de abstención (valida AC-02, AC-03, AC-06 y SEC-031).

## T-07: Caché Semántica
* **Descripción:** Modificar `SendMessageUseCase` para buscar en historial de la sesión si la consulta actual tiene alta similitud cosenoidal (>0.98) con un mensaje previo y emitir `chat.respuesta_desde_cache`.
* **Criterio de hecho:** Mensajes idénticos se responden instantáneamente sin llamar a `spring-ai-chat`.
* **Validación:** Test llamando 2 veces con el mismo input, verificando que el Mock del LLM se invoca solo 1 vez (valida AC-07).

## T-08: Controladores REST y Manejo de Errores
* **Descripción:** Crear `ChatController` para `POST /v1/chat/sessions` y `POST /v1/chat/sessions/{sessionId}/messages`. Implementar validación de propiedad de sesión (asegurar que el JWT actual coincide con el `user_id` de la sesión). Agregar `ControllerAdvice` para devolver la taxonomía de errores del banco.
* **Criterio de hecho:** API expone endpoints según contrato OpenAPI. Seguridad rechaza accesos cruzados.
* **Validación:** Test MVC mockeando UseCases, validando HTTP 200, HTTP 403 para usuarios incorrectos (valida AC-05, SEC-004).

## T-09: Integración de Autorización (Middleware)
* **Descripción:** Configurar `SecurityConfig` usando `libs/security-lib` para extraer contexto del tenant y validar que el usuario tenga acceso al documento específico previo a crear la sesión.
* **Criterio de hecho:** Un JWT válido pero sin rol/acceso al recurso es bloqueado tempranamente (403).
* **Validación:** Test E2E de seguridad de endpoints (valida AC-08 y SEC-007).
