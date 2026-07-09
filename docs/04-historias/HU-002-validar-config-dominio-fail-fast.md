---
id: HU-002
titulo: Validar config de dominio antes de activarla (fail-fast)
epica: EP-004
prioridad: Must
complejidad: S
estado: lista
---

# HU-002 — Validar config de dominio antes de activarla (fail-fast)

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** que mi configuración de dominio se valide antes de activarse, **para** detectar errores de configuración antes de que afecten documentos reales.

## Contexto

Capability "Configuración de dominio" (EP-004). Depende de HU-001 (esquema de config ya definido). Patrón fail-fast heredado del pipeline de referencia (validación temprana evita gastar cómputo LLM en config inválida).

## INVEST (chequeo parcial)

- [x] **Valiosa**: evita incidentes en producción por config mal formada.
- [x] **Pequeña**: complejidad S, validación de esquema sobre HU-001.
- [x] **Verificable**: config inválida rechazada con error antes de activarse; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo (acoplada a HU-001).
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Validar y activar config correcta (happy path)
**Given** el Integrador sube una configuración de dominio que cumple el esquema definido en HU-001
**When** el sistema ejecuta la validación de la configuración
**Then** la configuración pasa a estado activo y queda disponible para procesar documentos

### Escenario 2 — Rechazar config con regla de extracción inconsistente (error)
**Given** el Integrador sube una configuración cuyo esquema es válido pero declara una regla de extracción que referencia un campo inexistente
**When** el sistema ejecuta la validación de la configuración
**Then** la configuración se rechaza, permanece inactiva y se devuelve un error que identifica la regla y el campo en conflicto

### Escenario 3 — Bloquear activación de config con error crítico (error)
**Given** una configuración de dominio con un error crítico de validación (ej. tipo de dato incompatible en un campo)
**When** el sistema intenta activarla
**Then** la activación se bloquea, la configuración anterior (si existe) permanece vigente, y no se pierde disponibilidad del dominio

### Escenario 4 — Validar config sin errores pero con advertencias no bloqueantes (edge)
**Given** una configuración de dominio válida que incluye una regla redundante (no rompe el esquema, pero es innecesaria)
**When** el sistema ejecuta la validación
**Then** la configuración se activa igualmente y el sistema informa la advertencia sin bloquear el proceso

---
_Redactado vía `/factory:ac HU-002`. Sin ambigüedades relevantes detectadas — historia acoplada directamente al esquema definido en HU-001._
