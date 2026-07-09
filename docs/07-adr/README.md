# Architecture Decision Records — IDP - Extracción de Documentos

> Convención: `ADR-XXX-<slug>.md`, 3 dígitos, sin saltos. Formato Nygard (Contexto / Decisión / Consecuencias). Estado inicial `Aceptado` salvo que se indique lo contrario — todas las decisiones aquí fueron confirmadas explícitamente por el usuario vía `AskUserQuestion` durante la fase de diseño.
>
> No existe convención de ADR en la metodología `@factory/spec-driven-product` (cubre PRD → Flows, no arquitectura) — esta carpeta es un anexo del proyecto, fuera del pipeline Factory.

## Índice

| ADR | Título | Dispara / referencia |
|---|---|---|
| [ADR-001](ADR-001-topologia-microservicios.md) | Topología de microservicios por capa lógica | Diseño general (4 capas) |
| [ADR-002](ADR-002-persistencia-dual-adapter.md) | Persistencia dual-adapter multi-motor con aislamiento por tenant | Requisito de multi-tenancy y portabilidad de BD |
| [ADR-003](ADR-003-cloud-agnostic.md) | Diseño cloud-agnostic (Kubernetes + storage S3-compatible) | Requisito de portafolio: no amarrado a GCP |
| [ADR-004](ADR-004-entrega-dual-poll-webhook.md) | Patrón de entrega de resultados dual (publish/poll + push/webhook) | EP-003, HU-009, HU-010, HU-016 |
| [ADR-005](ADR-005-reprocesamiento-tipologia.md) | Reprocesamiento bajo demanda ante cambios de configuración de dominio | HU-001 |
| [ADR-006](ADR-006-formatos-limites-ingesta.md) | Formatos y límites de ingesta de documentos | HU-003 |
| [ADR-007](ADR-007-resolucion-multi-config.md) | Resolución de configuración de dominio ante ambigüedad multi-match | HU-005 |
| [ADR-008](ADR-008-fallo-parcial-extraccion-llm.md) | Manejo de fallos parciales de extracción LLM y trazabilidad de campos no resueltos | HU-017 (split de HU-006) |
| [ADR-009](ADR-009-retry-webhook-configurable.md) | Política de reintentos de entrega de webhook configurable por tenant/dominio | HU-016 |
| [ADR-010](ADR-010-umbral-confianza-clasificacion.md) | Umbral de confianza de clasificación configurable por dominio | HU-007 |
| [ADR-011](ADR-011-calculo-precision.md) | Método de cálculo del KPI de precisión de extracción | HU-011 |
| [ADR-012](ADR-012-muestra-minima-p95.md) | Tamaño mínimo de muestra para latencia p95 confiable | HU-013 |
| [ADR-013](ADR-013-criterio-exito-piloto.md) | Criterio de éxito del piloto vertical de embargos | HU-015 |
| [ADR-014](ADR-014-stack-tecnologico-polyglot.md) | Stack tecnológico polyglot por microservicio | Kickoff construcción, todas las épicas |
| [ADR-015](ADR-015-estetica-frontend-empresarial.md) | Estética de frontend empresarial (sin genericidad) | Frontend Next.js, EP-003/EP-005 |
