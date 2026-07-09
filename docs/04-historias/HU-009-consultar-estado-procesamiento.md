---
id: HU-009
titulo: Consultar estado de procesamiento de un documento
epica: EP-003
prioridad: Must
complejidad: S
estado: draft
---

# HU-009 — Consultar estado de procesamiento de un documento

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.
> Nota de alcance: cubre el patrón publish/poll (PRD Fase 5). El patrón push/webhook complementario está formalizado en [[HU-016]].

**Como** Operador de dominio, **quiero** consultar el estado de procesamiento de un documento (pendiente/completo/error), **para** integrar el resultado en mi flujo de trabajo sin acoplarme al tiempo de procesamiento.

## Contexto

Capability "Consulta" (EP-003). Patrón publish/poll — ver PRD, sección "Historias de usuario (resumen)".

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado textualmente en el PRD.
- [x] **Pequeña**: complejidad S, lectura de estado ya persistido por EP-002.
- [x] **Verificable**: estado devuelto coincide con el estado real del documento; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo.
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Consultar estado de documento pendiente (happy path)
**Given** un documento fue encolado (HU-005) y aún no terminó de procesarse
**When** el Operador de dominio consulta su estado vía API
**Then** el sistema responde con estado `pendiente`

### Escenario 2 — Consultar estado de documento completo (happy path)
**Given** un documento fue procesado exitosamente por EP-002
**When** el Operador de dominio consulta su estado vía API
**Then** el sistema responde con estado `completo`

### Escenario 3 — Consultar estado de documento que falló en el procesamiento (error)
**Given** un documento cuyo procesamiento terminó en error (ej. fallo del LLM tras reintentos)
**When** el Operador de dominio consulta su estado vía API
**Then** el sistema responde con estado `error` y una descripción del motivo del fallo

### Escenario 4 — Consultar estado de un documento inexistente (error)
**Given** un identificador de documento que no existe en la plataforma
**When** el Operador de dominio consulta ese identificador vía API
**Then** el sistema responde con un error 404 indicando que el documento no existe

### Escenario 5 — Consultar estado de un documento que pertenece a otro tenant (edge)
**Given** un documento válido que pertenece a un tenant distinto al del solicitante
**When** el Operador de dominio consulta ese identificador vía API
**Then** el sistema responde con un error de acceso (403 o 404, sin revelar que el documento existe), respetando el aislamiento total entre tenants

---
_Redactado vía `/factory:ac HU-009`. Sin ambigüedades relevantes detectadas — el Escenario 5 aplica directamente la restricción de aislamiento por tenant ya confirmada por el usuario en la fase de PRD._
