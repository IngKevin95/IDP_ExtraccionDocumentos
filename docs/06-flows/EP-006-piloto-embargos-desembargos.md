---
id: flow-006-piloto-embargos-desembargos
epica: EP-006
historias_cubiertas:
  - HU-014
  - HU-015
---

# EP-006 — Piloto vertical: embargos y desembargos

> Orquesta capacidades ya existentes de EP-001 a EP-004 en secuencia — `sequenceDiagram`.

```mermaid
sequenceDiagram
    actor OD as Operador de dominio (analista de embargos)
    participant Config as Config de dominio (EP-004)
    participant Core as Core genérico (EP-001/EP-002/EP-003)

    OD->>Config: Carga YAML tipologías EC/EJ/DC/DJ
    %% HU-014
    alt config válida
        Config-->>OD: config activa como dominio "embargos"
    %% HU-014
    else regla inconsistente (campo inexistente)
        Config-->>OD: rechazo fail-fast, identifica regla y campo
    %% HU-014
    else redefinir config ya activa, edge
        Config-->>OD: nueva versión reemplaza la anterior
    end

    OD->>Core: Sube documento de embargo real
    %% HU-015
    alt documento corrupto o excede tamaño (fail-fast HU-004)
        Core-->>OD: rechazo, no inicia pipeline
    %% HU-015
    else LLM falla de forma irrecuperable
        Core-->>OD: documento en estado error, motivo trazable
    %% HU-015
    else procesamiento completo exitoso
        Core-->>OD: resultado estructurado (tipología + campos + tablas)
        OD->>OD: compara resultado contra conocimiento del caso real
        alt resultado coincide con lo esperado
            OD->>OD: confirma core genérico produce resultado utilizable
        %% HU-015
        else discrepancia detectada, edge
            OD->>OD: documenta hallazgo del piloto, insumo para ajustar config (HU-014)
        %% HU-015
        end
    end
```

## Cobertura

- HU-014 (3/3 escenarios): carga válida happy, error de regla inconsistente rechazada fail-fast, edge de redefinición de config ya activa.
- HU-015 (4/4 escenarios): procesamiento e2e happy, error de documento rechazado por inválido, error de fallo LLM irrecuperable, edge de discrepancia entre resultado y conocimiento del analista (hallazgo del piloto, no fallo técnico).

## Trazabilidad

| Paso | HU | AC |
|---|---|---|
| Carga config → válida | HU-014 | Esc. 1 (happy) |
| Carga config → regla inconsistente | HU-014 | Esc. 2 |
| Carga config → redefinir, edge | HU-014 | Esc. 3 (edge) |
| Sube documento → corrupto/excede tamaño | HU-015 | Esc. 2 |
| Sube documento → LLM falla | HU-015 | Esc. 3 |
| Sube documento → completo exitoso | HU-015 | Esc. 1 (happy) |
| Compara → coincide | HU-015 | Esc. 1 (happy) |
| Compara → discrepancia, edge | HU-015 | Esc. 4 (edge) |

## Huecos detectados

Ninguno dentro del alcance de esta épica — las 2 historias de EP-006 quedan completamente referenciadas. Ambigüedades ya documentadas en HU-015 (criterio objetivo de "correcto", tamaño de muestra del piloto) siguen pendientes del ADR pausado.

## Siguiente paso

`/factory:revisar` una vez estén los 6 flows escritos — es el último de los 6, cierra la tarea #10 del pipeline funcional.
