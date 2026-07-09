---
id: flow-002-procesamiento-llm-configurable
epica: EP-002
historias_cubiertas:
  - HU-017
  - HU-018
  - HU-007
  - HU-008
---

# EP-002 — Procesamiento LLM configurable

> Sin actor humano directo (procesamiento asíncrono disparado por EP-001) — cadena de decisiones internas, por eso `flowchart TD`.

```mermaid
flowchart TD
    A[Documento encolado con config resuelta]
    %% HU-007
    A --> B{Config tiene una sola tipología definida?}
    %% HU-007
    B -- Sí, edge --> C[Clasifica directamente con esa tipología]
    %% HU-007
    B -- No, múltiples tipologías --> D{Evidencia encaja en alguna tipología conocida?}
    %% HU-007
    D -- No --> D1[Estado error: ninguna tipología coincide]
    %% HU-007
    D -- Sí, una clara --> C
    %% HU-007
    D -- Ambigua entre dos tipologías, edge --> D2[Asigna tipología de mayor confianza, registra ambigüedad]
    %% HU-017
    C --> E[Extrae campos definidos vía LLM]
    %% HU-017
    D2 --> E
    %% HU-017
    E --> F{LLM responde exitosamente?}
    %% HU-017
    F -- Falla tras reintentos agotados --> F1[Estado error: extracción irrecuperable]
    %% HU-018
    F -- Sí --> G{Cada campo tiene evidencia y tipo compatible?}
    %% HU-017
    G -- Campo sin evidencia --> G1[Campo queda null, continúa con los demás]
    %% HU-018
    G -- Campo con tipo incompatible --> G2[Campo marcado inválido, no bloquea los demás]
    %% HU-018
    G -- Todos los campos válidos --> H[Todos los campos extraídos correctamente]
    %% HU-008
    G1 --> I{Documento contiene tablas?}
    %% HU-008
    G2 --> I
    %% HU-008
    H --> I
    %% HU-008
    I -- No --> I1[Resultado sin sección de tablas, no bloquea]
    %% HU-008
    I -- Sí, una tabla --> J{Estructura de la tabla es legible?}
    %% HU-008
    J -- No, corrupta --> J1[Tabla marcada no extraíble con motivo, no bloquea el resto]
    %% HU-008
    J -- Sí --> K[Tabla persistida estructurada]
    %% HU-008
    I -- Sí, múltiples tablas, edge --> K1[Cada tabla persistida independiente, sin mezclar filas]
    K --> L[Resultado estructurado listo → EP-003]
    K1 --> L
    I1 --> L
    F1 --> L
```

## Cobertura

- HU-007 (4/4 escenarios): clasificación happy, error sin tipología que encaje, edge de config con una sola tipología, edge de evidencia ambigua entre dos tipologías.
- HU-017 (3/3 escenarios): extracción happy de todos los campos, error de fallo LLM irrecuperable tras reintentos, edge de campo sin evidencia (null).
- HU-018 (3/3 escenarios): campo válido happy, error de campo con tipo incompatible (inválido, no bloqueante), edge de campo `null` excluido de la validación de tipo (cubierto en la rama de HU-017).
- HU-008 (4/4 escenarios): extracción de tabla happy, ausencia de tablas (caso feliz sin sección), error de tabla con estructura irregular, edge de múltiples tablas independientes.

## Trazabilidad

| Paso | HU | AC |
|---|---|---|
| A→B / B→C / B→D / D→D1 / D→C / D→D2 | HU-007 | Esc. 1, 2, 3, 4 |
| C→E / D2→E | HU-017 | Esc. 1 (happy) |
| E→F | HU-017 | Esc. 1 (happy) |
| F→F1 | HU-017 | Esc. 2 (error, reintentos agotados) |
| F→G | HU-018 | Esc. 1 (happy) |
| G→G1 | HU-017 | Esc. 3 (edge, campo sin evidencia) |
| G→G2 | HU-018 | Esc. 2 (error, tipo incompatible) |
| G→H | HU-018 | Esc. 1 (happy, todos válidos) |
| G1→I / G2→I / H→I | HU-008 | Esc. 1, 2, 3 |
| I→I1 | HU-008 | Esc. 2 (sin tablas) |
| I→J / J→J1 / J→K | HU-008 | Esc. 1, 3 |
| I→K1 | HU-008 | Esc. 4 (edge, múltiples tablas) |

## Huecos detectados

Ninguno dentro del alcance de esta épica — las 4 historias de EP-002 (HU-017, HU-018, HU-007, HU-008) quedan completamente referenciadas. Ambigüedades ya documentadas en las HU (política de reintentos LLM, umbral de confianza de clasificación) siguen pendientes del ADR pausado — no bloquean el flow, solo el detalle de implementación.

## Siguiente paso

`/factory:revisar` una vez estén los 6 flows escritos.
