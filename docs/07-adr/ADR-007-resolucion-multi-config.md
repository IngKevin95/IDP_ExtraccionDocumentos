# ADR-007: Resolución de configuración de dominio ante ambigüedad multi-match

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-005 (resolver config de dominio activa y encolar documento) dejaba ambiguo qué ocurre cuando un documento entrante coincide con más de una configuración de dominio activa simultáneamente. Alternativas preguntadas: rechazar el documento y pedir desambiguación manual, prioridad explícita por config, o la config más reciente gana. El usuario aceptó la opción recomendada.

## Decisión

Cada configuración de dominio incluye un campo `priority` explícito. Ante múltiples configs candidatas para un mismo documento, gana la de mayor prioridad. No hay rechazo automático ni resolución implícita por fecha.

## Consecuencias

- La validación fail-fast de config (HU-002) debe advertir si dos configs activas tienen `priority` idéntica para el mismo criterio de match — condición de ambigüedad real, no resuelta por este ADR.
- El campo `priority` es responsabilidad explícita de quien administra la configuración de dominio, no un valor inferido por el sistema.
- La resolución queda determinística y auditable: dado un documento y el conjunto de configs activas en ese momento, el resultado es siempre el mismo.
