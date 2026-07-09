# Engineering Git Workflow — Master Guide

## Overview

Este documento detalla las **prácticas operacionales** que el equipo de ingeniería sigue para desarrollar IDP usando Git + GitHub + Factory (Product Discovery).

**Stack**: Git Flow (Vincent Driessen) + Conventional Commits + Factory Historias + Pull Requests + CI/CD checks.

---

## 1. Inicio de una historia (Feature)

### Paso 1: Preparar la rama

```bash
git checkout develop
git pull origin develop
git checkout -b feature/HU-XXX-slug-corto
```

**Naming**: `feature/HU-010-editar-campos`, `feature/HU-003-subir-documento`.

### Paso 2: Marcar historia en Factory como "en-desarrollo"

Editar `docs/04-historias/HU-XXX.md`:
```yaml
estado: en-desarrollo
```

Commit:
```bash
git add docs/04-historias/HU-XXX.md
git commit -m "docs(HU-XXX): mark as en-desarrollo"
```

### Paso 3: Implementar cambios

Hacer commits **atómicos** (1 cambio lógico por commit):

```bash
git commit -m "feat(HU-010): add edit button in topbar [HU-010]"
git commit -m "feat(HU-010): add modal dialog component [HU-010]"
git commit -m "feat(HU-010): add save/cancel handlers [HU-010]"
```

**Cada commit debe**:
- Tener un asunto claro (< 50 chars).
- Incluir el ID de historia en el mensaje (`[HU-XXX]`).
- Ser verificable por sí solo (tests pasan, no commits en medio roto).

---

## 2. Code Review (Pull Request)

### Paso 1: Push y crear PR

```bash
git push -u origin feature/HU-XXX-slug-corto
```

Abrir PR en GitHub:
- **De**: `feature/HU-XXX-slug-corto`
- **A**: `develop`

### Paso 2: PR Template

```markdown
## 📋 Descripción
Qué hace esta PR (resumen de commits).

## 📝 Linked Historias
- Closes #HU-XXX

## ✅ Checklist
- [ ] Commits son atómicos y bien descritos
- [ ] Historia updatada (estado → en-revision)
- [ ] Tests pasan (si aplica)
- [ ] Sin conflictos con develop
- [ ] AC del HU se cumplen

## 🎬 Demo / Screenshots
[Si es UI, adjunta screenshot]

Co-Authored-By: [usuario] <[email]>
```

### Paso 3: Actualizar historia a "en-revision"

Editar `docs/04-historias/HU-XXX.md`:
```yaml
estado: en-revision
```

Commit:
```bash
git add docs/04-historias/HU-XXX.md
git commit -m "docs(HU-XXX): mark as en-revision [HU-XXX]"
git push origin feature/HU-XXX-slug-corto
```

### Paso 4: Code review

- Equipo revisa commits, propone cambios.
- **Resolver conflictos**: rebase contra develop, no merge commit.
  ```bash
  git fetch origin
  git rebase origin/develop
  git push -f origin feature/HU-XXX-slug-corto
  ```
- Cuando todos aprueban (✅ checks, ✅ code review), proceder a merge.

---

## 3. Merge a develop

### Opción A: GitHub UI (recomendado)

En GitHub PR:
- Clic en "Squash and merge" **NO** — preservar historia, usar "Create a merge commit".
- GitHub crea merge commit automático: `Merge branch 'feature/HU-XXX-slug' into develop`.

### Opción B: Línea de comandos

```bash
git checkout develop
git pull origin develop
git merge --no-ff feature/HU-XXX-slug-corto
git push origin develop
```

### Paso después de merge

1. Actualizar historia a "lista":
   ```yaml
   estado: lista
   ```
   Commit:
   ```bash
   git add docs/04-historias/HU-XXX.md
   git commit -m "docs(HU-XXX): mark as lista [HU-XXX]"
   git push origin develop
   ```

2. Deletar rama local y remota:
   ```bash
   git branch -d feature/HU-XXX-slug-corto
   git push origin --delete feature/HU-XXX-slug-corto
   ```

3. **Validar**: La historia ahora está en `estado: lista` y el commit merge está en el log de develop.

---

## 4. Release (Producción)

### Paso 1: Preparar rama release

```bash
git checkout -b release/v1.0.0 develop
```

Actualizar versión en `package.json`, `CHANGELOG.md`, etc.:
```bash
git add package.json CHANGELOG.md
git commit -m "chore: bump version to v1.0.0"
git push origin release/v1.0.0
```

### Paso 2: QA + Pruebas finales

En rama release, hacer bugfixes menores si aplica:
```bash
git commit -m "fix: typo in validation message [release/v1.0.0]"
git push origin release/v1.0.0
```

### Paso 3: Merge a main

```bash
git checkout main
git pull origin main
git merge --no-ff release/v1.0.0 -m "Merge release v1.0.0 into main"
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin main --tags
```

### Paso 4: Merge de vuelta a develop

```bash
git checkout develop
git pull origin develop
git merge --no-ff release/v1.0.0 -m "Merge release v1.0.0 back to develop"
git push origin develop
```

