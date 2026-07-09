---
id: HU-012
titulo: Ver costo por documento procesado
epica: EP-005
prioridad: Must
complejidad: S
estado: draft
---

# HU-012 — Ver costo por documento procesado

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Operador de plataforma, **quiero** ver el costo por documento procesado, **para** decidir si el costo de una configuración es sostenible antes de escalarla.

## Contexto

Capability "Observabilidad de KPIs" (EP-005). KPI norte: costo.

## INVEST (chequeo parcial)

- [x] **Valiosa**: permite decisión de escalamiento informada por costo real.
- [x] **Pequeña**: complejidad S, agregación de métricas de costo ya calculadas por EP-002.
- [x] **Verificable**: costo por documento visible por dominio; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures/datos sembrados; dependencia de EP-002 es de datos en runtime, no de desarrollo.
- [x] **Negociable**: sin ambigüedades pendientes (ver nota al pie).
- [x] **Estimable**: sin ambigüedades detectadas.

## Acceptance Criteria

### Escenario 1 — Ver costo promedio por documento de un dominio (happy path)
**Given** un dominio con al menos un documento procesado exitosamente en el período consultado
**When** el Operador de plataforma consulta el costo por documento de ese dominio
**Then** el sistema muestra el costo promedio (incluyendo consumo de LLM y demás recursos computados) por documento en ese período

### Escenario 2 — Consultar costo de dominio sin documentos procesados (edge)
**Given** un dominio con configuración activa pero sin documentos procesados en el período consultado
**When** el Operador de plataforma consulta su costo por documento
**Then** el sistema responde indicando que no hay datos suficientes para calcular el costo, sin mostrar un valor de $0 engañoso

### Escenario 3 — Documento que falló en procesamiento también genera costo (edge)
**Given** un documento cuyo procesamiento consumió llamadas al LLM antes de terminar en estado `error`
**When** el Operador de plataforma consulta el costo por documento del dominio correspondiente
**Then** el costo de ese documento fallido se incluye en el cálculo agregado, sin excluirlo solo por no haber llegado a estado `completo`

### Escenario 4 — Comparar costo entre dominios de distintos tenants (edge)
**Given** un Operador de plataforma con visibilidad sobre múltiples tenants
**When** consulta el costo por documento de dominios pertenecientes a tenants distintos
**Then** el sistema muestra cada métrica claramente segmentada por tenant, sin mezclar costos entre tenants

---
_Redactado vía `/factory:ac HU-012`. Sin ambigüedades relevantes detectadas — el Escenario 3 asume, de forma consistente con el KPI norte de costo del PRD, que todo consumo real debe reflejarse aunque el documento no complete exitosamente._
