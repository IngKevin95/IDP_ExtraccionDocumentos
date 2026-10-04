# Especificación: Extraction Service

## 1. Propósito
El servicio `extraction-service` es el motor de IA del IDP Bancario. Recibe la orden de extraer datos de un documento previamente renderizado, clasifica su tipología (embargo/desembargo), invoca al proveedor LLM configurado para obtener los campos y tablas, aplica validadores determinísticos y un modelo de fiabilidad (score), y decide autónomamente si el documento se auto-aprueba o si requiere derivarse a una revisión humana (HITL).

## 2. Alcance y no alcance

**En alcance:**
* Consumo asíncrono de comandos de extracción vía Kafka.
* Lectura de imágenes rasterizadas y capa de texto desde el almacenamiento del tenant.
* Invocación de modelos fundacionales (LLMs) mediante abstracciones de Spring AI.
* Ejecución de validadores determinísticos (radicado, NIT, CC, montos).
* Lógica de ruteo en cascada según umbrales de score calibrado ($T_{revisar}$, $T_{auto}$).
* Detección y reporte de intentos de prompt injection.
* Emisión de eventos de completitud o requerimiento de revisión.

**Fuera de alcance:**
* Exposición de APIs REST síncronas de negocio (puramente asíncrono guiado por eventos).
* Lógica de interfaz de usuario o colas visuales (delegado a `review-service`).
* Gestión del golden set o re-entrenamiento (delegado a `quality-service`).
* OCR de bajo nivel (depende del texto nativo extraído previamente por el `renderer` o visión pura del LLM).

## 3. Requisitos cubiertos
* **RF-201:** Clasificación de tipología (EC, EJ, DC, DJ).
* **RF-202:** Extracción basada en evidencia (con bounding boxes y texto literal).
* **RF-203:** Ruteo por score en cascada ($T_{revisar}$, $T_{auto}$).
* **RF-204:** Validadores determinísticos (falla dura envía a HITL).
* **RF-502:** Failover LLM y circuit breaker.
* **RN-02:** Exactitud numérica (letras vs números).
* **RN-03:** Validez de demandados (Módulo 11 para NIT, formato para CC).
* **RN-05:** Suma de tablas coincide con total.
* **RNF-101:** Aislamiento Multitenant (acceso a llaves y buckets de forma segregada).
* **RNF-105:** Resiliencia basada en eventos (claim-check en Kafka).

## 4. Reglas
1. **Idempotencia:** El procesamiento del mismo comando `extraccion.solicitada` (mismo `documentId` y `versionId`) debe ser idempotente, ignorándose si ya existe una extracción finalizada exitosamente para dicha versión.
2. **Cero PII en eventos:** Los eventos publicados solo contendrán IDs; los datos extraídos se persisten en el silo del tenant para ser consultados luego (claim-check).
3. **Cascada (Second Pass):** Si al menos un campo obligatorio obtiene un score donde $T_{revisar} \le score < T_{auto}$, el servicio debe realizar una segunda consulta al LLM (posiblemente a un modelo de respaldo o con distinto prompt) solo para los campos dudosos. Si persiste por debajo de $T_{auto}$, se envía a revisión.
4. **Falla determinística:** Cualquier fallo en las validaciones de las reglas RN-02, RN-03 o RN-05 establece automáticamente `requires_review = true` para el campo y el documento en general, independientemente del score probabilístico del LLM.

## 5. Contrato

### 5.1 Endpoints (OpenAPI)
El servicio NO expone API pública de negocio. Únicamente expone puertos internos para Actuator (health, metrics).

### 5.2 Eventos consumidos
* `extraccion.solicitada` (Comando): Desencadena el flujo. Payload incluye `documentId`, `tenantId`, `versionId`.

### 5.3 Eventos publicados
* `extraccion.completada`: Publicado cuando la extracción completa todos los campos con score $\ge T_{auto}$ y sin fallos determinísticos. Esquema: `contracts/events/extraccion.completada.v1.schema.json`. (Estructura plana, sin objeto payload, incluye IDs, no incluye PII).
* `extraccion.requiere_revision`: Publicado cuando la extracción falla umbrales o validadores determinísticos y requiere intervención HITL. Esquema: `contracts/events/extraccion.requiere_revision.v1.schema.json`. (Estructura plana, sin objeto payload, incluye IDs, no incluye PII).
* `seguridad.prompt_injection_detectado`: Publicado si se detecta un ataque de inyección en la capa de texto. Esquema: `contracts/events/seguridad.prompt_injection_detectado.v1.schema.json`.

