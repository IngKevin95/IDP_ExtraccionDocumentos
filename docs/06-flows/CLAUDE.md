# CLAUDE.md — docs/06-flows/

Reglas locales para flujos de navegación. Detalle completo en `METODOLOGIA.md` §5a.

## Fuera de alcance aquí

Wireframes, imágenes o ASCII art no son válidos aquí — Mermaid es el único contrato aceptado.

## Nomenclatura

Un archivo por épica: `EP-XXX-<slug>.md`. La granularidad hereda directamente del User Story Map.

## Reglas de rechazo (rejection rules)

1. Frontmatter YAML obligatorio: `id, epica, historias_cubiertas`.
2. Cada arco del diagrama debe mapear 1:1 a un AC existente, marcado con `%% HU-XXX` inmediatamente antes del arco. Sin esa marca, el arco se considera inventado y se rechaza.
3. Cobertura mínima por flow: camino feliz + ≥1 ramal de error + ≥1 edge case.
4. Cobertura por épica: toda HU con `epica: EP-XXX` debe aparecer referenciada en el flow correspondiente.
5. Actores válidos: únicamente los declarados en el PRD §Stakeholders.
6. El Mermaid debe parsear sin errores (`sequenceDiagram` o `flowchart TD`, sin mezclar ambos en un mismo archivo).

## Automatización disponible

- Skill `factory-mapear-flujos-navegacion` (opcional, post-pipeline base).
- Agente revisor: `flows-auditor`, se dispara automáticamente si `FACTORY_AUTO_AUDIT=true`.
