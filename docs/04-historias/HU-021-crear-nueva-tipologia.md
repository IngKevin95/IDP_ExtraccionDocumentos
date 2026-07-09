---
id: HU-021
titulo: Crear nueva tipología desde editor (self-service)
epica: EP-004
prioridad: Should
complejidad: M
estado: en-desarrollo
---

# HU-019 — Crear nueva tipología desde editor (self-service)

> Prioridad tentativa (MoSCoW) — se ratifica en `docs/05-priorizacion/`.

**Como** Administrador del dominio, **quiero** crear una nueva tipología desde cero o copiando una existente, **para** soportar nuevos tipos de documentos sin esperar a que el equipo de plataforma escriba código.

## Contexto

Complementa HU-002 (validar config). Mientras HU-002 valida una tipología **editada** (cambios a existente), HU-019 introduce creación de tipología **nueva**. El flujo es:
1. Usuario abre editor tipologías
2. Hace clic en "+ Nueva tipología"
3. Elige template base (vacío, copia de embargo_judicial, etc.)
4. Abre editor YAML
5. Guarda → validación fail-fast (HU-002) → aprobación o rechazo

## INVEST (chequeo parcial)

- [x] **Valiosa**: permite iteración rápida sin dependencias externas.
- [x] **Pequeña**: complejidad M, solo UI (creación de borrador).
- [x] **Verificable**: nueva tipología aparece en tabla "Tipologías configuradas" (estado `draft` hasta guardar).
- [x] **Independiente**: sin dependencias bloqueantes.
- [x] **Negociable**: confirmado en sesiones de discovery (multi-tenant = self-service).
- [ ] **Estimable**: pendiente.

## Acceptance Criteria

### Escenario 1 — Crear tipología vacía (happy path)
**Given** un Administrador abierto en la página Tipologías
**When** hace clic en "+ Nueva tipología", selecciona nombre "reclamacion_asegurado", dominio "Fiducia", tenant "Banco Aurora", template "Vacía", y luego "Crear y abrir editor"
**Then** el editor se abre con un campo YAML vacío (solo headers: tipologia, version, campos: []), listo para editar

### Escenario 2 — Crear tipología copiando existente (variant)
**Given** la misma situación que Escenario 1
**When** en lugar de template "Vacía", selecciona "Copiar de embargo_judicial"
**Then** el editor abre con el YAML completo de embargo_judicial, listo para modificar (nombre, campos, etc.)

### Escenario 3 — Guardar tipología nueva pasa validación (success)
**Given** un borrador nuevo con nombre "reclamacion_asegurado", 3 campos bien formados (tipo, requerido)
**When** el usuario hace clic en "Guardar configuración"
**Then** el sistema valida con fail-fast (HU-002), devuelve OK, tipología pasa a estado `activa`, y aparece en tabla

### Escenario 4 — Guardar tipología nueva falla validación (error)
**Given** un borrador con 1 campo sin tipo declarado
**When** el usuario intenta guardar
**Then** el sistema rechaza con alert: "Campo 'monto_liberado' sin tipo declarado — configuración rechazada"

### Escenario 5 — Cancelar creación sin perder contexto (cancellation)
**Given** un usuario en el formulario "+ Nueva tipología", con algunos campos parcialmente rellenados
**When** hace clic en "Cancelar"
**Then** el diálogo se cierra sin guardar nada; si el usuario vuelve a "+ Nueva tipología", los campos están vacíos (no hay draft persistido)

---

_Redactado vía `/factory:ac HU-019`. Diferencia clave vs. HU-002: HU-002 edita existente, HU-019 crea nuevo borrador._
