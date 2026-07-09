---
id: HU-010
titulo: Obtener resultado estructurado de un documento completo
epica: EP-003
prioridad: Must
complejidad: S
estado: lista
---

# HU-010 — Obtener resultado estructurado de un documento completo

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Operador de dominio, **quiero** obtener el resultado estructurado de un documento una vez está completo, **para** usar los datos extraídos directamente en mi proceso downstream sin reprocesar el documento manualmente.

## Contexto

Capability "Consulta" (EP-003). Complementa HU-009: primero se consulta el estado, luego (si `completo`) se obtiene el resultado.

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado textualmente en el PRD.
- [x] **Pequeña**: complejidad S, lectura de resultado ya persistido por EP-002.
- [x] **Verificable**: resultado devuelto coincide con lo extraído/clasificado; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo (depende de HU-009).
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Obtener resultado de documento completo (happy path)
**Given** un documento cuyo estado (HU-009) es `completo`
**When** el Operador de dominio solicita el resultado estructurado vía API
**Then** el sistema responde con los datos extraídos y clasificados en formato estructurado, listos para consumo downstream

### Escenario 2 — Rechazar consulta de resultado sobre documento aún pendiente (error)
**Given** un documento cuyo estado es `pendiente`
**When** el Operador de dominio solicita el resultado estructurado vía API
**Then** el sistema responde con un error que indica que el documento aún no ha terminado de procesarse, sin devolver datos parciales

### Escenario 3 — Rechazar consulta de resultado sobre documento en estado error (error)
**Given** un documento cuyo estado es `error`
**When** el Operador de dominio solicita el resultado estructurado vía API
**Then** el sistema responde con un error que indica que el documento no tiene resultado disponible y referencia el motivo del fallo de procesamiento

### Escenario 4 — Consultar resultado de un documento inexistente (edge)
**Given** un identificador de documento que no existe en la plataforma
**When** el Operador de dominio solicita el resultado estructurado vía API
**Then** el sistema responde con un error 404 indicando que el documento no existe

### Escenario 5 — Consultar resultado de un documento de otro tenant (edge)
**Given** un documento completo que pertenece a un tenant distinto al del solicitante
**When** el Operador de dominio solicita el resultado estructurado vía API
**Then** el sistema responde con un error de acceso (403 o 404, sin revelar que el documento existe), respetando el aislamiento total entre tenants

---
_Redactado vía `/factory:ac HU-010`. Sin ambigüedades relevantes detectadas — la historia se apoya directamente en los estados ya definidos en HU-009._
