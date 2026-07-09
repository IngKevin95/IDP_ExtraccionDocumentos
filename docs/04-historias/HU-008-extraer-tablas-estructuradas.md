---
id: HU-008
titulo: Extraer tablas estructuradas cuando aplica
epica: EP-002
prioridad: Must
complejidad: M
estado: draft
---

# HU-008 — Extraer tablas estructuradas cuando aplica

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** que el sistema extraiga tablas estructuradas cuando el documento las contiene, **para** obtener datos tabulares utilizables (ej. relaciones, montos) sin transcripción manual.

## Contexto

Capability "Procesamiento" (EP-002). Estrategia de tablas heredada como patrón "ganador" de la referencia DocFly (`docs/00-referencia-docfly/capa-2-procesamiento-llm.md`).

## INVEST (chequeo parcial)

- [x] **Valiosa**: evita transcripción manual de tablas presentes en el documento.
- [x] **Pequeña**: complejidad M, extracción condicional (solo cuando el documento contiene tablas).
- [x] **Verificable**: tablas detectadas quedan persistidas estructuradas; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo (depende de HU-005/HU-017).
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Extraer tabla presente en el documento (happy path)
**Given** un documento con al menos una tabla identificable (ej. relación de bienes con montos)
**When** el sistema ejecuta la extracción estructurada
**Then** el resultado persiste la tabla como datos estructurados (filas y columnas), consultable junto al resto del resultado (HU-010)

### Escenario 2 — Documento sin tablas no genera extracción tabular (happy path del caso ausente)
**Given** un documento válido que no contiene ninguna tabla
**When** el sistema ejecuta la extracción estructurada
**Then** el resultado no incluye sección de tablas (o la incluye vacía), sin marcar el documento como error ni bloquear la extracción de campos (HU-017)

### Escenario 3 — Tabla con estructura irregular no puede extraerse limpiamente (error)
**Given** un documento con una tabla cuya estructura está corrupta o es ilegible (celdas fusionadas de forma inconsistente, OCR degradado)
**When** el sistema intenta extraer esa tabla
**Then** el sistema marca esa tabla específica como no extraíble con un motivo, sin bloquear el resto del procesamiento del documento

### Escenario 4 — Documento con múltiples tablas (edge)
**Given** un documento que contiene más de una tabla estructurada
**When** el sistema ejecuta la extracción estructurada
**Then** el resultado persiste cada tabla de forma independiente e identificable, sin mezclar filas entre tablas distintas

---
_Redactado vía `/factory:ac HU-008`. Sin ambigüedades relevantes detectadas — el Escenario 3 cubre el caso de degradación parcial ya anticipado en el contexto de la historia (patrón heredado de DocFly)._