### Paso 5: Cleanup

```bash
git branch -d release/v1.0.0
git push origin --delete release/v1.0.0
```

---

## 5. Hotfix (Bug crítico en producción)

### Paso 1: Crear rama desde main

```bash
git checkout -b hotfix/v1.0.1-auth-bypass main
```

### Paso 2: Fix + commit

```bash
git commit -m "fix(auth): disable vulnerable endpoint [hotfix]"
git push origin hotfix/v1.0.1-auth-bypass
```

### Paso 3: Merge a main + tag

```bash
git checkout main
git pull origin main
git merge --no-ff hotfix/v1.0.1-auth-bypass
git tag -a v1.0.1 -m "Hotfix v1.0.1: auth bypass"
git push origin main --tags
```

### Paso 4: Merge a develop (crítico, no saltar)

```bash
git checkout develop
git pull origin develop
git merge --no-ff hotfix/v1.0.1-auth-bypass
git push origin develop
```

### Paso 5: Cleanup

```bash
git branch -d hotfix/v1.0.1-auth-bypass
git push origin --delete hotfix/v1.0.1-auth-bypass
```

---

## 6. Commits + Mensajes

### Convención Conventional Commits

```
<type>(<scope>): <subject> [HU-XXX]

<body: optional, max 72 chars/line>

<footer: Closes #PR, Co-Authored-By, etc.>
```

### Ejemplos válidos

```
feat(HU-003): add document upload form [HU-003]

- Drag & drop zone
- File size validation (20MB max)
- Tipología selector (optional)

Closes #42
Co-Authored-By: Alice <alice@example.com>
```

```
fix(HU-010): fix null pointer in field extraction [HU-010]

Closes #41
```

```
docs(HU-020): update user roles matrix in backlog [HU-020]
```

### Tipos válidos

| Tipo | Ejemplo | Incremento semver |
|------|---------|---|
| `feat` | Implementar nueva HU | Minor (0.Y.0) |
| `fix` | Corregir bug | Patch (0.0.Z) |
| `refactor` | Limpieza, sin cambio funcional | Nada |
| `test` | Tests nuevos | Nada |
| `docs` | Documentación, Factory artefactos | Nada |
| `chore` | Deps, CI config, tooling | Nada |

---

## 7. Status de Historia ↔ Git

| Estado Historia | Rama | Acción Git |
|---|---|---|
| `pendiente` | — | Sin rama. Esperando inicio. |
| `en-desarrollo` | `feature/HU-XXX-*` activa | Commits diarios. Update HU frontmatter. |
| `en-revision` | PR abierto (HU-XXX → develop) | Code review. HU frontmatter `estado: en-revision`. |
| `lista` | Mergeado en develop | Commit merge creado. HU frontmatter `estado: lista`. |
| `en-produccion` | Mergeado en main | Release tag vX.Y.Z creado. HU frontmatter `estado: en-produccion` (opcional). |

---

## 8. Checklist antes de push

- ✅ Tests locales pasan: `npm test` (o equivalente).
- ✅ Linting OK: `npm run lint` (o equivalente).
- ✅ No hay secrets en diff (usar `git-secrets` si está configurado).
- ✅ Commits son atómicos (1 cambio = 1 commit).
- ✅ Mensaje de commit es claro y sigue convención.
- ✅ Historia en Factory está actualizada (estado, notas).
- ✅ Sin conflictos con `develop` (antes de abrir PR).

---

## 9. Troubleshooting

### Conflicto con develop

```bash
# En rama feature
git fetch origin
git rebase origin/develop
# Resolver conflictos manualmente
git add .
git rebase --continue
git push -f origin feature/HU-XXX-slug-corto
```

### Accidentalmente committeado a develop

```bash
# Antes de push
git reset --soft HEAD~1   # Deshace commit, preserva cambios
git stash                 # Guarda los cambios
git checkout -b feature/HU-XXX-slug-corto
git stash pop
git add .
git commit -m "feat(...) [HU-XXX]"
git push origin feature/HU-XXX-slug-corto
```

### Olvidé linkar a HU en commit

```bash
# Si no se ha pusheado
git commit --amend -m "feat(...) [HU-XXX]"

# Si ya se pusheó
git push -f origin feature/HU-XXX-slug-corto
# (luego avisar a revisor que rebase)
```

---

## 10. Sintaxis rápida (snippets)

```bash
# Crear feature
git checkout -b feature/HU-XXX-slug develop

# Commits atómicos
git add docs/04-historias/HU-XXX.md
git commit -m "docs(HU-XXX): mark as en-desarrollo"

git add prototipo/archivo.html
git commit -m "feat(HU-XXX): add UI component [HU-XXX]"

# Push
git push -u origin feature/HU-XXX-slug

# Merge (desde develop)
git merge --no-ff feature/HU-XXX-slug

# Tag release
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin main --tags
```

---

## Referencias

- [Git Flow (Vincent Driessen)](https://nvie.com/posts/a-successful-git-branching-model/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Factory Methodology](docs/00-referencia/gitflow-vincent.md)
