# Capa 2 — Procesamiento LLM

> Abstracción funcional de referencia (DocFly). Insumo para el PRD to-be de "IDP - Extracción de Documentos".

## Repos de referencia

| Repo | Tipo | Rol |
|---|---|---|
| `docfly-worker` | Cloud Function (8082) | Extracción vía Vertex AI/Gemini, consumidor Pub/Sub |
| `docfly-legal-document-classifier` | Cloud Run (8000) | Clasificación ML de tipo de documento legal |
| `docfly-embargos-table-extraction-api` | Cloud Run (8002) | Extracción de tablas estructuradas |

## Propósito funcional

Núcleo de inteligencia del pipeline: consume el mensaje publicado en Capa 1, extrae información no estructurada vía LLM, clasifica el tipo de documento y extrae tablas estructuradas cuando el documento las contiene.

## Flujo

1. **Extracción Vertex AI** (`docfly-worker`): consume Pub/Sub, descarga el PDF de Cloud Storage, invoca Vertex AI/Gemini con el prompt armado en Capa 1, escribe resultado en Firestore.
2. **Clasificación ML** (`docfly-legal-document-classifier`): modelo RandomForest con 96.5% de accuracy, clasifica el documento (ej. tipo de embargo/desembargo). Repo tratado como submodule externo en la documentación original — sin detalle completo de archivos en `ARCHITECTURE_ANALYSIS.md`.
3. **Extracción de tablas** (`docfly-embargos-table-extraction-api`): estrategia "winning" documentada explícitamente: `gemini-2.5-flash-lite` + `single_page` + `force image mode` — decisión de ingeniería ya validada empíricamente por el equipo DocFly, candidata a heredar directamente en el to-be salvo razón en contra.

## Stack técnico

- Python 3.10-3.13, `functions-framework`, Flask (worker), FastAPI (classifier, table-extraction)
- `google-cloud-aiplatform`, `google-genai` (Vertex AI/Gemini)
- `google-cloud-firestore`, `google-cloud-pubsub`, `google-cloud-storage`
- scikit-learn (RandomForest) en `docfly-legal-document-classifier`
- pandas/openpyxl para estructuración de tablas extraídas

## Interfaces expuestas

- `docfly-worker`: consumidor Pub/Sub (sin endpoint HTTP público de negocio, solo health).
- `docfly-legal-document-classifier` y `docfly-embargos-table-extraction-api`: APIs REST (Cloud Run).

## Multi-tenancy

Heredada de Capa 1 vía metadata en el mensaje Pub/Sub y en el documento Firestore (`customerid`). Esta capa no vuelve a resolver tenant, confía en lo ya resuelto aguas arriba.

## Notas de arquitectura (candidatas a ADR)

- **Elección de modelo por tarea, no un solo modelo genérico**: RandomForest para clasificación (rápido, barato, ya entrenado) vs. Gemini para extracción libre vs. Gemini configurado específicamente para tablas — separación de responsabilidades por tipo de tarea de IA. Candidato fuerte a ADR: "por qué no un solo LLM para todo".
- **Estrategia de extracción de tablas ya optimizada empíricamente** (`single_page` + `force image mode` + modelo lite) — evitar reinventar sin evidencia nueva.
- **Submodule externo** (`docfly-legal-document-classifier`): decisión de modularización/versionado a evaluar en el to-be (¿mantener como servicio separado con su propio ciclo de release, o integrar?).

## Notas de seguridad

- Ningún dato de entrenamiento del modelo RandomForest ni prompts completos se reproducen aquí. Los prompts de extracción son propiedad intelectual del cliente/proyecto original — el to-be debe diseñar los suyos propios, no copiar literalmente.
