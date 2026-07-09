# CLAUDE.md — docs/04-historias/

Reglas locales para historias de usuario y sus AC. Detalle completo en `METODOLOGIA.md` §3 y §4.

## Fuera de alcance aquí

Épicas → `docs/03-backlog/`. Sesiones de priorización → `docs/05-priorizacion/`. Flows → `docs/06-flows/`.

## Nomenclatura

`HU-XXX-<slug>.md`, 3 dígitos, kebab-case sin acentos.

## Reglas no negociables

1. Frontmatter YAML completo (`id, titulo, epica, prioridad, complejidad, estado`); estado inicial siempre `draft`.
2. Formato canónico: "Como [rol específico], quiero [acción concreta], para [beneficio externo y visible]". Un rol genérico como "usuario" se rechaza en revisión.
3. AC en Given/When/Then, 3-5 escenarios (happy + error + edge). Given describe estado, When es una única acción, Then es un resultado observable.
4. INVEST debe pasar antes de marcar `estado: lista`.

## Automatización disponible

- `factory-escribir-historia-usuario` — estructura Como/Quiero/Para.
- `factory-escribir-criterios-aceptacion-bdd` — AC en G/W/T.
- `factory-validar-invest` — valida los 6 criterios INVEST.
- Agentes revisores: `invest-validator`, `bdd-validator`, `trazabilidad-auditor`.
