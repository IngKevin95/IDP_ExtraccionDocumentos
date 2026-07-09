# ADR-008: Manejo de fallos parciales de extracción LLM y trazabilidad de campos no resueltos

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-006 (extraer campos vía LLM según config de dominio) dejaba ambiguo qué ocurre cuando el LLM extrae exitosamente algunos campos configurados pero falla en otros dentro del mismo documento. Ninguna de las 3 opciones ofrecidas (bloquear el documento completo, ignorar el campo fallido sin marca, reintentar solo el campo fallido) fue elegida tal cual — el usuario dio una respuesta propia:

> "Se marca el nivel de fiabilidad del documento, y se mencionan los campos críticos que campo no logró extraer por cada documento, en forma de trazabilidad para que el usuario pueda complementarlo."

## Decisión

Un fallo parcial de extracción **no bloquea** el documento completo. El resultado se entrega siempre, acompañado de:
1. Un **nivel de fiabilidad del documento** (agregado, calculado a partir del éxito/fallo de extracción de sus campos).
2. La lista explícita de **campos críticos no extraídos**, por documento, como mecanismo de trazabilidad.

Esta trazabilidad existe para que el operador de dominio pueda complementar manualmente los campos faltantes — no es solo un log interno, es parte del contrato de resultado expuesto (HU-010).

## Consecuencias

- El esquema de resultado (HU-010) debe incluir siempre nivel de fiabilidad + lista de campos críticos no extraídos, incluso cuando la extracción fue 100% exitosa (lista vacía, fiabilidad máxima).
- "Campo crítico" es una propiedad de la configuración de dominio (HU-001), no un valor fijo del sistema — cada dominio decide qué campos son críticos para su tipología.
- No hay reintento automático de campo individual en este ADR — la resolución de campos faltantes es responsabilidad del operador de dominio vía complemento manual, no del pipeline automatizado.
