---
id: flow-001-ingesta-validacion-documentos
epica: EP-001
historias_cubiertas:
  - HU-003
  - HU-004
  - HU-005
---

# EP-001 — Ingesta y validación de documentos

> Actor: Integrador. Flujo de decisión (validación fail-fast + resolución de config + encolado), por eso se modela como `flowchart TD` en vez de `sequenceDiagram`.

```mermaid
flowchart TD
    A[Integrador sube documento vía API]
    %% HU-003
    A --> B{Credenciales válidas?}
    %% HU-003
    B -- No --> B1[401 Unauthorized, documento no registrado]
    %% HU-003
    B -- Sí --> C{Formato soportado por la plataforma?}
    %% HU-003
    C -- No --> C1[Error: formatos soportados indicados, no registra documento]
    %% HU-004
    C -- Sí --> D{Documento corrupto o vacío 0 bytes?}
    %% HU-004
    D -- Sí --> D1[Rechazo fail-fast: error claro, no encola]
    %% HU-004
    D -- No --> E{Tamaño vs máximo permitido}
    %% HU-004
    E -- Excede --> D1
    %% HU-003
    E -- Igual al máximo, edge --> F
    %% HU-004
    E -- Menor al máximo --> F{Extensión coincide con contenido real?}
    %% HU-004
    F -- No coincide, edge --> F1[Rechazo: extensión no coincide con contenido real]
    %% HU-003
    F -- Sí --> G[202 Aceptado, ID único asignado]
    %% HU-005
    G --> I{Config de dominio activa existe?}
    %% HU-005
    I -- No --> I1[Error: no hay config de dominio activa, no encola]
    %% HU-005
    I -- Sí, dominio único --> K[Resuelve config única del dominio]
    %% HU-005
    I -- Sí, múltiples dominios, edge --> J1[Resuelve config del dominio correspondiente al documento]
    %% HU-005
    K --> L{Cola de procesamiento disponible?}
    %% HU-005
    J1 --> L
    %% HU-005
    L -- No --> L1[Fallo registrado, reintento o estado recuperable, documento no se pierde]
    %% HU-005
    L -- Sí --> M[Documento encolado con config resuelta → EP-002]
```

## Cobertura

- HU-003 (4/4 escenarios): happy (auth+formato ok → 202), error sin auth, error formato no soportado, edge tamaño exacto en el límite.
- HU-004 (4/4 escenarios): rechazo por corrupto, vacío, tamaño excedido, y edge de extensión que no coincide con el contenido real.
- HU-005 (4/4 escenarios): resolución de config + encolado happy, error sin config activa, error de cola no disponible (recuperable), edge de múltiples dominios activos.

## Trazabilidad

| Paso | HU | AC |
|---|---|---|
| A→B | HU-003 | Esc. 2 (error, sin auth) |
| B→B1 | HU-003 | Esc. 2 (error, sin auth) |
| B→C | HU-003 | Esc. 1 (happy) |
| C→C1 | HU-003 | Esc. 3 (error, formato no soportado) |
| C→D | HU-004 | Esc. 1 (happy) |
| D→D1 | HU-004 | Esc. 1 (error, corrupto/vacío) |
| D→E | HU-004 | Esc. 2 (error, tamaño) |
| E→D1 | HU-004 | Esc. 2 (error, tamaño excedido) |
| E→F (edge tamaño exacto) | HU-003 | Esc. 4 (edge, tamaño exacto en el límite) |
| E→F | HU-004 | Esc. 3 (happy, tamaño dentro del límite) |
| F→F1 | HU-004 | Esc. 4 (edge, extensión no coincide) |
| F→G | HU-003 | Esc. 1 (happy, 202 Aceptado) |
| G→I | HU-005 | Esc. 1 (happy) |
| I→I1 | HU-005 | Esc. 2 (error, sin config activa) |
| I→K | HU-005 | Esc. 1 (happy, dominio único) |
| I→J1 | HU-005 | Esc. 4 (edge, múltiples dominios) |
| K→L / J1→L | HU-005 | Esc. 1 (happy) |
| L→L1 | HU-005 | Esc. 3 (error, cola no disponible) |
| L→M | HU-005 | Esc. 1 (happy, documento encolado) |

## Huecos detectados

Ninguno dentro del alcance de esta épica — las 3 historias de EP-001 quedan completamente referenciadas.

## Siguiente paso

`/factory:revisar` una vez estén los 6 flows escritos, para disparar `flows-auditor` y `trazabilidad-auditor`.
