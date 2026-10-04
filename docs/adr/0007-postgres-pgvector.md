# ADR 0007: Almacenamiento Vectorial y Búsqueda con PostgreSQL y pgvector

## Estado
Aprobado

## Contexto
El IDP incluye una funcionalidad avanzada de chat e interrogatorio sobre los documentos cargados. Para habilitar esta capacidad mediante Generación Aumentada por Recuperación (RAG), el sistema debe almacenar representaciones vectoriales (embeddings) de los fragmentos de texto del documento y soportar consultas de similitud eficientes (búsqueda semántica). Es imperativo que esta búsqueda mantenga estrictas restricciones de seguridad: un usuario solo puede realizar búsquedas semánticas sobre fragmentos de documentos a los que esté explícitamente autorizado a acceder.

## Decisión
Se elige **PostgreSQL extendido con la extensión `pgvector`** como motor de base de datos vectorial para el soporte de la funcionalidad de chat sobre documentos, integrándose de forma nativa en la infraestructura de silo por tenant existente (ADR 0006).

1.  **Aislamiento y Consistencia:** Los vectores de los documentos residirán en la misma base de datos relacional del tenant donde se almacenan sus metadatos operativos (gestionada vía CloudNativePG). Esto garantiza que la información vectorial se beneficie del mismo nivel de aislamiento lógico, esquemas de backup (Barman Cloud), restauración (RPO) y crypto-shredding ya diseñados.
2.  **Mecanismo de Búsqueda sobre un Documento Específico (Fase 1):** Para interrogar a un oficio puntual, la consulta se realizará aplicando un filtro estricto de coincidencia exacta por el identificador del documento (`WHERE document_id = ?`) combinado con el cálculo de distancia semántica (ej. distancia coseno `<=>`). Para esta búsqueda intra-documento, **no se utilizarán índices HNSW (Hierarchical Navigable Small World)**, dado que el volumen de fragmentos (chunks) dentro de un único documento permite a PostgreSQL resolver la distancia exacta (secuencial sobre el subconjunto filtrado) de manera más eficiente y precisa, evitando la carga computacional de construir índices de vecindad aproximada.
3.  **HNSW para búsquedas Cross-Documento (Fase 2, Diferida):** Si en fases futuras se habilita la búsqueda de conocimiento transversal en el silo del tenant (entre múltiples oficios), solo entonces se habilitará la indexación HNSW utilizando el parámetro de extensión `hnsw.iterative_scan` para optimizar el particionamiento de la búsqueda, manteniendo los controles restrictivos de tenant.

## Alternativas Consideradas

*   **Motor de Búsqueda Vectorial Dedicado (ej. Pinecone, Qdrant, Milvus):** Descartado. Integrar una base de datos vectorial externa rompe la arquitectura de silo por tenant en PostgreSQL, incrementando drásticamente la carga operativa. Requeriría duplicar los controles de acceso (RBAC, llaves KMS, backups y sincronización de eliminaciones de datos para Habeas Data) en el motor secundario. En el caso de soluciones SaaS (Pinecone), expone PII a una nube externa rompiendo los esquemas de retención on-premise/soberanía de datos del banco.
*   **Búsqueda Semántica con Elasticsearch/OpenSearch:** Descartado para RAG inicial. Si bien OpenSearch provee búsqueda híbrida robusta (k-NN), su despliegue y manutención de clústeres JVM resulta pesado. Para los volúmenes de documentos proyectados en la Fase 1, `pgvector` ofrece un rendimiento competitivo consolidado sobre infraestructura relacional ya justificada.

## Consecuencias
*   **Positivas:** Simplicidad arquitectónica extrema. Los vectores participan en las mismas transacciones ACID que los metadatos; si se purga el registro de la tabla del documento, la llave foránea en cascada (ON DELETE CASCADE) elimina sus embeddings inmediatamente, garantizando el cumplimiento de borrado de datos (SEC-028). Reducción de costos al no mantener motores vectoriales adicionales.
*   **Negativas / Riesgos:** PostgreSQL escala verticalmente para el procesamiento matemático vectorial intensivo. Si la densidad de consultas (chats) se vuelve excesivamente alta, los nodos de lectura (réplicas) podrían requerir mayores recursos de cómputo (CPU) de forma prematura. Obliga a parametrizar cuidadosamente los pools de conexión de lectura/escritura.

## Controles de Seguridad Aplicables
*   **SEC-020:** Aislamiento lógico heredado de PostgreSQL. Garantiza que la búsqueda semántica jamás acceda a vectores de documentos de otros clientes.
*   **SEC-028:** Purga segura de datos; la consolidación relacional asegura que el borrado físico de los vectores (crypto-shredding) acompañe la destrucción del modelo relacional asociado.
*   **SEC-037:** Controles de acceso granulares. El diseño relacional facilita incluir validaciones de acceso basadas en el rol o permisos a nivel de fila antes de ejecutar operaciones vectoriales.