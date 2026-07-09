# IDP - Extracción de Documentos

Plataforma de procesamiento inteligente de documentos legales colombianos (embargos/desembargos judiciales) usando LLM, multi-tenant sobre GCP.

## Stack

- **Backend**: Python/FastAPI (Ingesta, Procesamiento) · Go (Consulta) · Java/Spring Boot (BFF)
- **Frontend**: Next.js + TypeScript + Tailwind + shadcn/ui
- **Data**: Postgres + MongoDB (híbrido)
- **Cloud**: GCP (Cloud Functions Gen2, Firestore, Pub/Sub, Vertex AI/Gemini)

## Estructura

```
docs/             Factory methodology artifacts
├── 01-prd/       Product Requirements Document
├── 02-user-story-map/
├── 03-backlog/
├── 04-historias/ User Stories (HU-XXX)
├── 05-priorizacion/
├── 06-flows/     Mermaid navigation diagrams
├── 07-adr/       Architecture Decision Records
└── 00-referencia/ Git workflow, stack, aesthetic

prototipo/        Static HTML prototype (9 pages)
├── styles.css    Shared stylesheet (ADR-015 aesthetic)
├── index.html
├── ingesta.html
├── procesamiento.html
├── consulta.html
├── documento.html
├── tipologias.html
├── webhooks.html
├── kpis.html
└── piloto-embargos.html

CLAUDE.md         Project instructions (Factory + Git Workflow)
```

## Quick Start

### Git Workflow

This project uses **Git Flow** (Vincent Driessen) + **Conventional Commits**.

```bash
# Start a feature
git checkout develop
git checkout -b feature/HU-XXX-slug-corto

# Commit atomically
git commit -m "feat(HU-XXX): description [HU-XXX]"

# Push and create PR
git push -u origin feature/HU-XXX-slug-corto
# Create PR in GitHub: feature/HU-XXX-slug → develop
```

See `docs/00-referencia/engineering-git-workflow-master.md` for full guide.

### Prototype

Open `prototipo/index.html` in a browser. All pages link to each other (click-through prototype).

## Tenants

- **Banco Aurora**
- **NeoFin**
- **Fiduciaria Meridiano**

## Key Features (Prototype Phase)

✅ Ingesta de documentos (HU-003)
✅ Validación fail-fast (HU-002, HU-004)
✅ Clasificación de tipo documental (HU-007)
✅ Extracción de campos (HU-010 — editar)
✅ Consulta de resultados (HU-009)
✅ Configuración de tipologías (HU-002, HU-021 — crear nueva)
✅ Webhooks salientes (HU-016)
✅ KPIs de observabilidad (HU-011–HU-013)
✅ Gestión de usuarios y roles (HU-020)
✅ Piloto vertical: embargos (HU-015)

## Documentation

- **Factory Methodology**: `docs/00-referencia/gitflow-vincent.md`
- **Engineering Workflow**: `docs/00-referencia/engineering-git-workflow-master.md`
- **Architecture Decisions**: `docs/07-adr/`
- **Product**: `docs/01-prd/`

## Contributors

- Kevin Orduz (@IngKevin95)
- Claude (design, documentation)

---

**Status**: Prototype phase → Ready for feature development.
