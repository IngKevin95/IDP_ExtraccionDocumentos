---
id: HU-011
titulo: Ver precisión de extracción por dominio
epica: EP-005
prioridad: Must
complejidad: M
estado: draft
---

# HU-011 — Ver precisión de extracción por dominio

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Operador de plataforma, **quiero** ver la precisión de extracción por dominio, **para** decidir si una configuración de extracción está lista para producción.

## Contexto

Capability "Observabilidad de KPIs" (EP-005). Cita textual del PRD, sección "Historias de usuario (resumen)". KPI norte: precisión.

**Método de cálculo de precisión (resuelto)**: confianza agregada reportada por el LLM sobre los campos extraídos (no ground truth etiquetado manualmente — queda como mejora post-MVP si se requiere mayor rigor). **Período de agregación por defecto**: últimos 30 días.

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado textualmente en el PRD.
- [x] **Pequeña**: complejidad M, agregación de métricas ya calculadas por EP-002.
- [x] **Verificable**: métrica de precisión visible por dominio; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures/datos sembrados; dependencia de EP-002 es de datos en runtime, no de desarrollo.
- [x] **Negociable**: alcance de método de cálculo y período documentado arriba, ajustable en refinamiento.
- [x] **Estimable**: método de cálculo y período ya definidos arriba.

## Acceptance Criteria

### Escenario 1 — Ver precisión de un dominio con documentos procesados (happy path)
**Given** un dominio con al menos un documento procesado (completo o error) en el período consultado
**When** el Operador de plataforma consulta la métrica de precisión de ese dominio
**Then** el sistema muestra el porcentaje de precisión calculado sobre los documentos procesados en ese período

### Escenario 2 — Consultar precisión de dominio sin documentos procesados aún (edge)
**Given** un dominio con configuración activa pero sin ningún documento procesado todavía
**When** el Operador de plataforma consulta su métrica de precisión
**Then** el sistema responde indicando que no hay datos suficientes para calcular la métrica, en lugar de mostrar un valor numérico engañoso (ej. 0% o 100%)

### Escenario 3 — Consultar precisión de un dominio inexistente (error)
**Given** un identificador de dominio que no existe en la plataforma
**When** el Operador de plataforma consulta su métrica de precisión
**Then** el sistema responde con un error indicando que el dominio no existe

### Escenario 4 — Comparar precisión entre dominios de distintos tenants (edge)
**Given** un Operador de plataforma con visibilidad sobre múltiples tenants
**When** consulta la precisión de dominios pertenecientes a tenants distintos
**Then** el sistema muestra cada métrica claramente segmentada por tenant, sin mezclar datos de extracción entre tenants

---
_Redactado vía `/factory:ac HU-011`. Ambigüedad resuelta (ver Contexto): método de cálculo = confianza LLM agregada; período = 30 días._
