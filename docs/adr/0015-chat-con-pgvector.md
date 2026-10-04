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

## Alternativas consideradas
- Motores de bases vectoriales dedicadas (Milvus, Pinecone, Weaviate): Se rechazaron por violar las restricciones de residencia, soberanía y complejidad operativa.
- Búsqueda basada puramente en BM25 / Full-Text Search de Postgres: Insuficiente para lenguaje natural complejo.
- ElasticSearch con capacidades densas: Aumentaría significativamente los recursos JVM.

## Consecuencias
- Positivas: Simplificación masiva del stack operativo y de recuperación ante desastres (DR) integrando Barman Cloud. Cumplimiento estricto SEC-048.
- Negativas o costos: `pgvector` requiere afinar parámetros `m` y `ef_construction` para no afectar el rendimiento transaccional.

## Controles relacionados
SEC-001, SEC-004, SEC-019, SEC-031, SEC-033, SEC-038, SEC-048