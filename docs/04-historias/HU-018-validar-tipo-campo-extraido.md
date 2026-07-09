---
id: HU-018
titulo: Validar tipo de dato de campos extraídos por el LLM
epica: EP-002
prioridad: Must
complejidad: S
estado: draft
---

# HU-018 — Validar tipo de dato de campos extraídos por el LLM

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`. Sustituye a HU-006 (dividida por complejidad L / criterio Pequeña de INVEST).

**Como** Integrador, **quiero** que el sistema valide el tipo de dato de cada campo extraído vía LLM, **para** confiar en que los datos estructurados respetan la tipología definida en mi configuración sin revisarlos manualmente.

## Contexto

Capability "Procesamiento" (EP-002). Corre inmediatamente después de la extracción (HU-017), sobre el resultado ya persistido, marcando campos individuales sin bloquear el documento completo.

**Comportamiento de bloqueo (resuelto)**: un campo con valor no convertible a su tipo se marca como inválido a nivel de campo; el documento no se bloquea ni pasa a `error` por esta causa — solo el campo queda señalado para revisión.

## INVEST (chequeo parcial)

- [x] **Valiosa**: evita que datos mal tipados lleguen sin señalización al consumidor de la API.
- [x] **Pequeña**: complejidad S, validación de tipo sobre un resultado ya extraído.
- [x] **Verificable**: campo inválido queda marcado en el resultado estructurado; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures que simulan resultado extraído con campos de distintos tipos; dependencia de HU-017 es orden de pipeline en runtime, no bloqueo de desarrollo.
- [x] **Negociable**: comportamiento de bloqueo documentado arriba, ajustable en refinamiento.
- [x] **Estimable**: comportamiento de bloqueo ya definido arriba.

## Acceptance Criteria

### Escenario 1 — Campo extraído cumple el tipo de dato esperado (happy path)
**Given** un resultado estructurado con un campo cuyo valor extraído es convertible al tipo de dato definido en la config (ej. fecha, número)
**When** el sistema ejecuta la validación de tipo
**Then** el campo se marca como válido y no requiere revisión adicional

### Escenario 2 — LLM devuelve un valor que no cumple el tipo de dato del campo (error de campo)
**Given** un campo definido en la config con un tipo de dato específico (ej. fecha, número)
**When** el LLM extrae un valor que no es convertible a ese tipo
**Then** el sistema marca ese campo como inválido en el resultado (sin descartar los demás campos válidos) y registra la inconsistencia para revisión, sin bloquear el estado global del documento

### Escenario 3 — Campo con valor `null` no se evalúa por tipo (edge)
**Given** un campo cuyo valor extraído es `null` porque no se encontró evidencia en el documento (HU-017, Escenario 3)
**When** el sistema ejecuta la validación de tipo
**Then** el campo se excluye de la validación de tipo (un `null` no es un valor mal tipado) y conserva su estado de "no encontrado"

---
_Redactado vía `/factory:ac HU-018`. Ambigüedad resuelta (ver Contexto): campo inválido no bloquea estado global del documento._
