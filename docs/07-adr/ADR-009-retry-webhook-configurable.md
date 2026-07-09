# ADR-009: Política de reintentos de entrega de webhook configurable por tenant/dominio

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-016 (recibir notificación webhook al completar procesamiento) dejaba ambigua la política de reintentos ante fallo de entrega (endpoint del integrador caído, timeout, error 5xx). El agente `invest-validator` marcó esta ambigüedad como motivo de fallo en la dimensión "Estimable" de INVEST. Alternativas preguntadas: política fija global, sin reintento (entrega best-effort única), o configurable por tenant/dominio. El usuario eligió explícitamente la tercera, no la recomendada.

## Decisión

El número de reintentos y la estrategia de backoff ante fallo de entrega de webhook son un **parámetro de configuración por tenant/dominio**, no un valor global fijo del sistema.

## Consecuencias

- La configuración de dominio (ADR-007, HU-001/HU-002) se extiende para incluir política de retry de webhook (número de intentos, backoff) como parte de su esquema.
- El resultado sigue disponible siempre vía polling (ADR-004) aunque todos los reintentos de webhook fallen — el webhook nunca es el único canal de entrega.
- Esta decisión desbloquea a HU-016 para pasar la validación INVEST en la dimensión Estimable, previamente fallida por esta ambigüedad.
