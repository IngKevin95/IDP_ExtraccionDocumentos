---
id: HU-020
titulo: Gestionar usuarios y roles del tenant (auto-administración)
epica: EP-004
prioridad: Should
complejidad: M
estado: lista
---

# HU-020 — Gestionar usuarios y roles del tenant (auto-administración)

> Prioridad tentativa (MoSCoW) — se ratifica en `docs/05-priorizacion/`.

**Como** Administrador del tenant, **quiero** invitar usuarios, asignar roles (Administrador/Operador/Auditor) y gestionar permisos, **para** controlar quién tiene acceso a qué funcionalidad en el platform sin depender de un equipo central.

## Contexto

Capability "Administración multi-tenant" (no asignada a épica, cae bajo gobernanza de plataforma). Complementa el aislamiento por tenant (ADR-007): cada tenant se autoadministra, con roles que limitan acceso a ingesta, edición, configuración, y visualización de datos.

## INVEST (chequeo parcial)

- [x] **Valiosa**: beneficio externo declarado (autoservicio, reduces ops bottleneck).
- [x] **Pequeña**: complejidad M, alcanza solo a UI (prototype) + permisos RBAC en backend.
- [x] **Verificable**: matriz de permisos por rol verificable; AC formal en `/factory:ac HU-020`.
- [x] **Independiente**: sin dependencias externas.
- [x] **Negociable**: criterio de equipo confirmado (Banco Aurora, NeoFin, Fiduciaria Meridiano requieren).
- [ ] **Estimable**: pendiente estimación formal.

## Acceptance Criteria

### Escenario 1 — Invitar usuario a tenant (happy path)
**Given** un usuario Administrador del tenant Banco Aurora, y un correo no registrado
**When** el Administrador completa el formulario "Invitar usuario" (correo, rol, tenant) y hace clic en "Enviar invitación"
**Then** el sistema envía un email de invitación al usuario nuevo, con enlace de onboarding

### Escenario 2 — Cambiar rol de usuario activo (modification)
**Given** un usuario operador ya activo en el tenant Banco Aurora
**When** el Administrador abre el panel "Usuarios", selecciona el usuario, cambia su rol a "Administrador" y guarda
**Then** el usuario cambia inmediatamente a Administrador, sin necesidad de re-login, y accede a funcionalidades anteriormente bloqueadas (gestión de webhooks, tipologías)

### Escenario 3 — Suspender usuario (suspension)
**Given** un usuario activo del tenant
**When** el Administrador marca el usuario como "Suspendido" y confirma
**Then** el usuario es bloqueado: no puede volver a login hasta que un Administrador lo reactive; documentos en procesamiento se marcan como "requiere revisión manual"

### Escenario 4 — Ver matriz de permisos por rol (reference)
**Given** un Administrador del tenant
**When** abre la pestaña "Roles y permisos"
**Then** ve una tabla de permisos claros por rol: Administrador (todo), Operador (ingesta + edición + consulta), Auditor (consulta + KPIs solo)

### Escenario 5 — Intentar invitar usuario a otro tenant (error / edge)
**Given** un usuario Administrador de Banco Aurora
**When** intenta invitar a un correo selectcionando el tenant "NeoFin"
**Then** el sistema rechaza la acción (403): "Solo puedes invitar usuarios a tu tenant"

---

_Redactado vía `/factory:ac HU-020`. Rol "Auditor" agregado tras demanda; AC 4 es referencia sin test formal (UI no tiene interactividad en prototype)._
