---
id: flow-004-configuracion-dominio
epica: EP-004
historias_cubiertas:
  - HU-001
  - HU-002
---

# EP-004 — Configuración de dominio (tipologías pluggable)

> Cadena de validación fail-fast con múltiples puntos de rechazo — `flowchart TD`.

```mermaid
flowchart TD
    A[Integrador sube YAML de tipología de dominio]
    %% HU-001
    A --> B{Sintaxis YAML válida?}
    %% HU-001
    B -- No --> B1[Rechazo: error de línea/motivo de parseo, no registra tipología]
    %% HU-001
    B -- Sí --> C{Campo obligatorio nombre de dominio presente?}
    %% HU-001
    C -- No --> C1[Rechazo: error identifica campo faltante, no registra tipología]
    %% HU-001
    C -- Sí --> D{Campos duplicados con reglas distintas?}
    %% HU-001
    D -- Sí, edge --> D1[Rechazo: error señala campo duplicado, no registra tipología]
    %% HU-002
    D -- No --> E{Regla de extracción referencia campo inexistente?}
    %% HU-002
    E -- Sí --> E1[Rechazo: error identifica regla y campo en conflicto, config inactiva]
    %% HU-002
    E -- No --> F{Error crítico de validación, ej. tipo de dato incompatible?}
    %% HU-002
    F -- Sí --> F1[Activación bloqueada, config anterior permanece vigente]
    %% HU-002
    F -- No --> G{Advertencia no bloqueante, ej. regla redundante?}
    %% HU-002
    G -- Sí, edge --> G1[Se activa igual, advertencia informada sin bloquear]
    %% HU-001
    G -- No --> H{Ya existe una tipología activa para ese dominio?}
    %% HU-001
    H -- Sí, edge --> H1[Reemplaza la definición anterior, nuevos documentos usan la actualizada]
    %% HU-001
    H -- No --> I[Tipología nueva registrada y activa]
    G1 --> Z[Config disponible para procesar documentos → EP-001/EP-002]
    H1 --> Z
    I --> Z
```

## Cobertura

- HU-001 (5/5 escenarios): definición válida happy, error de sintaxis YAML, error de campo obligatorio faltante, edge de redefinición de tipología existente, edge de campos duplicados.
- HU-002 (4/4 escenarios): validación y activación happy, error de regla inconsistente, error crítico que bloquea activación (config anterior vigente), edge de advertencia no bloqueante.

## Trazabilidad

| Paso | HU | AC |
|---|---|---|
| A→B | HU-001 | Esc. 1 (happy) |
| B→B1 | HU-001 | Esc. 2 (error, sintaxis YAML) |
| B→C | HU-001 | Esc. 1 (happy) |
| C→C1 | HU-001 | Esc. 3 (error, campo faltante) |
| C→D | HU-001 | Esc. 1 (happy) |
| D→D1 | HU-001 | Esc. 5 (edge, campos duplicados) |
| D→E | HU-002 | Esc. 1 (happy) |
| E→E1 | HU-002 | Esc. 2 (error, regla inconsistente) |
| E→F | HU-002 | Esc. 1 (happy) |
| F→F1 | HU-002 | Esc. 3 (error crítico, config anterior vigente) |
| F→G | HU-002 | Esc. 1 (happy) |
| G→G1 | HU-002 | Esc. 4 (edge, advertencia no bloqueante) |
| G→H | HU-001 | Esc. 1 (happy) |
| H→H1 | HU-001 | Esc. 4 (edge, redefinición) |
| H→I | HU-001 | Esc. 1 (happy, tipología nueva registrada) |
| G1→Z / H1→Z / I→Z | HU-001, HU-002 | Cierre de config disponible |

## Huecos detectados

Ninguno dentro del alcance de esta épica — las 2 historias de EP-004 quedan completamente referenciadas.

## Siguiente paso

`/factory:revisar` una vez estén los 6 flows escritos.
