---
id: HU-014
titulo: Cargar configuración de tipologías de embargos (piloto)
epica: EP-006
prioridad: Could
complejidad: M
estado: draft
---

# HU-014 — Cargar configuración de tipologías de embargos (piloto)

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`. Columna post-MVP "Validar piloto embargos" del mapa.

**Como** Operador de dominio (analista de embargos), **quiero** cargar la configuración de tipologías EC/EJ/DC/DJ, **para** validar el pipeline genérico con un caso real sin escribir código específico de dominio.

## Contexto

Capability piloto vertical embargos (EP-006). Usa el mecanismo genérico de EP-004 (HU-001/HU-002) para demostrar que el core config-driven soporta un dominio real sin hardcodear reglas.

## INVEST (chequeo parcial)

- [x] **Valiosa**: demuestra que el core genérico funciona para un caso real sin código a medida.
- [x] **Pequeña**: complejidad M, reutiliza el mecanismo de configuración de EP-004.
- [x] **Verificable**: config de embargos activa y validada; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo (depende de HU-001/HU-002).
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Cargar config de tipologías EC/EJ/DC/DJ válida (happy path)
**Given** el Operador de dominio (analista de embargos) tiene un YAML que define las cuatro tipologías EC/EJ/DC/DJ con sus campos y reglas, cumpliendo el esquema genérico (HU-001)
**When** carga la configuración vía el mecanismo genérico
**Then** la configuración pasa la validación fail-fast (HU-002) y queda activa como dominio "embargos" sin requerir código específico de dominio

### Escenario 2 — Cargar config de embargos con regla inconsistente (error)
**Given** un YAML de tipologías de embargos cuyo esquema es válido pero declara una regla de extracción que referencia un campo inexistente
**When** el Operador de dominio intenta cargarla
**Then** la carga se rechaza con el mismo mecanismo de validación fail-fast de HU-002, identificando la regla y el campo en conflicto

### Escenario 3 — Redefinir la config de embargos ya cargada (edge)
**Given** una configuración de tipologías de embargos ya activa
**When** el Operador de dominio carga una nueva versión con ajustes a las reglas EC/EJ/DC/DJ
**Then** la nueva configuración reemplaza a la anterior siguiendo el mismo comportamiento de reemplazo definido en HU-001

---
_Redactado vía `/factory:ac HU-014`. Sin ambigüedades relevantes detectadas — historia reutiliza explícitamente el mecanismo genérico de HU-001/HU-002 sin comportamiento adicional propio._
