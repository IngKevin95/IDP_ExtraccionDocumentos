# CLAUDE.md — docs/01-prd/

Reglas locales para esta carpeta. La fuente completa es `METODOLOGIA.md` §1; esto es el resumen operativo.

## Fuera de alcance aquí

Épicas → `docs/03-backlog/`. Historias de usuario → `docs/04-historias/`. Flows → `docs/06-flows/`.

## Nomenclatura

Un archivo por PRD vigente: `<slug>.md`, kebab-case sin acentos. Módulos independientes usan `prd-modulo-A.md`, `prd-modulo-B.md`.

## Chequeo obligatorio

Los **12 componentes obligatorios** (10 clásicos + Non-goals + KPIs) deben estar presentes. Se acepta variante One-Pager solo en borrador temprano; la entrega final exige los 12 completos.

## Automatización disponible

- Skill de escritura/edición: `factory-escribir-prd`.
- Revisión automática: agente `prd-reviewer`, invocable vía `/factory:revisar`.
