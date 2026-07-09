# ADR-011: Método de cálculo del KPI de precisión de extracción

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-011 (ver precisión de extracción por dominio) dejaba ambiguo el método de cálculo del KPI de precisión — uno de los 3 KPI norte del PRD. Alternativas preguntadas: comparación contra golden dataset etiquetado, uso de la confianza reportada por el LLM, o un tercer método. Ninguna fue elegida tal cual — el usuario dio una respuesta propia:

> "Una combinación de la primera y segunda; si no se tiene el golden dataset nos vamos con la segunda."

## Decisión

El cálculo de precisión por dominio combina dos señales cuando ambas están disponibles:
1. Comparación contra un **golden dataset etiquetado** (ground truth) para ese dominio.
2. **Confianza reportada por el LLM** en la extracción.

Si el dominio **no tiene** golden dataset etiquetado, el KPI de precisión se calcula únicamente a partir de la confianza del LLM (fallback), no se deja sin valor ni se bloquea el KPI.

## Consecuencias

- Un dominio recién creado (sin histórico etiquetado) tiene precisión visible desde el primer documento procesado, aunque con menor rigor (solo confianza LLM) hasta que exista golden dataset.
- El sistema debe distinguir y exponer, junto al valor de precisión, **qué método lo produjo** (con/sin golden dataset) — la cifra no es comparable entre dominios con métodos distintos sin esa marca.
- Construir y mantener golden datasets por dominio queda como actividad operativa fuera de alcance de este ADR, pero es la vía para subir el rigor del KPI en un dominio dado.
- Consistente con ADR-013 (criterio de éxito del piloto embargos), que explícitamente no usa ground truth externo para el piloto — el piloto opera bajo el modo fallback de este ADR.
