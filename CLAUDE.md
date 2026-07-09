# CLAUDE.md

<!-- BEGIN factory-vertical v0.4.0 -->
## Vertical documentaria Factory

Este proyecto usa la vertical Factory (`@factory/spec-driven-product`) para producir y mantener artefactos de discovery / producto:

```
PRD → User Story Map → Backlog → Historias (AC G/W/T) → Priorización → Flows
```

### Estructura

```
docs/
├── 01-prd/                 PRD (12 componentes obligatorios)
├── 02-user-story-map/      Mapa estilo Jeff Patton (backbone + ranking + releases)
├── 03-backlog/             epicas.md + backlog.md (tabla ordenada)
├── 04-historias/           HU-XXX.md con frontmatter YAML obligatorio + AC en G/W/T
├── 05-priorizacion/        Aplicación de framework (MoSCoW/RICE/Valor-Esfuerzo/Eisenhower)
└── 06-flows/               Flujos de navegación en Mermaid, un archivo por épica
```

### Reglas duras (no negociables)

1. Toda Historia de Usuario vive en `docs/04-historias/HU-XXX.md` con frontmatter YAML obligatorio (`id, titulo, epica, prioridad, complejidad, estado`).
2. Todo Acceptance Criterion se escribe en **Given/When/Then**; nada de prosa libre. 3-5 escenarios incluyendo happy / error / edge.
3. Toda Historia pasa los 6 criterios **INVEST** antes de marcarse `estado: lista`.
4. La metodología canónica vive en el `METODOLOGIA.md` del paquete `@factory/spec-driven-product` (ruta exacta depende de dónde npm haya instalado el paquete global; típicamente `$(npm root -g)/@factory/spec-driven-product/METODOLOGIA.md`). Si una skill contradice la metodología, **gana la metodología**.
5. Ningún artefacto se marca como "final" sin pasar por su agente revisor (manual con `/factory:revisar` o automático si `FACTORY_AUTO_AUDIT=true`).

### Slash commands disponibles

| Operativos | Por artefacto |
|---|---|
| `/factory:onboard` — parametriza el proyecto | `/factory:prd` — escribe PRD |
| `/factory:flujo` — pipeline completo | `/factory:epicas` — descompone PRD |
| `/factory:revisar` — auditoría global | `/factory:mapa` — User Story Map |
|  | `/factory:historia` — historia de usuario |
|  | `/factory:ac` — criterios de aceptación BDD |
|  | `/factory:invest` — valida INVEST |
|  | `/factory:backlog` — backlog consolidado |
|  | `/factory:priorizar` — priorización |
|  | `/factory:flows` — flujos de navegación (Mermaid, por épica) |

### Contexto del proyecto

- **Nombre**: IDP - Extracción de Documentos
- **Naturaleza**: Plataforma nueva (to-be), inspirada en el sistema DocFly (referencia/no es el nombre del proyecto)
- **Dominio**: Procesamiento inteligente de documentos legales colombianos (embargos/desembargos judiciales), plataforma multi-tenant sobre GCP (Cloud Functions Gen2, Firestore, Pub/Sub, Vertex AI/Gemini)
- **Stakeholders**: Equipo de desarrollo · Clientes: Banco Aurora, NeoFin, Fiduciaria Meridiano (perfiles inspirados en clientes de referencia de DocFly)
- **Framework de priorización**: MoSCoW (asunción — no confirmado con usuario, ajustar si se requiere otro)

### Convenciones de naming

- Épicas: `EP-001`, `EP-002`, … (3 dígitos)
- Historias: `HU-001`, `HU-002`, … (3 dígitos)
- Slugs en kebab-case-sin-acentos

### Hooks de calidad (opt-in)

Dos flags opcionales en `.claude/settings.local.json`:

```json
{
  "env": {
    "FACTORY_AUTO_AUDIT": "true",
    "FACTORY_REFLECT_SESSION": "true"
  }
}
```

- `FACTORY_AUTO_AUDIT=true` → tras cada Write/Edit en `docs/`, dispara el agente revisor correspondiente. Cuando esté off (default), usar `/factory:revisar` manualmente.
- `FACTORY_REFLECT_SESSION=true` → al cerrar sesión, escanea heurísticas (campos no canónicos en frontmatter, AC fuera de G/W/T, secciones inesperadas en PRD) y escribe propuestas en `docs/.factory-suggestions.md` sin auto-editar nada. Útil para capturar convenciones emergentes del cliente.

<!-- END factory-vertical -->

## Git Workflow (Gitflow + Factory Integration)

### Ramas principales

- **`main`** — producción. Solo recibe merges desde `release/*` o `hotfix/*`. Cada merge = versión nueva.
- **`develop`** — integración continua. Base para todas las features. Recibe merges desde `feature/*` tras revisión.

### Naming de ramas por tipo

```
feature/HU-XXX-slug-corto        → Implementación de historia (HU-XXX)
bugfix/HU-XXX-descripcion        → Fix de historia ya en desarrollo
release/v0.Y.Z                   → Preparación de release (desde develop)
hotfix/v0.Y.Z-descripcion        → Fix crítico en producción (desde main)
```

Ejemplo:
- `feature/HU-003-subir-documento` — implementa HU-003
- `feature/HU-010-editar-campos-extraidos` — implementa HU-010
- `hotfix/v1.0.1-auth-bypass` — fix crítico

### Convención de commits (Conventional Commits)

```
<tipo>(<scope>): <asunto> [HU-XXX]

<cuerpo opcional, máx 72 chars/línea>

<footer opcional: Closes #PR, Co-Authored-By:, etc.>
```

