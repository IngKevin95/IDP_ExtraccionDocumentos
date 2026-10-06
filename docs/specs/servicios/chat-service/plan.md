# Plan de Diseño: chat-service

## 1. Módulo Maven
El servicio residirá en el directorio `services/chat-service`.
* **Dependencias Principales:**
  * `spring-boot-starter-web`
  * `spring-boot-starter-data-jpa`
  * `spring-kafka`
  * `spring-ai-core`, `spring-ai-openai-spring-boot-starter` (o el adaptador correspondiente al modelo vigente).
  * `hibernate-vector` (o el soporte nativo de pgvector vía JDBC).
  * `libs/security-lib` (validación de tenant/roles).
  * `libs/tenant-context` (gestión del silo).
  * `libs/observability-lib` (trazas, logs sin PII).
  * `libs/kafka-outbox` (publicación transaccional).

## 2. Paquetes (Arquitectura Hexagonal / Clean Architecture)
* `com.banco.idp.chat.domain`: Entidades core (`ChatSession`, `ChatMessage`, `Chunk`, `DocumentIndexStatus`), excepciones de dominio (`PromptInjectionException`, `InsufficientInformationException`).
* `com.banco.idp.chat.application`: Casos de uso (`IndexDocumentUseCase`, `SendMessageUseCase`, `CreateSessionUseCase`).
* `com.banco.idp.chat.infrastructure.web`: Controladores REST (`ChatController`), DTOs, manejo global de excepciones.
* `com.banco.idp.chat.infrastructure.kafka`: Listeners de eventos (`ExtractionApprovedListener`), serialización de eventos salientes (`EventPublisherAdapter`).
* `com.banco.idp.chat.infrastructure.persistence`: Repositorios Spring Data JPA, adaptadores vectoriales (`PgVectorStoreAdapter`).
* `com.banco.idp.chat.infrastructure.llm`: Adaptadores de `spring-ai` (`LlmChatAdapter`, `LlmEmbeddingAdapter`, detectores de inyección).
* `com.banco.idp.chat.config`: Configuración de Spring, beans, interceptores.

## 3. Clases principales

* `ChatController`: Expone `POST /v1/chat/sessions` y `POST /v1/chat/sessions/{sessionId}/messages`. Maneja el binding de datos.
* `SendMessageUseCase`: Orquesta la detección de prompt injection, búsqueda en caché semántica, obtención de chunks (búsqueda vectorial), armado del prompt RAG y parseo de citas.
* `IndexDocumentUseCase`: Descarga el texto del documento (vía ObjectStore o evento si es pequeño, usualmente S3), particiona en chunks lógicos (párrafos/tablas), genera embeddings y persiste en `chunk`.
* `PgVectorStoreAdapter`: Encapsula las sentencias SQL nativas o abstracciones de `spring-ai` para ejecutar KNN sobre `embedding` filtrando explícitamente por `document_id`.
* `PromptInjectionDetector`: Interfaz con implementación basada en heurística y/o un modelo LLM pequeño de clasificación, antes del procesamiento RAG.

## 4. Configuración Spring
* `application.yml`: Configuración de base de CNPG, pool Hikari (dinámico vía OpenBao), tópicos de Kafka (escribe solo `audit.signals`; consumirá `document.events` para `extraccion.aprobada`), configuraciones base de Spring AI.
* `KafkaConsumerConfig`: Factory para consumers idempotentes con offsets manuales (ack local tras commit DB).
* `SecurityConfig`: Integración con `security-lib` para validar JWT, tenant y extraer claims (MDC logger).

## 5. Migraciones Flyway
Ruta: `src/main/resources/db/migration/tenant/` (se ejecutan en cada schema/silo).
* `V1__init_chat_schema.sql`: Creación de extensión `vector` si no existe. Creación de tablas `document_index_status`, `chunk`, `chat_session`, `chat_message`, `citation`.
* `V2__add_vector_index.sql`: Creación de índice HNSW sobre `chunk.embedding`.

## 6. Adaptadores y Puertos
* **Puerto `VectorStore`:** Interfaz agnóstica para guardar e interrogar chunks.
* **Puerto `ObjectStore`:** Para recuperar la capa de texto del documento a indexar desde el bucket del tenant.
* **Puerto `SecurityEventPublisher`:** Publicador específico para eventos que van a `audit.signals` (ej. `seguridad.prompt_injection_detectado`).

## 7. Estrategia de Tests
* **Unitarios (JUnit 5 + Mockito):** Validación estricta del prompt format, parsing de citas, casos de uso ignorando persistencia y LLM real (mockeando interfaces).
* **Integración (Testcontainers):**
  * `PostgreSQLContainer` con imagen de pgvector para probar búsquedas KNN correctas, aislamiento de `document_id` y persistencia.
  * `KafkaContainer` para probar consumo de `extraccion.aprobada` y publicación del outbox.
  * MockWebServer o WireMock para simular la API del LLM Provider (asegurando fallbacks o parseos correctos).
* **Pruebas de Contrato:** Uso de Spring Cloud Contract para asegurar compatibilidad de `extraccion.aprobada` provisto por document-service y los eventos publicados hacia Kafka.
* **Seguridad:** Test específico asegurando que enviar mensajes a sesión ajena dispara 403 (SEC-004) y que las excepciones de inyección se manejan con log y evento sin revelar detalles internos (SEC-033).
