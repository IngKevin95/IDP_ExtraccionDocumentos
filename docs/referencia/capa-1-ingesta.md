# Capa 1 — Ingesta

> Abstracción funcional de referencia (DocFly). Insumo para el PRD to-be de "IDP - Extracción de Documentos". No es documentación as-is exhaustiva; captura decisiones y patrones reutilizables.

## Repos de referencia

| Repo | Tipo | Rol |
|---|---|---|
| `docfly-to-classifier` | Cloud Function (8083) | Punto de entrada: recepción y validación de PDF |
| `docfly-publisher` | Cloud Function (8081) | Ingesta con contexto de negocio + prompt, encola trabajo |
| `dav-emb-v2-api-extraccion-entidades` | Cloud Run (8001) | Extracción avanzada de entidades tipificadas (YAML-driven) |

## Propósito funcional

Punto de entrada del pipeline. Recibe documentos (PDF), los valida estructuralmente, resuelve el tenant/cliente, adjunta contexto de negocio y prompt de extracción, y publica el trabajo de forma asíncrona para que la Capa 2 lo procese.

## Flujo

1. **Upload/validación** (`docfly-to-classifier`): recibe el PDF, corre validación en 7 niveles (integridad de archivo, tamaño, número de páginas, etc.), rechaza documentos inválidos antes de gastar cómputo aguas abajo.
2. **Ingesta con contexto** (`docfly-publisher`): resuelve tenant vía colección `subscriptions` en Firestore, arma el prompt de extracción según tipología del cliente, escribe metadata en Firestore (`documents`) y publica mensaje en Pub/Sub (`topic-docflydev`).
3. **Extracción de entidades avanzada** (`dav-emb-v2-api-extraccion-entidades`): servicio FastAPI independiente, config-driven por YAML, soporta tipologías EC/EJ/DC/DJ (Embargo Civil/Judicial, Desembargo Civil/Judicial) — más granular que la extracción genérica de Capa 2.

## Stack técnico

- Python 3.10-3.13, `functions-framework` (Cloud Functions), FastAPI (solo `dav-emb-v2-*`)
- `google-cloud-firestore`, `google-cloud-pubsub`, `google-cloud-storage`
- PyPDF2 / pypdf / pikepdf para validación estructural de PDF
- PyYAML para configuración de tipologías

## Interfaces expuestas

- HTTP (Cloud Function trigger) para upload de documento.
- API REST (`dav-emb-v2-api-extraccion-entidades`, FastAPI) — único servicio de esta capa con OpenAPI propio.
- Publica a Pub/Sub topic `topic-docflydev` (mensaje con referencia a documento + contexto).

## Multi-tenancy

Resuelto vía colección `subscriptions` en Firestore: `customerid`, `remainingpages`, `isactive`. Cada servicio de esta capa consulta esta colección de forma independiente (fragmentado, no centralizado).

## Notas de arquitectura (candidatas a ADR)

- **Validación temprana (fail-fast)**: rechazar en el borde antes de encolar — patrón a preservar en el to-be.
- **Config-driven por YAML** para tipologías en `dav-emb-v2-api-extraccion-entidades` — evita hardcodear reglas de negocio en código; candidato a estandarizar en toda la plataforma.
- **Balanceo regional** (`RegionService.select_region()`) entre `us-central1`/`us-east4` aplicado en esta capa — decisión de disponibilidad a revisar en el to-be (¿multi-región desde el día 1 o iteración posterior?).

## Notas de seguridad

- Credenciales de servicio (service accounts GCP) y datos de clientes viven en `.env`/JSON de credenciales dentro de cada repo — no reproducidos aquí, solo su existencia. El to-be debe definir gestión de secretos explícita (candidato a ADR).
