---
id: HU-013
titulo: Ver latencia p95 de procesamiento por dominio
epica: EP-005
prioridad: Must
complejidad: S
estado: draft
---

# HU-013 — Ver latencia p95 de procesamiento por dominio

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Operador de plataforma, **quiero** ver la latencia p95 de procesamiento por dominio, **para** detectar degradaciones de rendimiento antes de que afecten a integradores.

## Contexto

Capability "Observabilidad de KPIs" (EP-005). KPI norte: latencia.

**Tamaño mínimo de muestra (resuelto)**: 20 documentos completados en el período; por debajo de ese umbral se marca "muestra insuficiente" (ver Escenario 4). **Período de comparación por defecto (Escenario 3)**: últimos 7 días vs. 7 días anteriores.

## INVEST (chequeo parcial)

- [x] **Valiosa**: permite detección proactiva de degradación antes de que impacte al Integrador.
- [x] **Pequeña**: complejidad S, agregación de métricas de latencia ya calculadas por EP-001/EP-002.
- [x] **Verificable**: p95 visible por dominio; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures/datos sembrados; dependencia de EP-001/EP-002 es de datos en runtime, no de desarrollo.
- [x] **Negociable**: tamaño de muestra y período documentados arriba, ajustables en refinamiento.
- [x] **Estimable**: tamaño de muestra y período ya definidos arriba.

## Acceptance Criteria

### Escenario 1 — Ver latencia p95 de un dominio con volumen suficiente (happy path)
**Given** un dominio con múltiples documentos completados en el período consultado
**When** el Operador de plataforma consulta la latencia p95 de ese dominio
**Then** el sistema muestra el percentil 95 del tiempo total de procesamiento (desde ingesta hasta estado `completo`) sobre esos documentos

### Escenario 2 — Consultar latencia de dominio sin documentos completados (edge)
**Given** un dominio sin ningún documento en estado `completo` en el período consultado
**When** el Operador de plataforma consulta su latencia p95
**Then** el sistema responde indicando que no hay datos suficientes para calcular el percentil, sin mostrar un valor arbitrario

### Escenario 3 — Detectar degradación de latencia respecto a un período anterior (happy path de negocio)
**Given** un dominio cuya latencia p95 del período actual es significativamente mayor que la del período anterior
**When** el Operador de plataforma consulta la métrica
**Then** el sistema muestra ambos valores (actual y anterior) o la variación entre ellos, permitiendo detectar la degradación antes de que afecte a integradores

### Escenario 4 — Dominio con muy pocos documentos para un percentil confiable (edge)
**Given** un dominio con solo 1 o 2 documentos completados en el período consultado
**When** el Operador de plataforma consulta la latencia p95
**Then** el sistema muestra el valor calculado junto con una indicación de que la muestra es insuficiente para ser estadísticamente representativa

---
_Redactado vía `/factory:ac HU-013`. Ambigüedad resuelta (ver Contexto): muestra mínima = 20 documentos; período de comparación = 7+7 días._
