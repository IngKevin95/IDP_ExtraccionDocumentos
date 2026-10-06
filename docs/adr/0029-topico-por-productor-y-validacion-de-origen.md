# 0029. Tópico por productor y validación de origen de eventos

Estado: Aceptada
Fecha: 2026-10-05

## Contexto
Hasta ahora varios servicios tenían `Write` sobre el mismo tópico `dominio.documentos`. Que `document-service` fuera el "único productor" de `extraccion.aprobada`, `review-service` de `revision.completada` o `quality-service` de `calidad.muestra_ciega_solicitada` era solo una convención documental: un servicio comprometido con `Write` en el tópico podía publicar `revision.completada` APROBADO con un revisor real (el chequeo de rol de `document-service` lo aceptaba) o forzar muestras ciegas. Es una falla de integridad de origen: Kafka autoriza por tópico, no por tipo de evento.

## Decisión
- Un tópico por productor o familia, con `Write` exclusivo del `KafkaUser` productor:

| Tópico | Productor | Eventos | Clave |
|---|---|---|---|
| `documentos.eventos` | `document-service` | `documento.recibido/rechazado/renderizado/purgado`, `extraccion.solicitada`, `extraccion.aprobada` | `documentId` |
| `extraccion.eventos` | `extraction-service` | `extraccion.completada`, `extraccion.requiere_revision`, `ia.ejecucion_registrada` | `documentId` |
| `revision.eventos` | `review-service` | `revision.completada`, `revision.escalada` | `documentId` |
| `calidad.eventos` | `quality-service` | `calidad.muestra_ciega_solicitada` | `documentId` |
| `notificaciones.eventos` | `notification-service` | `webhook.entregado`, `webhook.fallido` | `documentId` |
| `idp.tenant.events` | `tenant-service` | `tenant.*`, `acceso.revocado`, `acceso.rol_sensible_otorgado`, `breakglass.*`, `cuota.umbral_alcanzado` | `tenantId` |
| `auditoria.senales` | varios (excepción, señales) | `seguridad.*`, `chat.*` | `tenantId` |
| `auditoria.control` | `tenant-service`, `audit-service`, `extraction-service` (productores explícitos por eventType) | `legalhold.*` (`tenant-service`, `audit-service`), `consumo.registrado` (`tenant-service`, `extraction-service`), `auditoria.alerta_integridad` (`audit-service`) | `tenantId` |

- Excepción documentada: solo los tópicos de auditoría admiten varios productores, separados por naturaleza (revisión de seguridad posterior a la primera versión de este ADR: un único `auditoria.eventos` mezclaba señales con eventos de control, y cualquiera de siete servicios podía emitir `legalhold.*` o `auditoria.alerta_integridad` y contaminar el dossier probatorio):
  - `auditoria.senales` (`seguridad.*`, `chat.*`): multi-productor abierto. `audit-service` las ingiere en la cadena de hash como señales, no como estado autoritativo; ningún servicio decide nada de negocio a partir de ellas.
  - `auditoria.control` (`legalhold.*`, `consumo.registrado`, `auditoria.alerta_integridad`): `Write` solo para los productores legítimos según el código (`tenant-service`, `audit-service`, `extraction-service`), con productores explícitos por eventType en la topología (el relay y el publicador de cada servicio rechazan los que no le corresponden).
  - Regla de CI: un tópico multi-productor abierto (`kind: signal`) solo puede contener señales (`seguridad.*`, `chat.*`); estado y control nunca. Los tópicos de dominio tienen un único productor.
  - Cada servicio sigue escribiendo solo los eventTypes que la topología le asigna.
