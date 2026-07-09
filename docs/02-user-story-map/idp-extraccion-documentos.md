# User Story Map — IDP - Extracción de Documentos

> Fuente: `docs/01-prd/idp-extraccion-documentos.md` (secciones 3 y 5) + `docs/03-backlog/epicas.md`.
> Historias listadas como `HU-XXX` son **IDs reservados, aún no escritos** — se redactan en `docs/04-historias/` vía `/factory:historia`.

## Backbone (actividades cronológicas)

```
[Configurar dominio] → [Subir documento] → [Procesar documento] → [Consultar resultado] → [Monitorear KPIs] → [Validar piloto embargos]
      EP-004                 EP-001               EP-002                 EP-003                EP-005                 EP-006
```

## Mapa por columna

| Configurar dominio (EP-004) | Subir documento (EP-001) | Procesar documento (EP-002) | Consultar resultado (EP-003) | Monitorear KPIs (EP-005) | Validar piloto embargos (EP-006) |
|---|---|---|---|---|---|
| HU-001 Definir tipología de documento vía config YAML (campos, reglas) | HU-003 Subir documento vía API | HU-017 Extraer campos vía LLM según config (con reintentos) | HU-009 Consultar estado de procesamiento (pendiente/completo/error) | HU-011 Ver precisión de extracción por dominio | HU-014 Cargar config de tipologías EC/EJ/DC/DJ |
| HU-002 Validar config de dominio antes de activarla (fail-fast) | HU-004 Rechazar documento inválido con error claro | HU-018 Validar tipo de campo extraído | HU-010 Obtener resultado estructurado una vez completo | HU-012 Ver costo por documento procesado | HU-015 Procesar documento de embargo real end-to-end sobre el core |
| | HU-005 Resolver config de dominio activa y encolar trabajo | HU-007 Clasificar tipo de documento | HU-016 Recibir notificación push al completar procesamiento | HU-013 Ver latencia p95 por dominio | |
| | | HU-008 Extraer tablas estructuradas cuando aplica | | | |

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ **línea de MVP** (ver justificación abajo) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

La columna **Validar piloto embargos** queda fuera del corte de MVP.

## Justificación de la línea de MVP

El PRD (Definición de "hecho") exige explícitamente que **los 3 KPIs se midan automáticamente desde el primer release** — no es una mejora posterior, es parte de "hecho". Por eso la línea de corte incluye la columna de KPIs (EP-005), no solo ingesta/procesamiento/consulta.

La columna **Validar piloto embargos** (EP-006) corresponde a la fase **"Piloto — dominio embargos"** del Plan de entrega del PRD, que depende explícitamente de "MVP completo" — es decir, el propio PRD la trata como posterior al MVP, no como parte de él. El corte respeta esa secuencia.

## Huecos de cobertura detectados

- **Push/webhook de resultados**: resuelto — formalizado en `HU-016` (decisión de ADR: "soportar ambos desde el diseño"). Ya referenciada en la columna "Consultar resultado" (EP-003).
- **Gestión/versionado de config de dominio ya activa**: `HU-001`/`HU-002` cubren crear y validar una config, pero no editar/versionar una config existente sin downtime — no bloqueante para MVP (el PRD no lo pide), se anota como hueco menor.
- **Multi-tenancy operativa** (alta/gestión de tenants): el PRD asume un tenant/config a la vez en su alcance ("Non-goals": "Soporte multi-tenant productivo con aislamiento fuerte... es decisión de ADR pendiente") — ausencia es intencional, no un hueco real, coherente con el propio PRD.
- **Orquestación/Admin (Capa 4 de referencia)**: correctamente ausente del mapa — el PRD la marca como no-MVP explícitamente.

## Siguiente paso

`/factory:historia HU-001` en loop columna por columna (empezando por "Configurar dominio", que abre el resto del journey), o `/factory:flujo` para automatizar el recorrido completo.
