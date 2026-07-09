# ADR-013: Criterio de éxito del piloto vertical de embargos

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-015 (procesar un documento de embargo real end-to-end) dejaba ambiguo el criterio objetivo de "correcto" para el piloto vertical de embargos (EP-006). Alternativas preguntadas: verificación contra ground truth externo, ausencia de fallos técnicos + confianza LLM alta, o revisión manual caso por caso. El usuario eligió explícitamente la segunda.

## Decisión

Un documento de embargo procesado en el piloto se considera "correcto" cuando cumple ambas condiciones:
1. El pipeline se completó **sin fallos técnicos** (sin excepción, sin timeout, sin bloqueo).
2. La **confianza LLM reportada** está por encima del umbral de dominio configurado (ADR-010).

El piloto **no** usa verificación contra ground truth externo — es consistente con el modo fallback de ADR-011 (precisión sin golden dataset), ya que el dominio de embargos parte sin dataset etiquetado.

## Consecuencias

- El piloto es rápido de evaluar (no depende de construir un golden dataset de embargos antes de arrancar) pero su "éxito" mide completitud técnica + confianza del modelo, no exactitud verificada contra la realidad legal del documento.
- Si en el futuro se construye un golden dataset para embargos, el criterio de éxito del piloto puede endurecerse reutilizando ADR-011 en modo con ground truth — no es una decisión final e inmutable, es la línea base razonable para arrancar.
- HU-015 queda categorizada como "Could" en la priorización MoSCoW vigente — el piloto valida el diseño genérico, no es condición de existencia del MVP (EP-001 a EP-005).
