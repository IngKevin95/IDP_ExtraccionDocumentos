# CLAUDE.md — docs/03-backlog/

Reglas locales para épicas y backlog. Detalle completo en `METODOLOGIA.md` §2 y §6.

## Fuera de alcance aquí

Las historias individuales viven en `docs/04-historias/` — aquí solo se consolidan referencias a ellas.

## Los dos artefactos

- `epicas.md`: descomposición del PRD en épicas (`EP-XXX`), con trazabilidad bidireccional a los objetivos del PRD.
- `backlog.md`: tabla consolidada de todas las historias. El orden de filas **es** la priorización vigente.

## Reglas locales clave

1. IDs `EP-XXX` únicos, 3 dígitos, sin saltos ni reutilización.
2. Trazabilidad bidireccional obligatoria: épica → ≥1 objetivo del PRD, y objetivo → ≥1 épica.
3. Este directorio no prioriza — la priorización se decide en `docs/05-priorizacion/` y aquí solo se refleja el resultado.

## Automatización disponible

- Skill `factory-descomponer-prd-a-epicas` → escribe `epicas.md`.
- Skill `factory-construir-backlog` → escribe `backlog.md`.
- Agentes revisores: `story-decomposer-auditor`, `trazabilidad-auditor`.
