# 0028. Lazo del muestreo ciego: quality-service solicita, review-service mide

Estado: Aceptada
Fecha: 2026-10-05

## Contexto
`quality-service` decide el muestreo ciego de los oficios auto-aprobados (`AUTO_STP`) al consumir `extraccion.aprobada`, pero ningún camino enviaba esos oficios a revisión humana: sin esa revisión no existe la medición del error real de lo auto-aprobado (KPI de error silente, `docs/producto.md`; fiabilidad AC-04). El documento ya está en estado `APROBADO` y su resultado ya pudo notificarse al integrador, así que la revisión no puede reabrir el flujo normal de HITL.

## Decisión
- Nuevo evento `calidad.muestra_ciega_solicitada.v1` (`documentId`, `tenantId`, `typology`, `sampleId`, sin PII). Lo publica `quality-service`, único productor, por outbox en la misma transacción que registra la selección, en `dominio.documentos` (clave `documentId`; no se crea un tópico dedicado porque el volumen es bajo y la topología y las ACL de dominio ya cubren a ambos servicios).
- Como `quality-service` no tiene silo por tenant, su outbox vive en la base de control (esquema `quality`); se reutiliza `OutboxRelay` con un `TenantOutboxAccess` que ignora el tenant. Es una excepción acotada al patrón de outbox por silo de `docs/arquitectura.md` §5.
- `review-service` lo consume y crea una tarea ciega (`blind_sample=true`, id = `sampleId`) con todos los campos de la última extracción. No toca el estado del documento. Sin extracción disponible no crea tarea.
- El revisor ve el recorte de página y los campos a completar; `confidence` y `originalValue` se enmascaran y el original que se compara es siempre el guardado. La comparación (transcrito vs. extraído) la hace `review-service`, que ya posee ambos valores; el evento `revision.completada` solo lleva nombre y tipo de la diferencia con `blindSample=true`. `quality-service` cuenta un desacuerdo como error silente.
- No hay segundo revisor ni `reject`: es una medición, no una corrección al modelo. La independencia se exige contra quien cargó el documento y quien intervino antes en él cuando el dato existe en el silo.
- `document-service` y `notification-service` ignoran explícitamente `revision.completada` con `blindSample=true` (estado del documento intacto y sin webhook al integrador).

## Alternativas consideradas
- Que `extraction-service` fuerce HITL antes de aprobar: contradice AUTO_STP, retrasa al cliente y mide un documento ya intervenido por humanos, no el error silente.
- Que `quality-service` consulte y compare valores: exigiría acceso a datos extraídos (PII) y rompe SEC-050 y el aislamiento de quality (SEC-035).
- Reutilizar `extraccion.requiere_revision` con `blindSample`: ese evento hace que `document-service` mueva el documento a `EN_REVISION`; el campo ya existe en el schema pero no debe emitirse para documentos aprobados.
- Tópico dedicado `calidad.eventos`: más topología y ACL sin beneficio al volumen esperado.

## Consecuencias
- Positivas: el KPI de error silente pasa a alimentarse de revisiones reales; sin PII en Kafka; sin tocar el flujo de aprobación.
- Negativas o costos: `quality-service` gana un outbox y un relay propios; los consumidores de `revision.completada` deben conocer `blindSample`; si la extracción ya no está disponible la muestra queda `PENDING` sin tarea.

## Controles relacionados
SEC-035, SEC-050, SEC-051, SEC-009 (excluido a propósito para la medición: ver spec de review-service §5.4)
