# ADR-004: Patrón de entrega de resultados dual (publish/poll + push/webhook)

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

Instrucción explícita del usuario sobre el patrón de entrega de resultados: **"Soportar ambos desde el diseño"**. HU-009/HU-010 cubrían consulta por polling; el flow de EP-003 y una revisión posterior detectaron que integradores con necesidad de baja latencia requieren notificación push. Se creó HU-016 para cerrar ese gap.

## Decisión

La capa de Consulta (EP-003, ADR-001) expone ambos patrones simultáneamente, no como alternativa mutuamente excluyente:
- **Pull**: `GET` de estado y resultado (HU-009, HU-010) — siempre disponible, es el mecanismo base.
- **Push**: registro de webhook por tenant/dominio, notificado al completar el procesamiento (HU-016) — opt-in, complementario.

Un integrador puede usar solo polling, solo webhook, o ambos — el sistema no obliga a elegir uno.

## Consecuencias

- El resultado del procesamiento se persiste igual en ambos casos — el webhook es una notificación de que el resultado ya está disponible vía polling, no un canal de entrega alternativo del payload completo.
- La capa de Consulta debe mantener registro de configuración de webhook por tenant/dominio y estado de intentos de entrega (ver ADR-009).
- HU-016 queda categorizada como "Should" (no "Must") en la priorización MoSCoW vigente — el polling por sí solo satisface el MVP.
