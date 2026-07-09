# ADR-010: Umbral de confianza de clasificación configurable por dominio

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-007 (clasificar tipo de documento) dejaba ambiguo el umbral de confianza mínimo para aceptar una clasificación del LLM como válida. Alternativas preguntadas: umbral global fijo, umbral configurable por dominio, o sin umbral (siempre se acepta la clasificación de mayor score). El usuario eligió explícitamente la segunda, no la recomendada.

## Decisión

El umbral de confianza de clasificación es un parámetro de la configuración de dominio (mismo mecanismo que ADR-007/ADR-009), no un valor global fijo del sistema.

## Consecuencias

- Cada dominio decide su propia tolerancia a clasificaciones ambiguas — un dominio de alto riesgo (ej. embargos judiciales) puede exigir un umbral más alto que uno de bajo riesgo.
- Una clasificación por debajo del umbral configurado se trata como fallo de clasificación, activando el mismo mecanismo de trazabilidad de fallo parcial descrito en ADR-008 (nivel de fiabilidad + campo crítico no resuelto), no un rechazo silencioso.
- El criterio de éxito del piloto de embargos (ADR-013) queda consistente con este umbral por dominio, no con un valor genérico.
