---
id: HU-001
titulo: Definir tipología de documento vía configuración YAML
epica: EP-004
prioridad: Must
complejidad: S
estado: draft
---

# HU-001 — Definir tipología de documento vía configuración YAML

> Prioridad tentativa (MoSCoW, no confirmada con el usuario) — se ratifica en `docs/05-priorizacion/`.

**Como** Integrador, **quiero** definir la tipología de documento de mi dominio (campos a extraer, reglas) vía configuración YAML, **para** incorporar un dominio nuevo sin depender de un release de código.

## Contexto

Capability "Configuración de dominio" (EP-004). Es el habilitador que distingue este proyecto de DocFly (dominio hardcodeado a embargos) — ver PRD, sección "Historias de usuario (resumen)".

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado — agregar dominio sin PR de código en el core.
- [x] **Pequeña**: complejidad S, alcance acotado a esquema de config + parseo.
- [x] **Verificable**: acción observable (config cargada, tipología disponible); AC formal pendiente de `/factory:ac`.
- [ ] **Independiente**: pendiente criterio de equipo.
- [ ] **Negociable**: pendiente criterio de equipo.
- [ ] **Estimable**: pendiente estimación formal en refinamiento.

## Acceptance Criteria

### Escenario 1 — Definir tipología nueva con YAML válido (happy path)
**Given** el Integrador tiene un archivo YAML que declara los campos a extraer y sus reglas para un dominio que aún no existe en la plataforma
**When** sube ese archivo como configuración de dominio
**Then** la tipología queda registrada y disponible para procesar documentos de ese dominio, sin requerir cambios en el código del core

### Escenario 2 — Rechazar YAML con sintaxis inválida (error)
**Given** el Integrador tiene un archivo con sintaxis YAML malformada
**When** intenta subirlo como configuración de dominio
**Then** la plataforma rechaza la carga y devuelve un error que indica la línea o el motivo del fallo de parseo, sin registrar ninguna tipología

### Escenario 3 — Rechazar configuración con campo obligatorio faltante (error)
**Given** el Integrador tiene un YAML sintácticamente válido pero sin el campo obligatorio que identifica el nombre del dominio
**When** intenta subirlo como configuración de dominio
**Then** la plataforma rechaza la carga y devuelve un error que identifica el campo faltante, sin registrar ninguna tipología

### Escenario 4 — Redefinir una tipología existente (edge)
**Given** el Integrador ya tiene una tipología activa para su dominio
**When** sube un nuevo YAML para ese mismo dominio
**Then** la plataforma reemplaza la definición anterior por la nueva y los documentos procesados después del reemplazo usan la tipología actualizada

### Escenario 5 — Definir tipología con campos duplicados (edge)
**Given** el Integrador tiene un YAML que declara el mismo campo a extraer dos veces con reglas distintas
**When** intenta subirlo como configuración de dominio
**Then** la plataforma rechaza la carga y devuelve un error señalando el nombre del campo duplicado, sin registrar ninguna tipología

---
_Redactado vía `/factory:ac HU-001`. Ambigüedad detectada: la historia no especifica si "reemplazar" una tipología (Escenario 4) debe re-procesar documentos ya extraídos con la definición anterior — se asume que no, pero queda pendiente de confirmar con el usuario._
