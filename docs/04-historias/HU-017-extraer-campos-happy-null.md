---
id: HU-017
titulo: Extraer campos vía LLM según config de dominio (happy path + reintentos)
epica: EP-002
prioridad: Must
complejidad: M
estado: draft
---

# HU-017 — Extraer campos vía LLM según config de dominio (happy path + reintentos)

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`. Sustituye a HU-006 (dividida por complejidad L / criterio Pequeña de INVEST).

**Como** Integrador, **quiero** que el sistema extraiga los campos definidos en mi configuración de dominio vía LLM, **para** obtener datos estructurados fieles a mi tipología sin escribir lógica de extracción propia.

## Contexto

Capability "Procesamiento" (EP-002), núcleo de inteligencia de la plataforma — generalizado (config-driven) frente al hardcoded a embargos de DocFly. Cubre extracción exitosa, ausencia de evidencia (`null`) y agotamiento de reintentos. La validación de tipo de dato de cada campo extraído se resuelve en HU-018.

**Política de reintentos (resuelta)**: 3 intentos con backoff exponencial (1s, 2s, 4s) ante timeout o error del proveedor LLM. Agotados los 3 intentos, el documento pasa a estado `error`.

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado textualmente en el PRD.
- [x] **Pequeña**: complejidad M, extracción + reintentos sin lógica de validación de tipos (delegada a HU-018).
- [x] **Verificable**: resultado estructurado persiste con los campos de la config; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures que simulan documento encolado + config de dominio; dependencia de HU-005 es orden de pipeline en runtime, no bloqueo de desarrollo.
- [x] **Negociable**: política de reintentos documentada arriba, ajustable en refinamiento.
- [x] **Estimable**: política de reintentos ya definida arriba.

## Acceptance Criteria

### Escenario 1 — Extraer todos los campos definidos en la config (happy path)
**Given** un documento encolado (HU-005) con una configuración de dominio activa que define un conjunto de campos a extraer
**When** el sistema ejecuta la extracción vía LLM sobre el documento
**Then** el resultado estructurado persiste con todos los campos definidos en la config, cada uno con su valor extraído o `null` si no se encontró evidencia en el documento

### Escenario 2 — Fallo del LLM tras reintentos agota la extracción (error)
**Given** un documento encolado cuya extracción vía LLM falla repetidamente (timeout o error del proveedor)
**When** el sistema agota los 3 intentos con backoff exponencial (1s, 2s, 4s)
**Then** el documento queda en estado `error` (consultable vía HU-009) con el motivo del fallo, sin producir un resultado parcial marcado como completo

### Escenario 3 — Campo definido en la config sin evidencia en el documento (edge)
**Given** un documento cuya config de dominio define un campo que no tiene correspondencia en el contenido del documento
**When** el sistema ejecuta la extracción vía LLM
**Then** el resultado estructurado incluye el campo con valor `null` (o equivalente explícito de "no encontrado"), sin bloquear la extracción de los demás campos

---
_Redactado vía `/factory:ac HU-017`. Ambigüedad resuelta (ver Contexto): política de reintentos = 3 intentos, backoff 1s/2s/4s._
