# Especificacion de Plataforma: Eventos Kafka

## Proposito
Estandarizar la publicacion y consumo de eventos asincronos garantizando entrega al menos una vez (at-least-once), ordenamiento parcial y proteccion de datos sensibles (PII).

## Alcance y no alcance
Alcance: Patron outbox transaccional, relay multi-tenant, idempotencia en consumidores, reintentos con backoff exponencial, DLT, versionado de esquemas, y patron claim-check.
No alcance: Sincronizacion en tiempo real via WebSockets a clientes finales.

## Requisitos cubiertos
RNF-105 (resiliencia basada en eventos, consumidores idempotentes), RF-601 (trazabilidad inmutable), RNF-101 (aislamiento por tenant).

## Reglas
1. Ningun evento debe contener informacion personal identificable (PII); se debe usar claim-check.
2. Todo evento debe publicarse primero en la tabla outbox de la transaccion de negocio.
3. Los consumidores deben ser idempotentes basandose en el correlationId o eventId.
4. Fallos en el consumo tras los reintentos permitidos iran a una Dead Letter Topic (DLT).

## Contrato
Eventos publicados: Esquemas JSON en `contracts/events/` con formato de sobre comun (eventId, eventType, occurredAt, tenantId, correlationId, payload).

## Modelo de datos
Base de datos de tenant: Tabla `outbox_events` (id, event_type, payload, status, created_at).
Base de datos de control: Tabla `consumed_events` para control de idempotencia (event_id, processed_at).

## Controles de seguridad
SEC-050 Patron Claim-Check para evitar filtracion de PII en topics de Kafka.
SEC-014 Cifrado en tránsito (TLS) y autenticación mTLS de los KafkaUser de Strimzi con los brokers.

## Escenarios de aceptacion

### AC-01 Publicacion transaccional Outbox
Given una operacion de negocio que modifica estado y emite un evento
When la transaccion se hace commit
Then el evento se persiste en la tabla outbox del tenant correspondiente.

### AC-02 Relay Outbox a Kafka
Given eventos pendientes en la tabla outbox
When el proceso de relay se ejecuta
Then lee los eventos, los publica en Kafka y los marca como procesados.

### AC-03 Procesamiento idempotente
Given un evento E1 que ya fue procesado con exito
When el sistema vuelve a recibir E1 debido a un reintento de entrega
Then el consumidor ignora el evento silenciosamente revisando el registro de idempotencia.

### AC-04 Propagacion de contexto de Tenant
Given la publicacion de un evento en el outbox del tenant T1
When el mensaje se publica en Kafka
Then el mensaje incluye el tenantId T1 en las cabeceras (headers) de Kafka.

### AC-05 Envio a DLT tras reintentos fallidos
Given un consumidor que falla repetidamente al procesar un evento
When se agotan los 3 reintentos con backoff
Then el mensaje se mueve automaticamente a la Dead Letter Topic (DLT) correspondiente.

### AC-06 Validacion de JSON Schema
Given un payload de evento
When se intenta publicar o consumir
Then el componente de integracion valida que cumpla estrictamente con el esquema registrado.

### AC-07 Patron Claim Check
Given un evento que notifica la extraccion de un documento con datos sensibles
When el evento es generado
Then el payload contiene solo un URI/Referencia al recurso protegido, sin PII.

### AC-08 Manejo de errores temporales vs fatales
Given un evento recibido por un consumidor
When ocurre un error temporal (ej. base de datos inaccesible)
Then el sistema reintenta; When ocurre un error fatal (ej. JSON malformado) Then el sistema envia directo a DLT sin reintentos.

## Metricas y SLO
SLO: 99.9% de eventos del outbox publicados en menos de 5 segundos.
Metricas: Lag del consumidor, tasa de reintentos, mensajes enviados a DLT, tiempo en outbox.

## Dependencias
Cluster de Apache Kafka.
Base de datos relacional para tabla Outbox.
