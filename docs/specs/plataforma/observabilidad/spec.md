# Especificación: Observabilidad Integral

## 1. Propósito
Proporcionar visibilidad unificada del comportamiento, rendimiento y estado de salud de todos los componentes del IDP. Garantizar la capacidad de diagnosticar problemas rápidamente, medir el cumplimiento de los SLOs y mantener trazas distribuidas completas sin exponer información confidencial (PII) en los sistemas de telemetría.

## 2. Alcance y No Alcance
**Alcance:**
* Instrumentación de trazas distribuidas (OpenTelemetry) en todos los microservicios Java.
* Emisión de métricas estandarizadas (tráfico, latencia, errores, saturación - RED/USE).
* Centralización de logs estructurados.
* Definición de Alertas y tableros base para medición de SLOs.

**No Alcance:**
* Monitoreo de infraestructura física (nodos bare-metal), asumido por el proveedor de nube o equipo de infra base.
* Procesamiento analítico de negocio complejo (eso va a un data warehouse, no al sistema de observabilidad).

## 3. Requisitos Cubiertos
* **RNF-102:** Disponibilidad multi-cloud. El estándar de observabilidad (OTel) garantiza portabilidad de telemetría sin acoplamiento a herramientas específicas de un proveedor.
* **RNF-103:** Cuotas y Rate Limit. Monitoreo visual y alertas basadas en el consumo de cuotas.

## 4. Reglas
1. **Estándar Único:** Toda la instrumentación (logs, trazas, métricas) debe realizarse utilizando OpenTelemetry.
2. **Cero PII (Regla de Oro):** Absolutamente ningún dato de identificación personal (nombres de deudores, montos de embargo, IDs de cuentas) debe imprimirse en logs, métricas o etiquetas de trazas.
3. **Propagación de Contexto:** El `traceId`, `spanId` y `tenantId` deben inyectarse y extraerse en todos los límites de red (HTTP headers, Kafka headers).
4. **Logs Estructurados:** Todos los logs deben emitirse en formato JSON conteniendo los atributos estándar de contexto (`traceId`, `tenantId`, `timestamp`).

## 5. Contrato
* **Formatos de Exportación:**
  * Métricas, Trazas y Logs emitidos vía protocolo OTLP (OpenTelemetry Protocol) sobre gRPC al OpenTelemetry Collector.
* **Contexto HTTP/Kafka:**
  * Inyección/Extracción basada en el estándar W3C Trace Context (`traceparent`, `tracestate`) y encabezados customizados (`X-Tenant-ID`).

## 6. Modelo de Datos
La observabilidad no define un modelo transaccional en PostgreSQL, sino un esquema lógico de atributos para telemetría:
* **Métricas Principales:**
  * `http.server.requests` (Tags: `method`, `status`, `uri`, `tenant_id`)
  * `kafka.consumer.processing` (Tags: `topic`, `partition`, `tenant_id`)
  * `idp.extraction.confidence` (Gauge, métricas calibradas del motor)
* **Atributos de Traza Obligatorios (Span Tags):**
  * `net.peer.name`, `http.status_code`, `messaging.system`, `tenant_id`, `correlation_id`.

## 7. Controles de Seguridad
* **SEC-050 (Evasión de PII en telemetría):** Implementación de filtros en el OpenTelemetry Collector o configuraciones de logger en Spring Boot que enmascaren automáticamente patrones que parezcan números de cuenta bancaria o tarjetas de crédito antes de salir del clúster.
* **SEC-AUTH-OTLP:** La comunicación entre los agentes/microservicios y el colector de OpenTelemetry debe estar asegurada por mTLS.

## 8. Escenarios de Aceptación

* **AC-01 [Trazabilidad HTTP]:** Given una petición HTTP que atraviesa API Gateway y un microservicio backend, When la respuesta es entregada, Then ambos componentes registran spans que comparten el mismo `traceId` en el sistema de trazabilidad.
* **AC-02 [Trazabilidad Asíncrona]:** Given un evento producido hacia Kafka por el servicio A, When es consumido por el servicio B, Then la traza se propaga transparentemente a través de los headers del mensaje Kafka.
* **AC-03 [Privacidad en Logs]:** Given un error de procesamiento que incluye los datos de un oficio en memoria, When el error es logueado en nivel ERROR, Then los datos de PII son excluidos o enmascarados explícitamente en el JSON de salida.
* **AC-04 [Silos en Telemetría]:** Given múltiples tenants operando simultáneamente, When un operador consulta el tablero de métricas de negocio, Then puede filtrar de manera segura e independiente el tráfico y los errores utilizando la etiqueta `tenant_id`.
* **AC-05 [Métrica de Cuota]:** Given un tenant que alcanza el 80% de su límite de tasa, When ocurre el consumo de su cuota, Then se registra una métrica específica que dispara una alerta a los administradores del tenant.
* **AC-06 [Alertas SLO]:** Given que el error rate de la API de ingesta supera el 1% sostenido por 5 minutos, When el colector central procesa las métricas, Then se dispara automáticamente una alerta crítica al equipo de guardia.
* **AC-07 [Formato de Logs]:** Given el arranque de un microservicio, When emite mensajes de inicio, Then dichos mensajes se envían hacia el stdout (o colector local) en formato JSON estandarizado, sin texto plano multilinea.
* **AC-08 [Recuperación ante Caída del Colector]:** Given la caída temporal del OTel Collector destino, When los microservicios intentan enviar telemetría, Then aplican estrategias de retención en memoria y backoff exponencial, minimizando la pérdida de datos y sin degradar la respuesta al usuario.

## 9. Métricas y SLO
* **Métricas:**
  * Las propias de monitoreo de la plataforma de observabilidad: `otel.collector.dropped_spans`, `otel.collector.queue_size`.
* **SLO del Sistema de Observabilidad:**
  * Retención garantizada de trazas muestreadas: 14 días.
  * Disponibilidad del pipeline de ingestión OTLP >= 99.9%.

## 10. Dependencias
* **Infraestructura:** Despliegue de un OpenTelemetry Collector como sidecar o DaemonSet. Sistema destino de almacenamiento (ej. Jaeger, Grafana Tempo, Prometheus, Loki o SaaS).
* **Framework:** Spring Boot Actuator y Micrometer (con puentes hacia OTel).
