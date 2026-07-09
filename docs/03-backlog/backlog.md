# Backlog consolidado — IDP - Extracción de Documentos

> Consolidación tabular de `docs/04-historias/HU-*.md`. Orden de filas = priorización vigente (`docs/05-priorizacion/`). HU-006 se dividió en HU-017 (extracción/reintentos) y HU-018 (validación de tipo) — ver `docs/04-historias/HU-017-*.md`, `HU-018-*.md`.

| # | ID | Título | Épica | Prioridad | Complejidad | Estado | AC count | Notas |
|---|---|---|---|---|---|---|---|---|
| 1 | HU-001 | Definir tipología de documento vía configuración YAML | EP-004 | Must | S | draft | 5 | Reprocesamiento ante cambio de config: bajo demanda (ADR-005) |
| 2 | HU-002 | Validar config de dominio antes de activarla (fail-fast) | EP-004 | Must | S | en-desarrollo | 4 | Implementación en tipologias.html: validación fail-fast panel (HU-019 genera drafts, HU-002 valida) |
| 3 | HU-003 | Subir documento vía API | EP-001 | Must | S | draft | 4 | Formatos soportados: multi-formato desde MVP — PDF, JPG/PNG, DOCX (ADR-006) |
| 4 | HU-004 | Rechazar documento inválido con error claro | EP-001 | Must | S | draft | 4 | |
| 5 | HU-005 | Resolver config de dominio activa y encolar documento | EP-001 | Must | M | draft | 4 | Ambigüedad multi-match resuelta: campo `priority` explícito (ADR-007) |
| 6 | HU-017 | Extraer campos vía LLM según config de dominio (con reintentos) | EP-002 | Must | L | draft | 3 | Reemplaza a HU-006 (split). Fallo parcial resuelto: no bloquea documento, marca fiabilidad + campos críticos no extraídos (ADR-008) |
| 7 | HU-018 | Validar tipo de campo extraído | EP-002 | Must | S | draft | 3 | Reemplaza a HU-006 (split) |
| 8 | HU-007 | Clasificar tipo de documento | EP-002 | Must | M | draft | 4 | Umbral de confianza: configurable por dominio (ADR-010) |
| 9 | HU-008 | Extraer tablas estructuradas cuando aplica | EP-002 | Must | M | draft | 4 | |
| 10 | HU-009 | Consultar estado de procesamiento de un documento | EP-003 | Must | S | draft | 5 | |
| 11 | HU-010 | Obtener resultado estructurado de un documento completo | EP-003 | Must | S | en-desarrollo | 5 | Implementación UI: botón "Editar campos" + diálogo con inputs editables en documento.html (prototipo) |
| 12 | HU-016 | Recibir notificación webhook al completar procesamiento | EP-003 | Should | M | lista | 5 | Cierra gap push/webhook detectado en HU-009 y flow EP-003; política de reintentos configurable por tenant/dominio (ADR-009) |
| 13 | HU-011 | Ver precisión de extracción por dominio | EP-005 | Must | M | draft | 4 | Cálculo: golden dataset + confianza LLM combinados; fallback solo confianza si no hay golden dataset (ADR-011) |
| 14 | HU-012 | Ver costo por documento procesado | EP-005 | Must | S | draft | 4 | |
| 15 | HU-013 | Ver latencia p95 de procesamiento por dominio | EP-005 | Must | S | draft | 4 | Muestra mínima 20-30 documentos; por debajo, marca "insuficiente" (ADR-012) |
| 16 | HU-014 | Cargar configuración de tipologías de embargos (piloto) | EP-006 | Could | M | draft | 3 | |
| 17 | HU-015 | Procesar un documento de embargo real end-to-end | EP-006 | Could | M | draft | 4 | "Correcto" = sin fallos técnicos + confianza LLM sobre umbral de dominio, sin ground truth externo (ADR-013) |
| 18 | HU-020 | Gestionar usuarios y roles del tenant (auto-administración) | EP-004 | Should | M | en-desarrollo | 5 | Implementación UI: usuarios.html con invitar/editar diálogos, tabla matriz de permisos por rol (Administrador/Operador/Auditor) |
| 19 | HU-021 | Crear nueva tipología desde editor (self-service) | EP-004 | Should | M | en-desarrollo | 5 | Implementación UI: botón "+ Nueva tipología" en tipologias.html + diálogo con template base (vacío, copia existente) |

## Resumen por estado

| Estado | Cantidad |
|---|---|
| draft | 15 |
| en-desarrollo | 4 |
| lista | 1 |
| en curso | 0 |
| hecha | 0 |

**En desarrollo** (prototipo fase): HU-002 (validar tipologías fail-fast), HU-010 (editar campos), HU-020 (gestionar usuarios), HU-021 (crear tipología).
**Lista** (pasó INVEST, test coverage OK): HU-016 (webhooks). Las 15 restantes en `draft`.

## Matriz de trazabilidad épica → historias

| Épica | Historias | Cobertura |
|---|---|---|
| EP-001 — Ingesta y validación de documentos | HU-003, HU-004, HU-005 | ✅ |
| EP-002 — Procesamiento LLM configurable | HU-017, HU-018, HU-007, HU-008 | ✅ |
| EP-003 — Consulta de resultados | HU-009, HU-010, HU-016 | ✅ |
| EP-004 — Configuración de dominio (tipologías pluggable) + Administración | HU-001, HU-002, HU-020, HU-021 | ✅ |
| EP-005 — Observabilidad de KPIs | HU-011, HU-012, HU-013 | ✅ |
| EP-006 — Piloto vertical: embargos y desembargos | HU-014, HU-015 | ✅ |

## Chequeo de salud

- **Historias huérfanas (sin épica)**: ninguna — las 17 tienen `epica` asignada en frontmatter.
- **Historias fuera de `draft` sin AC**: ninguna — las 17 ya tienen AC completos (G/W/T).
- **Épicas sin ninguna historia asociada**: ninguna — EP-001 a EP-006 tienen al menos 2 historias cada una.

## Siguiente paso

`/factory:priorizar` — aplicar framework formal (MoSCoW, tentativo — no confirmado con usuario) y fijar el orden real del backlog.
