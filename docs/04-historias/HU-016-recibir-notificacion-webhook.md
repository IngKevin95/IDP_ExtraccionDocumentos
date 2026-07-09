---
id: HU-016
titulo: Recibir notificación webhook al completar procesamiento
epica: EP-003
prioridad: Should
complejidad: M
estado: lista
---

# HU-016 — Recibir notificación webhook al completar procesamiento

> Formaliza la decisión de ADR "soportar ambos patrones de entrega desde el diseño" (publish/poll + push/webhook). Cierra el hueco de cobertura documentado en HU-009 y en el flow `docs/06-flows/EP-003-consulta-resultados.md`. Prioridad tentativa (MoSCoW, no confirmada con el usuario) — pendiente de ratificar en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** recibir una notificación webhook cuando un documento termina de procesarse (completo o error), **para** reaccionar en tiempo real sin tener que hacer polling periódico sobre el estado.

## Contexto

Capability "Consulta" (EP-003). Complementa HU-009/HU-010: el polling sigue siempre disponible como mecanismo de respaldo; el webhook es un mecanismo adicional, no un reemplazo.

Política de reintentos de entrega: **configurable por tenant/dominio** (número de intentos y backoff como parámetro de la configuración de dominio, no un valor global fijo) — ver [ADR-009](../07-adr/ADR-009-retry-webhook-configurable.md).

## INVEST (chequeo parcial)

- [x] **Valiosa**: evita polling activo, reduce latencia percibida por el Integrador.
- [x] **Pequeña**: complejidad M — requiere registro de URL destino y lógica de reintento, más que una simple lectura de estado.
- [x] **Verificable**: se puede observar el payload entregado y comparar contra el estado real del documento; AC formal en G/W/T abajo.
- [x] **Independiente**: depende solo de que el documento tenga un estado final (HU-009/HU-010 ya resuelven esa lectura) — no depende de negociación abierta con otras historias.
- [x] **Negociable**: alcance definido por AC, sin prescribir implementación (mecanismo de cola/entrega queda abierto).
- [x] **Estimable**: política de reintentos ya resuelta (ADR-009, configurable por tenant/dominio) — elimina la ambigüedad que impedía estimar el esfuerzo de esa lógica.

## Acceptance Criteria

### Escenario 1 — Notificar documento completo vía webhook (happy path)
**Given** el Integrador registró una URL de webhook al subir el documento (HU-003) y el documento termina su procesamiento con estado `completo`
**When** el sistema detecta la transición a estado `completo`
**Then** el sistema envía una notificación POST a la URL registrada con el resultado estructurado, equivalente al que devuelve HU-010

### Escenario 2 — Notificar documento en error vía webhook (happy path)
**Given** el Integrador registró una URL de webhook y el documento termina su procesamiento con estado `error`
**When** el sistema detecta la transición a estado `error`
**Then** el sistema envía una notificación POST a la URL registrada con el estado `error` y el motivo del fallo, equivalente al que devuelve HU-009

### Escenario 3 — Reintentar entrega cuando la URL de webhook no responde (error)
**Given** una notificación pendiente de entrega y la URL de webhook registrada no responde o devuelve error de servidor
**When** el sistema intenta la entrega
**Then** el sistema reintenta la entrega según la política de reintentos configurada para ese tenant/dominio (número de intentos y backoff, ver ADR-009), sin bloquear el pipeline de procesamiento de otros documentos

### Escenario 4 — Agotar reintentos de entrega sin afectar disponibilidad del resultado (edge)
**Given** una notificación que agotó todos sus reintentos de entrega configurados
**When** el sistema descarta el intento de notificación
**Then** el resultado del documento permanece disponible normalmente vía polling (HU-009/HU-010), sin pérdida de datos

### Escenario 5 — No registrar webhook y depender solo de polling (edge)
**Given** el Integrador no registró ninguna URL de webhook al subir el documento
**When** el documento termina su procesamiento (completo o error)
**Then** el sistema no intenta ninguna notificación push y el resultado queda disponible únicamente vía polling (HU-009/HU-010)

---
_Redactado para cerrar el gap detectado en HU-009 y en el flow de EP-003, tras decisión explícita del usuario en la fase de ADR: formalizar el patrón push/webhook con HU + AC antes de continuar. Ambigüedad de política de reintentos resuelta en [ADR-009](../07-adr/ADR-009-retry-webhook-configurable.md): configurable por tenant/dominio._
