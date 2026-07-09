---
id: HU-003
titulo: Subir documento vía API
epica: EP-001
prioridad: Must
complejidad: S
estado: draft
---

# HU-003 — Subir documento vía API

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** subir un documento vía API, **para** recibir datos estructurados extraídos sin construir mi propio pipeline de IA.

## Contexto

Capability "Ingesta" (EP-001). Punto de entrada del pipeline — ver PRD, sección "Historias de usuario (resumen)".

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado textualmente en el PRD.
- [x] **Pequeña**: complejidad S, endpoint de recepción de archivo.
- [x] **Verificable**: documento subido queda registrado en el sistema; AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo.
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Subir documento válido con autenticación correcta (happy path)
**Given** el Integrador tiene credenciales válidas y un documento en formato soportado por la plataforma
**When** sube el documento vía API
**Then** el sistema acepta la subida, asigna un identificador único al documento y responde con estado 202 (aceptado, pendiente de procesamiento)

### Escenario 2 — Rechazar subida sin autenticación (error)
**Given** una petición de subida de documento sin credenciales válidas
**When** llega al endpoint de subida
**Then** el sistema responde con error de autenticación (401) y no registra ningún documento

### Escenario 3 — Rechazar documento en formato no soportado (error)
**Given** el Integrador tiene un archivo en un formato que la plataforma no soporta
**When** intenta subirlo vía API
**Then** el sistema responde con un error que indica los formatos soportados y no registra el documento

### Escenario 4 — Subir documento en el límite máximo de tamaño permitido (edge)
**Given** el Integrador tiene un documento cuyo tamaño es exactamente el límite máximo configurado en la plataforma
**When** lo sube vía API
**Then** el sistema acepta la subida y responde con estado 202, igual que en el flujo esperado

---
_Redactado vía `/factory:ac HU-003`. Ambigüedad detectada: la historia no define el límite de tamaño ni los formatos soportados — se asume que existe una configuración de plataforma que los define (referencia a capa de Ingesta); pendiente de confirmar el valor exacto con el usuario o en el ADR pausado._
