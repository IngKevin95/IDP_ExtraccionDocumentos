# CLAUDE.md — docs/02-user-story-map/

Reglas locales para el Story Map. Detalle completo en `METODOLOGIA.md` §5.

## Fuera de alcance aquí

El detalle de cada historia (con sus AC) vive en `docs/04-historias/`, no en el mapa.

## Nomenclatura y formato

Un archivo, mismo slug que el PRD asociado: `<slug>.md`. Formato tabla Markdown por defecto — se prefiere sobre Mermaid porque es más portable para este tipo de artefacto.

## Reglas locales clave

1. El backbone debe cubrir el journey completo del usuario primario, sin saltos.
2. Debe existir una línea de MVP explícita — sin ella el mapa no aporta valor de priorización.
3. Las historias se referencian por ID (`HU-XXX`); si la historia todavía no existe, se reserva el ID igualmente.

## Automatización disponible

- Skill: `factory-crear-mapa-historias` (requiere épicas ya escritas).
- Revisión automática: agente `mapping-coherence-auditor`.
