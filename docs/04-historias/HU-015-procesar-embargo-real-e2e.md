---
id: HU-015
titulo: Procesar un documento de embargo real end-to-end
epica: EP-006
prioridad: Could
complejidad: M
estado: draft
---

# HU-015 — Procesar un documento de embargo real end-to-end

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`. Columna post-MVP "Validar piloto embargos" del mapa.

**Como** Operador de dominio (analista de embargos), **quiero** procesar un documento de embargo real end-to-end sobre el core genérico, **para** confirmar que el resultado extraído es correcto y utilizable en mi proceso real, sin depender del sistema legado.

## Contexto

Capability piloto vertical embargos (EP-006). Cierra el ciclo completo (HU-003 a HU-010) usando la config cargada en HU-014, validando el core genérico frente al caso de uso original que motivó el proyecto (referencia DocFly).

**Criterio de "correcto" (resuelto, Escenario 1)**: ≥95% de campos coincidentes contra un documento de referencia anotado manualmente por el analista. **N mínimo del piloto**: 10 documentos de embargo reales.

## INVEST (chequeo parcial)

- [x] **Valiosa**: confirma utilidad real del sistema frente al proceso legado, cerrando el piloto.
- [x] **Pequeña**: complejidad M, orquesta capacidades ya existentes (EP-001/EP-002/EP-003) sin lógica nueva.
- [x] **Verificable**: resultado extraído coincide con el esperado para el documento de embargo real; AC formal pendiente de `/factory:ac`.
- [x] **Independiente** (excepción documentada): es historia de aceptación/DoD de EP-006 por naturaleza — orquesta HU-003 a HU-010 y HU-014 ya construidas; no aporta código nuevo, solo valida el core genérico end-to-end.
- [x] **Negociable**: criterio de "correcto" y N mínimo documentados arriba, ajustables en refinamiento.
- [x] **Estimable**: criterio de "correcto" y N mínimo ya definidos arriba.

## Acceptance Criteria

### Escenario 1 — Procesar un embargo real end-to-end con éxito (happy path)
**Given** la config de tipologías de embargos ya está activa (HU-014) y el Operador de dominio tiene un documento de embargo real
**When** sube el documento vía API (HU-003) y espera a que el ciclo completo de ingesta, clasificación, extracción y consulta (HU-005 a HU-010) termine
**Then** el resultado estructurado final coincide con los datos reales del embargo (tipología correcta, campos y tablas extraídos), confirmando que el core genérico produce un resultado utilizable sin lógica de dominio hardcodeada

### Escenario 2 — Documento de embargo real rechazado por inválido (error)
**Given** un documento de embargo real que está corrupto o excede el tamaño máximo permitido
**When** el Operador de dominio intenta subirlo
**Then** el sistema lo rechaza con el mismo comportamiento fail-fast de HU-004, sin iniciar el pipeline de procesamiento

### Escenario 3 — Documento de embargo real que el LLM no logra procesar (error)
**Given** un documento de embargo real subido exitosamente y encolado
**When** el procesamiento LLM falla de forma irrecuperable (HU-017/HU-007)
**Then** el documento queda en estado `error` (HU-009) con motivo trazable, evidenciando un caso real donde el core genérico aún no cubre el documento, sin comprometer el resto del piloto

### Escenario 4 — Resultado extraído difiere del esperado por el analista (edge)
**Given** un documento de embargo real que termina en estado `completo`
**When** el Operador de dominio compara el resultado estructurado contra su conocimiento del caso real
**Then** cualquier discrepancia queda documentada como hallazgo del piloto (no como fallo técnico del sistema), sirviendo de insumo para ajustar la config de tipologías (HU-014) en una iteración posterior

---
_Redactado vía `/factory:ac HU-015`. Ambigüedad resuelta (ver Contexto): criterio de "correcto" = ≥95% campos vs. referencia anotada; N mínimo = 10 documentos._