## 6. Modelo de datos
El servicio utiliza la base de datos PostgreSQL asignada al tenant (silo físico o lógico gestionado vía `libs/tenant-context`).
Tablas por tenant:
* **`extraction`**: `id` (PK, UUID), `document_id` (UUID, index), `tenant_id` (UUID), `status` (VARCHAR), `model_version` (VARCHAR), `prompt_version` (VARCHAR), `overall_score` (DECIMAL), `created_at` (TIMESTAMP).
* **`field_value`**: `id` (PK, UUID), `extraction_id` (FK), `field_name` (VARCHAR), `value_text` (TEXT), `confidence_score` (DECIMAL), `bounding_box` (JSONB), `requires_review` (BOOLEAN), `validation_error` (VARCHAR, null si no hay).
* **`outbox`**: Patrón outbox estándar de la plataforma para publicación atómica de eventos en Kafka.

## 7. Controles de seguridad
* **SEC-031 (Grounding):** Se exige al LLM retornar las coordenadas espaciales (bounding boxes) y el extracto de texto literal como evidencia de la fuente.
* **SEC-032 (Fidelidad):** Validadores programáticos impiden conversiones numéricas destructivas.
* **SEC-033 (Protección Prompt Injection):** Aislamiento entre instrucciones de sistema y datos del usuario, más filtro heurístico preventivo de directivas maliciosas.
* **SEC-034 (Control Alucinaciones):** Score bajo obliga indefectiblemente la revisión humana (HITL).
* **SEC-036 (Inmutabilidad de contexto IA):** Versiones del prompt y modelo quedan fijadas y registradas por transacción.
* **SEC-049 (Registro inmutable IA):** Este registro es capturado en el outbox y ruteado al WORM de `audit-service`.
* **SEC-050 (Claim-check):** Cero datos personales (PII) en los eventos de salida hacia Kafka.

## 8. Escenarios de aceptación

* **AC-01: Extracción exitosa (auto-aprobación).** Given un comando `extraccion.solicitada` para un oficio nítido, When el LLM retorna todos los campos y los validadores aprueban con score $> T_{auto}$, Then se guarda en la base local y se publica `extraccion.completada` vía outbox.
* **AC-02: Extracción en cascada.** Given una extracción inicial con un campo (ej. fecha) con score entre $T_{revisar}$ y $T_{auto}$, When se dispara la segunda pasada para ese campo y el modelo de respaldo obtiene score $\ge T_{auto}$, Then el documento es auto-aprobado y publica `extraccion.completada`.
* **AC-03: Falla de validador determinístico.** Given un oficio donde el LLM extrae "cien mil pesos" y un valor numérico "10000", When el validador evalúa RN-02, Then falla por discrepancia, marca el campo con `requires_review = true`, y publica `extraccion.requiere_revision`.
* **AC-04: Score bajo obliga revisión.** Given un documento borroso donde un campo crítico obtiene score $< T_{revisar}$, When se evalúan las reglas, Then se salta la cascada directa a revisión, marcando el estado y publicando `extraccion.requiere_revision`.
* **AC-05: Prompt injection directa detectado.** Given un oficio cuya capa de texto contiene "ignora las reglas anteriores", When la validación semántica procesa el texto, Then se aborta la extracción y se publica de inmediato `seguridad.prompt_injection_detectado`.
* **AC-06: Resiliencia ante falla del LLM.** Given una caída del LLM principal (HTTP 5xx), When se intenta extraer, Then se activa circuit breaker, intentando con el proveedor secundario de manera transparente.
* **AC-07: Aislamiento Multitenant de base de datos.** Given un comando para el tenant A, When se abren transacciones, Then el `tenant-context` asigna el pool dinámico Hikari de A y restringe lecturas/escrituras al silo de A; la filtración debe fallar ruidosamente y disparar alerta.
* **AC-08: Trazabilidad inmutable de versión.** Given una extracción completada, When se inspecciona la tabla `extraction`, Then deben persistirse inmutablemente las columnas `model_version` y `prompt_version` usadas.
* **AC-09: Idempotencia en consumo.** Given un comando `extraccion.solicitada` procesado previamente, When se recibe de nuevo, Then se ignora sin lanzar error y no se procesa nuevamente.

## 9. Métricas y SLO
* **Latencia de extracción:** p95 $< 15$ segundos por documento.
* **Tasa de errores no controlados (LLM timeouts/5xx continuos):** $< 0.1\%$.
* **Concurrencia (Bulkhead):** Adaptable dinámicamente, asegurando equidad entre tenants sin sobrepasar cuotas de APIs de LLM externas.

## 10. Dependencias
* **`document-service`**: Proveedor del evento inicial y orquestador maestro.
* **`libs/llm-port`**: Puerto Java para Spring AI (vLLM, Bedrock, Vertex).
* **`libs/storage-port` / `libs/kms-port`**: Para lectura segura de documentos y KEK.
* **`libs/tenant-context`**: Manejo del ciclo de vida de la conexión a DB multitenant.
