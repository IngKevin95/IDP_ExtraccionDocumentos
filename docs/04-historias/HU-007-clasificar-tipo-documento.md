---
id: HU-007
titulo: Clasificar tipo de documento
epica: EP-002
prioridad: Must
complejidad: M
estado: draft
---

# HU-007 — Clasificar tipo de documento

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** que el sistema clasifique automáticamente el tipo de documento que subo, **para** que se procese según su tipología real sin tener que indicarlo manualmente al subirlo.

## Contexto

Capability "Procesamiento" (EP-002). Corre junto a la extracción de campos (HU-017); en el piloto embargos (EP-006) distingue EC/EJ/DC/DJ.

**Umbral de confianza (resuelto)**: confianza mínima de clasificación = 0.7. Por debajo del umbral, el documento queda en estado `error` con motivo "confianza de clasificación insuficiente" (no se trata como HU-011, que mide precisión agregada, no casos individuales).

## INVEST (chequeo parcial)

- [x] **Valiosa**: elimina paso manual de indicar tipo al subir el documento.
- [x] **Pequeña**: complejidad M, clasificación sobre config de dominio ya resuelta.
- [x] **Verificable**: documento procesado queda con tipo asignado; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures que simulan documento encolado con config de dominio; dependencia de HU-005 es orden de pipeline en runtime, no bloqueo de desarrollo.
- [x] **Negociable**: umbral de confianza documentado arriba, ajustable en refinamiento.
- [x] **Estimable**: umbral de confianza ya definido arriba.

## Acceptance Criteria

### Escenario 1 — Clasificar documento en una tipología conocida (happy path)
**Given** un documento encolado cuya config de dominio define múltiples tipologías posibles (ej. EC/EJ/DC/DJ en el piloto de embargos)
**When** el sistema ejecuta la clasificación
**Then** el documento queda etiquetado con el tipo correcto y ese tipo determina qué reglas de extracción (HU-017) se aplican, sin intervención manual del Integrador

### Escenario 2 — Documento no encaja en ninguna tipología definida (error)
**Given** un documento cuyo contenido no corresponde a ninguna de las tipologías definidas en la config de dominio activa
**When** el sistema ejecuta la clasificación
**Then** el documento queda en estado `error` con un motivo que indica que no fue posible clasificarlo, sin proceder a la extracción de campos

### Escenario 3 — Config de dominio con una sola tipología (edge)
**Given** una config de dominio que define una única tipología posible
**When** el sistema ejecuta la clasificación sobre un documento de ese dominio
**Then** el documento se clasifica directamente en esa tipología sin ambigüedad, y el proceso continúa a extracción de campos

### Escenario 4 — Documento con evidencia ambigua entre dos tipologías (edge)
**Given** un documento cuyo contenido presenta señales compatibles con más de una tipología definida en la config
**When** el sistema ejecuta la clasificación
**Then** el sistema asigna la tipología de mayor confianza y registra la ambigüedad detectada para trazabilidad, sin bloquear el procesamiento

---
_Redactado vía `/factory:ac HU-007`. Ambigüedad resuelta (ver Contexto): umbral de confianza = 0.7; por debajo, estado `error`._
