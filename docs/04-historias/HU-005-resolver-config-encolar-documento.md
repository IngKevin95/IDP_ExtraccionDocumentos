---
id: HU-005
titulo: Resolver config de dominio activa y encolar documento
epica: EP-001
prioridad: Must
complejidad: M
estado: draft
---

# HU-005 — Resolver config de dominio activa y encolar documento

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** que el sistema resuelva automáticamente la configuración de mi dominio y encole el documento para procesamiento, **para** no tener que indicar manualmente qué reglas de extracción aplicar en cada subida.

## Contexto

Capability "Ingesta" (EP-001). Cierra el flujo de ingesta: documento válido (HU-003/HU-004) + config de dominio (HU-001/HU-002) resuelto → trabajo encolado para EP-002.

**Criterio de resolución multi-dominio (resuelto, Escenario 4)**: la subida (HU-003) exige un metadato explícito `domain_id`; con múltiples dominios activos, el sistema resuelve exactamente ese dominio y rechaza la subida si el metadato falta o no corresponde a un dominio activo del Integrador.

## INVEST (chequeo parcial)

- [x] **Valiosa**: elimina trabajo manual de asociar documento↔config en cada subida.
- [x] **Pequeña**: complejidad M, integra resolución de config con encolado asíncrono.
- [x] **Verificable**: documento válido queda encolado con su config resuelta; AC formal pendiente de `/factory:ac`.
- [x] **Independiente**: desarrollo aislado con fixtures que simulan documento válido + config activa; dependencia de HU-001-004 es orden de pipeline en runtime, no bloqueo de desarrollo.
- [x] **Negociable**: criterio de resolución multi-dominio documentado arriba, ajustable en refinamiento.
- [x] **Estimable**: criterio de resolución multi-dominio ya definido arriba.

## Acceptance Criteria

### Escenario 1 — Resolver config activa y encolar documento válido (happy path)
**Given** un documento válido (HU-003/HU-004 ya superadas) subido por un Integrador que tiene una configuración de dominio activa (HU-001/HU-002)
**When** el sistema procesa la ingesta del documento
**Then** el documento queda encolado para procesamiento junto con la referencia a la configuración de dominio resuelta, sin intervención manual del Integrador

### Escenario 2 — Rechazar encolado cuando no hay config de dominio activa (error)
**Given** un documento válido subido por un Integrador que aún no tiene ninguna configuración de dominio activa
**When** el sistema intenta resolver la configuración para encolar el documento
**Then** el sistema rechaza el encolado y responde con un error que indica que no hay configuración de dominio activa para ese Integrador

### Escenario 3 — Fallo al encolar por indisponibilidad de la cola (error)
**Given** un documento válido con configuración de dominio resuelta correctamente
**When** el sistema intenta publicar el trabajo en la cola de procesamiento y la cola no está disponible
**Then** el sistema no pierde el documento, registra el fallo y lo reintenta o lo deja en estado recuperable, sin marcarlo como procesado

### Escenario 4 — Resolver config cuando el Integrador tiene múltiples dominios configurados (edge)
**Given** un Integrador con más de una configuración de dominio activa simultáneamente
**When** sube un documento que corresponde a uno de esos dominios
**Then** el sistema resuelve y asocia la configuración correspondiente al dominio correcto, no una arbitraria

---
_Redactado vía `/factory:ac HU-005`. Ambigüedad resuelta (ver Contexto): criterio de resolución multi-dominio = metadato `domain_id` obligatorio en la subida._