**Tipos** (siempre minúscula):
- `feat` — nueva feature (HU implementada)
- `fix` — corrección de bug (HU en desarrollo)
- `refactor` — limpieza de código (sin cambio funcional)
- `test` — tests nuevos o corregidos
- `docs` — cambios en documentación / Factory artefactos
- `chore` — setup, deps, CI config

**Scope** (opcional, relacionado a épica o componente):
- `HU-XXX` — historia específica
- `ingesta`, `procesamiento`, `consulta`, `admin`, etc.

**Asunto**:
- Imperativo: "add", "fix", "refactor", nunca "added", "fixes"
- Max 50 caracteres
- Sin punto final

**Ejemplo válido**:
```
feat(HU-010): add edit-mode dialog for extracted fields

- Clickable "Editar campos" button in topbar
- Modal with 4 input fields (radicado, monto, juzgado, fecha)
- Save/Cancel handlers (no-op in prototype)
- Follows ADR-015 aesthetic (dialog, inputs, button styling)

Closes #42
Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>
```

### Estado de Historia ↔ Git (trackeo)

Cada historia en `docs/04-historias/HU-XXX.md` tiene un campo `estado`:

| Estado | Rama | Acción |
|--------|------|--------|
| `pendiente` | — | No hay rama. Esperando inicio. |
| `en-desarrollo` | `feature/HU-XXX-*` activa | Equipo trabajando. Commits diarios. |
| `en-revision` | PR abierto (HU-XXX → develop) | Code review en curso. |
| `lista` | Mergeado en develop | Commit merge creado. Test coverage OK. |
| `en-produccion` | Mergeado en main | Release tag v0.Y.Z aplicado. |

**Importante**: Update historia `estado` al abrir PR, no al mergear. El merge cierra automáticamente la PR.

### Flujo por feature (paso a paso)

1. **Crear rama**:
   ```bash
   git checkout develop
   git pull origin develop
   git checkout -b feature/HU-XXX-slug-corto
   ```

2. **Actualizar historia**:
   - Abrir `docs/04-historias/HU-XXX.md`
   - Cambiar `estado: pendiente` → `estado: en-desarrollo`
   - Commit: `docs(HU-XXX): mark as en-desarrollo`

3. **Implementar + commits atómicos**:
   - Cada commit = 1 cambio lógico (no "fix typo" + "add feature" en un commit)
   - Linked a HU en mensaje: `feat(HU-XXX): [descripción] [HU-XXX]`
   - Ejemplo commits secuencia:
     ```
     feat(HU-003): add upload form panel [HU-003]
     feat(HU-003): add validation fail-fast alerts [HU-003]
     feat(HU-003): add ingesta queue table [HU-003]
     ```

4. **Code review (PR en GitHub)**:
   - Push: `git push origin feature/HU-XXX-slug-corto`
   - Crear PR: `feature/HU-XXX-slug-corto` → `develop`
   - PR title: `feat(HU-XXX): descripcion breve`
   - PR body: checksumario de commits, screenshot/demo si aplica
   - Actualizar historia: `estado: en-revision`

5. **Merge**:
   - ✅ Aprobaciones (code review + tests)
   - Merge commit message: `Merge branch 'feature/HU-XXX-slug-corto' into develop (HU-XXX)`
   - Actualizar historia: `estado: lista`
   - Cerrar rama: `git branch -d feature/HU-XXX-slug-corto`

### Release (main)

Cuando `develop` acumula features listas, preparar release:

```bash
git checkout -b release/v0.Y.Z develop
# Bump version en package.json, CHANGELOG, etc.
# Commits: "chore: prepare v0.Y.Z"
git checkout main
git merge --no-ff release/v0.Y.Z
git tag v0.Y.Z
git push origin main --tags
git checkout develop
git merge --no-ff release/v0.Y.Z
git branch -d release/v0.Y.Z
```

### Hotfix (producción)

Bug crítico en `main`:

```bash
git checkout -b hotfix/v0.Y.(Z+1)-descripcion main
# Fix + test
git commit -m "fix: descripcion [hotfix v0.Y.(Z+1)]"
git checkout main
git merge --no-ff hotfix/v0.Y.(Z+1)-descripcion
git tag v0.Y.(Z+1)
git checkout develop
git merge --no-ff hotfix/v0.Y.(Z+1)-descripcion
git branch -d hotfix/v0.Y.(Z+1)-descripcion
git push origin main develop --tags
```

### PR Template (GitHub)

```markdown
## 📋 Descripción
Qué hace esta PR. Vinculado a historia(s).

## 📝 Historias
- Closes #HU-XXX
- Closes #HU-YYY

## ✅ Checklist
- [ ] Commits son atómicos y bien descritos
- [ ] Historias updatadas (estado → en-revision)
- [ ] Tests pasan (si aplica)
- [ ] Sin conflictos con develop

## 🎬 Demo / Screenshots
[adjunta si es UI]

## Co-Authored-By
Co-Authored-By: [usuario] <[email]>
```

### CI/CD Integration

- **Pre-merge checks** (rama feature):
  - Tests pasan
  - Linting OK
  - No secrets en diff (git-secrets)
  
- **Post-merge** (develop):
  - Deploy a staging
  - Smoke tests
  
- **Post-tag** (main):
  - Deploy a producción
  - Monitoring activo

---

**Resumen**: cada HU → rama feature → commit atómico → PR → code review → merge a develop → release a main. Historiasactualizadas en `docs/04-historias/` con estado sincro al git workflow.
