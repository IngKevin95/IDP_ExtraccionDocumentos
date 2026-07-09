# Git Flow (Vincent Driessen Model) — IDP Extracción Documentos

## Modelo estándar

```
main (production-ready)
  ↑
  ├─ release/v0.Y.Z → merge tags, bump version
  │
develop (integration)
  ↑
  ├─ feature/HU-XXX-slug → historias nuevas (linked a Factory HU)
  ├─ bugfix/HU-XXX-desc → fixes en desarrollo (linked a Factory HU)
  ├─ hotfix/vX.Y.Z-desc → production fixes (merge main + develop)
```

## Ramas principales

### `main` (protected)
- **Propósito**: Producción. Solo versiones estables.
- **Recibe de**: `release/*` (merge --no-ff + tag), `hotfix/*` (merge --no-ff + tag).
- **Protecciones**:
  - Requiere PR aprobado + checks verdes.
  - Cada merge = `git tag vX.Y.Z`.
  - Auto-deploy a producción (si CI/CD configurado).

### `develop` (protected)
- **Propósito**: Integración continua. Base para todas las features.
- **Recibe de**: `feature/*`, `bugfix/*` (merge --no-ff tras PR aprobado).
- **Protecciones**:
  - Requiere PR aprobado + tests pasando.
  - Auto-deploy a staging.

## Ramas de soporte

### `feature/HU-XXX-slug` (efímero)
- **Origen**: branquea de `develop`.
- **Naming**: `feature/HU-001-definir-tipologia`, `feature/HU-010-editar-campos`.
- **Ciclo de vida**:
  1. Crear rama.
  2. Commits atómicos (linked a HU en mensaje).
  3. Abrir PR → `develop`.
  4. Code review + tests verdes.
  5. Merge --no-ff + delete branch.
- **AC**: Actualizar HU en Factory: `estado: en-revision` → `estado: lista`.

### `bugfix/HU-XXX-desc` (efímero)
- **Origen**: branquea de `develop`.
- **Naming**: `bugfix/HU-010-fix-null-values`, `bugfix/HU-002-schema-validation`.
- **Diferencia vs. `feature/`**: fix que ocurrió en desarrollo (no en producción).
- **Merge**: igual que feature, a `develop`.

### `release/vX.Y.Z` (temporal)
- **Origen**: branquea de `develop`.
- **Naming**: `release/v1.0.0`, `release/v0.1.0-beta`.
- **Propósito**:
  - Bump version en package.json, CHANGELOG, etc.
  - Pruebas finales / hotfixes menores.
  - Merge a `main` (sin cambios posteriores).
- **Ciclo**:
  ```bash
  git checkout -b release/v1.0.0 develop
  # Editar version en package.json, CHANGELOG
  git commit -m "chore: bump version to v1.0.0"
  git checkout main
  git merge --no-ff release/v1.0.0
  git tag v1.0.0
  git checkout develop
  git merge --no-ff release/v1.0.0
  git branch -d release/v1.0.0
  ```

### `hotfix/vX.Y.Z-desc` (temporal)
- **Origen**: branquea de `main`.
- **Naming**: `hotfix/v1.0.1-auth-bypass`, `hotfix/v1.0.1-critical-crash`.
- **Propósito**: Bug crítico en producción.
- **Ciclo**:
  ```bash
  git checkout -b hotfix/v1.0.1-auth-bypass main
  # Fix + test
  git commit -m "fix(auth): bypass vulnerability [hotfix]"
  git checkout main
  git merge --no-ff hotfix/v1.0.1-auth-bypass
  git tag v1.0.1
  git checkout develop
  git merge --no-ff hotfix/v1.0.1-auth-bypass
  git branch -d hotfix/v1.0.1-auth-bypass
  ```

## Integración con Factory (Historias)

Cada rama de feature/bugfix está **linked a una HU** (`docs/04-historias/HU-XXX.md`):

| Rama | HU Estado | Acción |
|------|-----------|--------|
| `feature/HU-XXX-*` creada | `pendiente` → `en-desarrollo` | Update HU frontmatter |
| PR abierto | `en-desarrollo` → `en-revision` | PR body menciona `Closes #HU-XXX` |
| Merge a develop | `en-revision` → `lista` | Merge commit cierra la HU |

## Convenciones de commit

Seguir [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject> [HU-XXX]

<body>

<footer>
```

**Tipos válidos**:
- `feat` — feature nueva (HU implementada)
- `fix` — corrección de bug
- `refactor` — limpieza sin cambio funcional
- `test` — tests nuevos
- `docs` — documentación / Factory
- `chore` — setup, deps, CI

**Ejemplos**:
```
feat(HU-010): add edit-mode dialog for extracted fields [HU-010]

- Clickable "Editar campos" button in topbar
- Modal with 4 input fields
- Save/Cancel handlers

Closes #42

fix(HU-002): remove null pointer in validation [HU-002]

Closes #41
```

## CI/CD + Protecciones

| Rama | Pre-merge checks | Post-merge |
|------|---|---|
| Feature → develop | tests ✓, lint ✓, no secrets | deploy staging |
| Release → main | version bump ✓, CHANGELOG ✓ | deploy prod, tag |
| Hotfix → main | tests ✓, tag bumped | deploy prod, tag; merge develop |

## Checklist de buenas prácticas

- ✅ Ramas feature/bugfix tienen nombres cortos (max 50 chars).
- ✅ Commits son atómicos (1 cambio lógico = 1 commit).
- ✅ Merge commits tienen `--no-ff` (preservan historia de rama).
- ✅ Historias en Factory se actualizan con estado (`en-desarrollo` → `en-revision` → `lista`).
- ✅ PRs linkan a historias (`Closes #HU-XXX`).
- ✅ Tags semver en main (`v1.0.0`, `v1.0.1`, etc.).
- ✅ Hotfixes se propagan a develop (no dejar divergencias).
