# Plan de Diseño: Extraction Service

## 1. Módulo Maven
El servicio se ubicará en el módulo `services/extraction-service`, dependiendo de las bibliotecas base de la plataforma:
* `libs/tenant-context`
* `libs/events`
* `libs/security`
* `libs/storage-port`
* `libs/llm-port`
* `libs/observability`

## 2. Estructura de paquetes

Se utiliza arquitectura hexagonal (Ports & Adapters) adaptada a Spring Boot:

```
com.banco.idp.extraction
├── ExtractionServiceApplication.java
├── config/
│   ├── KafkaConfig.java             # Configuración de consumidor concurrente
│   ├── ResilienceConfig.java        # CircuitBreaker y Bulkhead para llamadas LLM
│   └── PromptConfig.java            # Carga de YAMLs desde contracts/tipologias/embargos.v1.yaml
├── domain/
│   ├── model/
│   │   ├── Extraction.java          # Entidad raíz de agregación
│   │   ├── FieldValue.java          # Entidad de campo extraído
│   │   └── DocumentType.java        # Enum (EC, EJ, DC, DJ)
│   ├── service/
│   │   ├── ExtractionProcessor.java # Lógica central y cascada
│   │   ├── LlmOrchestrator.java     # Abstracción de interacción con el modelo
│   │   └── ValidationEngine.java    # Motor determinístico de RN-02, RN-03, RN-05
│   └── validator/
│       ├── FieldValidator.java      # Interfaz base
│       ├── NitValidator.java        # Módulo 11 (RN-03)
│       ├── AmountMatchValidator.java# Letras vs Números (RN-02)
│       └── TableSumValidator.java   # Sumatoria de tablas (RN-05)
├── application/
│   └── port/
│       ├── in/
│       │   └── ProcessExtractionCommand.java
│       └── out/
│           ├── ExtractionRepository.java
│           ├── ExtractionEventPublisher.java
│           └── DocumentStoragePort.java
└── infrastructure/
    ├── adapter/
    │   ├── in/
    │   │   └── ExtractionKafkaListener.java # Adaptador primario Kafka
    │   └── out/
    │       ├── JpaExtractionRepository.java # Adaptador secundario DB
    │       ├── OutboxEventPublisher.java    # Implementación de evento vía Outbox
    │       └── S3DocumentStorageAdapter.java# Llama a libs/storage-port
    └── persistence/
        ├── ExtractionEntity.java
        ├── FieldValueEntity.java
        └── ExtractionJpaMapper.java
```

## 3. Lógica principal

* **LlmOrchestrator:** Se encarga de ensamblar el prompt usando los parámetros del contexto, incluyendo el texto extraído y/o las imágenes rasterizadas. Gestiona el enrutamiento principal vs el de "Second Pass" (cascada). Se apoya en Resilience4j para fallback transparente ante 5xx del `llm-port`.
* **ValidationEngine:** Recibe el mapa de `FieldValue` extraídos y aplica la lista de `FieldValidator` inyectados en Spring. Si alguno arroja excepción de validación, la captura, y setea `requires_review = true` y detalla el `validation_error`.
* **ExtractionProcessor:** La clase maestra. Coordina:
  1. Extraer imágenes/textos de Storage.
  2. Llamar a `LlmOrchestrator` para tipología general y extracción de primera pasada.
  3. Ejecutar `ValidationEngine`.
  4. Analizar scores: Si $T_{revisar} \le score < T_{auto}$, dispara a `LlmOrchestrator` en modo cascada focalizada.
  5. Calcular estado final, persistir `Extraction` y lanzar evento correspondiente usando `OutboxEventPublisher`.

## 4. Migraciones Flyway
Ruta: `src/main/resources/db/migration/tenant/` (Aplicado por `tenant-context` a cada esquema de tenant).
* `V1__init_extraction_tables.sql`: Crea tabla `extraction` y `field_value` con claves foráneas.

## 5. Estrategia de Tests
* **Unitarios:** Cobertura exhaustiva del `ValidationEngine` (casos límite de NITs, discrepancias de moneda, redondeos) y del árbol de decisiones del `ExtractionProcessor`.
* **Integración (Testcontainers):**
  * Base de datos PostgreSQL compartida levantada con Flyway simulando la persistencia y recuperación del silo del tenant.
  * Kafka Testcontainers para verificar que el listener recibe el evento, el Outbox inserta en BD, y el relay expulsa los esquemas `extraccion.completada` o `extraccion.requiere_revision`.
* **Mockeo Externo:** `WireMock` para simular las respuestas REST del proveedor LLM, verificando los circuit breakers y retries (Resilience4j). Al igual que respuestas controladas para testear inyecciones (SEC-033).
* **Test de Contrato (Spring Cloud Contract / JSON Schema):** Validar que los JSON de eventos generados cumplen estrictamente con los esquemas versionados descritos en `contracts/events/`.
