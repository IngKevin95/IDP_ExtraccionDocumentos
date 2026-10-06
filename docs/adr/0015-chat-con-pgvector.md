# 0015. Chat con pgvector

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El sistema IDP bancario no solo realiza extracción transaccional, sino que expone un chat interactivo para responder preguntas puntuales (RAG) sobre expedientes voluminosos. Para lograr esto de forma segura y respetando la arquitectura multi-tenant y las políticas de retención, necesitamos indexar fragmentos de texto (chunks) y sus correspondientes embeddings en un motor de búsqueda vectorial. 

## Decisión
Utilizamos la extensión `pgvector` sobre las bases de datos PostgreSQL exclusivas de cada tenant (gestionadas mediante el instance manager de CNPG y Barman Cloud) para el almacenamiento y búsqueda vectorial del `chat-service`.
1. Arquitectura de índice y búsqueda: Para el chat sobre un documento específico, se realizará búsqueda exacta filtrada por `document_id` (sin HNSW). El índice HNSW (Hierarchical Navigable Small World) con `hnsw.iterative_scan` se usará exclusivamente para la búsqueda entre documentos (fase 2).
2. Aislamiento natural: Al residir dentro de la misma instancia aprovisionada por el `tenant-service`, los vectores heredan inmediatamente el aislamiento físico y las políticas de retención.
3. Citación estricta y control de alucinaciones (SEC-048): El `chat-service` inyecta los fragmentos en el prompt. Es obligatoria la abstención del chat ("información insuficiente") sin inventar datos cuando el contexto recuperado no alcanza para responder la consulta.
4. Detección de PII y Eventos de Auditoría: El texto pasa por el motor de DLP para bloquear PII. Se publican eventos obligatorios del catálogo como `chat.respuesta_bloqueada`, `chat.respuesta_desde_cache` y `seguridad.prompt_injection_detectado`.

5. Cifrado en reposo (enmienda tras la auditoría de F6): el texto de los fragmentos, de los mensajes y de las citas se guarda cifrado con el sobre del tenant (AES-GCM, DEK por dato envuelta con la KEK de datos del tenant, AAD `tenantId|documentId|recordId|campo`) en columnas `bytea`. Solo se descifra al servir. Los embeddings siguen en claro porque pgvector los necesita para buscar; son invertibles parcialmente. Riesgo residual aceptado, con estas mitigaciones: silo por tenant, crypto-shredding (al destruir la KEK el texto queda ilegible y los vectores solos no lo reconstruyen de forma práctica) y purga física de los vectores con `documento.purgado`.
6. Purga Habeas Data (SEC-022): el chat consume `documento.purgado` (`document.events`, con `EventOriginGuard`) de forma idempotente y borra citas, mensajes (y con ellos la caché), sesiones, fragmentos y estado de indexación del documento. Excepción documentada a la inmutabilidad de `chat_message` y `citation`: los triggers rechazan siempre `UPDATE` y `TRUNCATE`, y rechazan `DELETE` salvo que la transacción haya fijado `idp.purge_document_id` (con `set_config(..., true)`, solo lo hace el consumidor de purga) al documento al que pertenecen las filas; el borrado de filas de otro documento se rechaza igual.
7. Protección del proveedor y del gasto (SEC-030, ADR 0023): límite de tasa por (tenant, usuario) y por tenant, tope diario de tokens por tenant contado en el silo (`chat_token_usage`), bulkhead por tenant hacia el LLM, timeout real de la llamada y failover al modelo secundario si está configurado. Todo se evalúa antes de calcular el embedding. Los modelos se fijan por versión y se registran por respuesta con la versión del prompt y la huella de configuración (SEC-036, SEC-049).
8. Verificación de cifras (SEC-048): además de la cita literal, el texto libre de la respuesta no puede afirmar cifras, montos (en dígitos o letras), fechas ni monedas que no estén, tras normalizar formato, en las citas válidas o en el fragmento citado. La caché semántica exige coseno >= 0.995 y misma pregunta normalizada o mismos tokens significativos.

## Alternativas consideradas
- Motores de bases vectoriales dedicadas (Milvus, Pinecone, Weaviate): Se rechazaron por violar las restricciones de residencia, soberanía y complejidad operativa.
- Búsqueda basada puramente en BM25 / Full-Text Search de Postgres: Insuficiente para lenguaje natural complejo.
- ElasticSearch con capacidades densas: Aumentaría significativamente los recursos JVM.

## Consecuencias
- Positivas: Simplificación masiva del stack operativo y de recuperación ante desastres (DR) integrando Barman Cloud. Cumplimiento estricto SEC-048.
- Negativas o costos: `pgvector` requiere afinar parámetros `m` y `ef_construction` para no afectar el rendimiento transaccional. Cada fragmento cifrado implica una llamada de envoltura al KMS al indexar y un desenvolvimiento por fragmento recuperado al responder. La verificación de cifras es conservadora: puede bloquear una respuesta correcta que escriba una cifra que no figura literalmente en la cita (por ejemplo una suma).

## Controles relacionados
SEC-001, SEC-004, SEC-015, SEC-019, SEC-022, SEC-030, SEC-031, SEC-033, SEC-036, SEC-038, SEC-048, SEC-049, SEC-054