- Fuente única de verdad: `contracts/events/topology.yaml` (por eventType: tópico, productores, clave). `libs/events` la empaqueta (como los esquemas) y expone `EventTopology` (`topicFor`, `producersOf`, `allowedTopicFor`, `topicsFor`).
- Productores: los relays del outbox (`OutboxRelay`) y los publicadores (`JdbcOutboxPublisher`, `JdbcOutbox` de tenant, `KafkaAuditEventPublisher`) resuelven el tópico por eventType con `allowedTopicFor(servicio, eventType)`; un eventType que el servicio no produce falla de forma explícita (al encolar, y de nuevo en el relay).
- Consumidores: cada consumo valida con `EventOriginGuard` que el eventType llegó por el tópico que la topología le asigna (topic del `ConsumerRecord`). Si no coincide: el evento se ignora, se incrementa `idp.events.origin.rejected` y se registra una alerta `SECURITY` sin payload. Aplica a document, extraction, review, quality, notification, audit y a los listeners de `libs/security` (`acceso.revocado`, `tenant.baja_iniciada`). Los `@KafkaListener` suscriben por defecto a los tópicos que dicta la topología (propiedades configurables).
- Defensa en el consumidor más allá del origen: el tópico correcto no prueba que el contenido sea cierto (un productor legítimo comprometido sigue pudiendo mentir en lo suyo), por eso cada consumidor contrasta el evento con su propio registro: `document-service` auto-aprueba solo con `routing=AUTO` (campo aditivo de `extraccion.completada`) y una solicitud de extracción registrada, y aprueba una revisión solo si existe la solicitud de revisión registrada al consumir `extraccion.requiere_revision` y el revisor es distinto del cargador; `review-service` acepta una muestra ciega solo si, en su silo, el documento está `APROBADO` por `AUTO_STP`, no es `ALTAMENTE_CONFIDENCIAL` y el tenant está activo; `audit-service` valida el `tenantId` contra el directorio de la plataforma antes de abrir cadena y descarta con alerta SECURITY sin payload lo que no pueda encadenar por su contenido (reintento infinito solo ante errores de infraestructura).
- Observabilidad: `idp.events.origin.rejected` (contador exacto, log limitado a 1/min por (tópico, eventType)) y los descartes de auditoría tienen `PrometheusRule` crítica (`deploy/platform/prometheus-rules-eventos.yaml`).
- Replay desde DLT: los `*-dlt` no son tópicos de pipeline. Reproducir un mensaje desde un DLT solo se hace con una identidad de operación auditada (no con la cuenta del servicio) y revalidando el productor del eventType contra la topología antes de republicarlo en el tópico de su productor; el consumidor igualmente vuelve a validar el origen.
- Infra: `users.yaml` con `Write` solo sobre los tópicos productores (más los `*-dlt` de los tópicos que cada servicio consume, donde `DeadLetterPublishingRecoverer` publica) y `Read` sobre los que consume; `renderer` y `edge-gateway` sin usuario. `tools/ci/check_kafka_topics.py` (PyYAML real, ACL normalizadas a (recurso, patternType, operaciones)) valida el conjunto contra la topología y el catálogo de esquemas: sin ACL de tipo cluster, tópicos solo `literal` (prefix solo en grupos esperados), sin nombre `*`, operaciones solo `Write`/`Read`/`Describe`, `Read` solo sobre lo que el servicio escucha, `KafkaUser` con `tls` y `simple`, y `cluster.yaml` solo con listeners `tls` + autenticación `tls`, `authorization: simple`, sin `superUsers`, con `auto.create.topics.enable: "false"` y `allow.everyone.if.no.acl.found: "false"`. Su `--self-test` aplica al menos una mutación por cada regla y falla si alguna regla no tiene mutación.
- Reemplaza el tópico `dominio.documentos` y la nota del ADR 0028 que lo mantenía para `calidad.muestra_ciega_solicitada`.

## Alternativas consideradas
- Firmar los eventos (JWS por productor) y validar la firma en el consumidor: añade gestión de llaves por servicio y latencia; la ACL de Kafka ya da el aislamiento y la validación de tópico es suficiente para este modelo de amenaza. Queda como refuerzo futuro.
- ACL por prefijo de clave o por cabecera: Kafka no autoriza por contenido del mensaje.
- Un solo tópico y solo validación en el consumidor: no impide que el servicio comprometido inunde o falsee el tópico ni que otros consumidores confíen en él.
- Un tópico por eventType: multiplica tópicos y ACL sin beneficio; la familia por productor da la misma garantía de `Write` exclusivo.
- Tópicos por tenant: ya descartado en el ADR 0003.

## Consecuencias
- Positivas: la unicidad del productor deja de ser documental; un servicio comprometido ya no puede emitir `revision.completada`, `extraccion.aprobada` ni muestras ciegas; el orden por documento se conserva dentro de cada tópico (clave `documentId`).
- Negativas o costos: más tópicos y particiones (incluidos cuatro `*-dlt`); el orden causal entre tópicos distintos no está garantizado (los consumidores ya eran idempotentes y dependen de estado, no de orden entre tópicos); un consumidor se suscribe a varios tópicos; hay que mantener la topología sincronizada con los esquemas (lo valida CI).
- Riesgo residual: un servicio comprometido puede seguir emitiendo señales falsas en `auditoria.senales` (excepción documentada, sin efecto sobre estado ni control); un productor legítimo comprometido puede falsear los eventos de SU propio tópico (p. ej. `extraction-service` el contenido de `extraccion.completada`), mitigado por las verificaciones cruzadas del consumidor pero no eliminado; los DLT son escribibles por los consumidores de su tópico.

## Controles de seguridad relacionados
SEC-052 (nuevo), SEC-053 (nuevo), SEC-050, SEC-051, SEC-009, SEC-039
