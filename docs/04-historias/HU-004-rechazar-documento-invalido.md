---
id: HU-004
titulo: Rechazar documento inválido con error claro
epica: EP-001
prioridad: Must
complejidad: S
estado: draft
---

# HU-004 — Rechazar documento inválido con error claro

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** recibir un error claro cuando el documento que subo es inválido, **para** corregir el problema de inmediato sin esperar el ciclo completo de procesamiento.

## Contexto

Capability "Ingesta" (EP-001). Validación fail-fast en el borde de entrada — evita gastar cómputo LLM en documentos inválidos (patrón heredado de DocFly).

## INVEST (chequeo parcial)

- [x] **Valiosa**: feedback inmediato evita ciclos de espera innecesarios.
- [x] **Pequeña**: complejidad S, validación estructural sobre HU-003.
- [x] **Verificable**: documento inválido rechazado con mensaje de error; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo (acoplada a HU-003).
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Rechazar documento corrupto con mensaje claro (happy path del error)
**Given** el Integrador sube un archivo que declara ser de un formato soportado pero cuyo contenido está corrupto
**When** el sistema intenta leer su estructura durante la validación de ingesta
**Then** el sistema rechaza el documento de inmediato y responde con un error que indica que el archivo está corrupto, sin encolarlo para procesamiento

### Escenario 2 — Rechazar documento vacío (error)
**Given** el Integrador sube un archivo de tamaño cero bytes
**When** el sistema valida el documento en el borde de entrada
**Then** el sistema rechaza el documento con un error que indica que el archivo está vacío, sin encolarlo para procesamiento

### Escenario 3 — Rechazar documento que excede el tamaño máximo permitido (error)
**Given** el Integrador sube un archivo cuyo tamaño supera el límite máximo configurado en la plataforma
**When** el sistema valida el documento en el borde de entrada
**Then** el sistema rechaza el documento con un error que indica el límite permitido y el tamaño recibido, sin encolarlo para procesamiento

### Escenario 4 — Rechazar documento con extensión que no coincide con su contenido real (edge)
**Given** el Integrador sube un archivo cuya extensión declara un formato soportado pero cuyo contenido binario corresponde a otro tipo de archivo
**When** el sistema valida el documento en el borde de entrada
**Then** el sistema rechaza el documento con un error que indica la inconsistencia entre extensión y contenido, sin encolarlo para procesamiento

---
_Redactado vía `/factory:ac HU-004`. Sin ambigüedades relevantes detectadas — historia acotada a validación estructural en el borde de entrada, previa a HU-005._
