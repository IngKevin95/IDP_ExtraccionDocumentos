# Capa 3 — Consulta

> Abstracción funcional de referencia (DocFly). Insumo para el PRD to-be de "IDP - Extracción de Documentos".

## Repos de referencia

| Repo | Tipo | Rol |
|---|---|---|
| `docfly-query-classification` | Cloud Run (8003) | Clasifica intención de consulta entrante |
| `docfly-response-service` | Cloud Run (8004) | Sirve resultados de extracción ya procesados |

## Propósito funcional

Capa de salida del pipeline: expone al consumidor (cliente/integración) los resultados de la extracción, y clasifica/enruta consultas antes de responder.

## Flujo

1. **Clasificación de consulta** (`docfly-query-classification`): recibe la consulta entrante, determina intención/tipo antes de enrutarla.
2. **Servicio de respuesta** (`docfly-response-service`): consulta Firestore (`documents`, `classifications`) por el estado/resultado del procesamiento y lo devuelve al cliente — patrón publish/poll: el cliente pregunta por resultado en vez de recibir push.

## Stack técnico

- Python 3.10-3.13, FastAPI
- `google-cloud-firestore`
- **Arquitectura hexagonal** (Domain → Application → Infrastructure), SOLID, 100% test coverage (44 tests) — único par de servicios de todo el sistema DocFly con este nivel de rigor arquitectónico. El resto de servicios usa patrones más simples (monolítico en publisher/worker, SRP modular en dav-emb-v2).

## Interfaces expuestas

- APIs REST (Cloud Run) para consulta de estado y resultado de documento.

## Multi-tenancy

Consulta filtrada por `customerid` contra la misma colección `subscriptions`/`documents` usada en capas anteriores.

## Notas de arquitectura (candidatas a ADR)

- **Patrón publish/poll (async publish + polling de resultado)**: confirmado end-to-end en el flujo publisher → Pub/Sub → worker → Firestore → response-service. Candidato firme a ADR: ¿mantener polling en el to-be, o evolucionar a push (webhook/WebSocket) para el cliente final?
- **Arquitectura hexagonal usada solo en 2 de 8 servicios**: inconsistencia de patrón arquitectónico entre servicios del sistema de referencia. Candidato a ADR: ¿estandarizar hexagonal en todos los servicios del to-be, o mantener "complejidad proporcional al servicio" (patrón simple donde el dominio es simple)?

## Notas de seguridad

- Ningún resultado de extracción de cliente real (datos de embargos/desembargos) se reproduce en este documento — solo la forma del contrato de API.
