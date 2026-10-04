# Especificación de Quality Service

## Propósito
Gestionar el ciclo de vida de la calidad del sistema de extracción (Riesgo de Modelo), evaluando la precisión (*accuracy*, *precision*, *recall*) y detectando deriva (*drift*) en el rendimiento de los LLMs. Garantiza una mejora continua y observabilidad de calidad sin exponer ni retroalimentar los modelos con información personal (PII) de los oficios reales.

## Alcance
* Consumo asíncrono de eventos de extracción y revisión para generar métricas comparativas (HITL vs. Extracción automática).
* Medición del "Error Silente" mediante la ingesta de resultados provenientes del muestreo ciego (1-2% del tráfico forzado a HITL).
* Gestión y evaluación de un *Golden Set* de oficios sintéticos ficticios para calibrar los *prompts* y umbrales.
* Exposición de API para el Data Steward que permite visualizar tableros de deriva, calibración de umbrales y exportación de reportes de calidad.

## No alcance
* Reentrenamiento o *fine-tuning* directo de modelos LLM.
* Procesamiento en caliente o intervención en la latencia del flujo de extracción E2E (fuera del *critical path*).
* Modificación de documentos reales ni almacenaje de datos personales (PII) extraídos.

## Requisitos cubiertos
* **RN-07**: Calidad del Golden Set (sin PII, uso de sintéticos).
* **KPIs**: Precisión y Recall, Tasa STP, Tasa HITL, Error Silente.
* **SEC-035**: Ambiente seguro de calibración.

## Reglas
1. **Aislamiento de PII:** Los eventos de `revision.completada` que ingresen al servicio se utilizarán exclusivamente para extraer metadatos de qué campos fallaron, la tipología y el delta de corrección (como tipo de error matemático o de formato), eliminando el valor en crudo.
2. **Umbrales Calibrados:** El servicio calcula estadísticamente los umbrales óptimos para auto-aprobación ($\tau_{auto}$) basados en la métrica histórica de error, permitiendo su consulta por los servicios core.
3. **Muestreo Ciego:** El sistema registrará métricas bajo la etiqueta `blind_sample` cuando identifique oficios reales que superaron $\tau_{auto}$ pero fueron forzados a revisión humana aleatoriamente, usando estas diferencias para hallar la tasa de Error Silente.
4. **Golden Set Independiente:** Las evaluaciones del *Golden Set* se ejecutan contra oficios sintéticos creados por los Data Stewards, permitiendo certificar nuevas versiones del sistema.

## Contrato

### Eventos Consumidos
* `extraccion.aprobada.v1`: Para registrar el éxito del procesamiento *Straight-Through Processing* (STP) e incrementar el denominador de la tasa STP.
* `revision.completada.v1`: Para calcular errores (campos corregidos vs propuestos) y alimentar la precisión y recall.

### Eventos Publicados
* Ninguno directamente. Las métricas se exponen vía API y tableros.

### Endpoints (API)
Referencia completa en: `contracts/openapi/quality-service.yaml`
* `GET /v1/quality/reports/stp`: Tasa STP general y por tipología.
* `GET /v1/quality/reports/silent-error`: Tasa de error silente.
* `GET /v1/quality/golden-set`: Administración de oficios sintéticos.
* `POST /v1/quality/golden-set/evaluate`: Dispara una corrida de evaluación sobre el modelo actual.

## Modelo de Datos

Las tablas residen en el esquema de la **Base de Control (Compartida)** dado que sus métricas consolidan el estado del modelo para toda la plataforma, pero agrupan por `tenant_id` de forma anónima para reportes comerciales.

* `qa_metrics_daily`: Almacena agregados diarios (tenant_id, fecha, tipologia, total_documentos, stp_count, hitl_count, silent_error_count).
* `qa_field_error`: Almacena frecuencia de error por campo (tenant_id, fecha, campo_modificado, tipo_correccion, cuenta).
* `golden_set_document`: (id, nombre, tipologia, payload_sintetico_json, tags).
* `golden_set_evaluation`: (id, fecha, version_prompt, accuracy, precision, recall).

## Controles de Seguridad

* **SEC-035 (Ambiente Seguro de Calibración):** El Golden Set está estrictamente desvinculado de bases de datos de tenants con datos reales.
* **SEC-005 (Mínimo Privilegio):** La API de métricas avanzadas y modificación del Golden Set está restringida mediante RBAC al rol `Data Steward`.
* **SEC-050 (Claim-Check en Kafka):** El servicio consume los eventos, pero ignora cualquier enlace de descarga del documento real, leyendo únicamente los metadatos de las correcciones de extracción.

## Escenarios de Aceptación

* **AC-01: Ingesta de revisión con corrección.**
  * *Given* un evento de `revision.completada.v1` donde se corrigió el campo "monto",
  * *When* el `quality-service` procesa el evento,
  * *Then* incrementa los contadores diarios de HITL y registra un error en `qa_field_error` para el campo "monto", ignorando el valor exacto del monto corregido.
* **AC-02: Registro de éxito STP.**
  * *Given* un evento de `extraccion.aprobada.v1` que no pasó por revisión,
  * *When* es ingerido por el servicio,
  * *Then* incrementa la métrica `stp_count` para la tipología y el tenant correspondientes.
* **AC-03: Muestreo ciego y Error Silente.**
  * *Given* un evento de `revision.completada.v1` marcado con `is_blind_sample = true`, donde hubo correcciones,
  * *When* es consumido,
  * *Then* el servicio lo categoriza como "Error Silente" y actualiza la tasa `silent-error` respectiva.
* **AC-04: Ejecución de Golden Set.**
  * *Given* un usuario Data Steward,
  * *When* invoca `POST /v1/quality/golden-set/evaluate`,
  * *Then* el servicio encola la petición y retorna un `202 Accepted`, evaluando los oficios sintéticos asíncronamente.
* **AC-05: Prevención de filtración de PII.**
  * *Given* un evento entrante con datos personales reales en el payload de campos crudos,
  * *When* el servicio extrae las estadísticas,
  * *Then* asegura que ninguna cadena de texto del documento original sea almacenada en la base de datos de métricas de calidad.
* **AC-06: Consulta de métricas por Data Steward.**
  * *Given* un token JWT con rol `Data Steward`,
  * *When* hace `GET /v1/quality/reports/stp`,
  * *Then* retorna la serie de tiempo `200 OK` con los promedios calculados.
* **AC-07: Acceso no autorizado a métricas.**
  * *Given* un usuario con rol `Operador`,
  * *When* intenta invocar `GET /v1/quality/reports/stp`,
  * *Then* recibe `403 Forbidden`.
* **AC-08: Alerta de deriva (Drift).**
  * *Given* un reporte consolidado diario,
  * *When* el porcentaje de Error Silente supera el 5% histórico o la tasa STP cae más de 10 puntos,
  * *Then* el servicio marca un flag de `drift_detected` en el resumen del día para el dashboard.

## Métricas y SLO

* **SLO:** Procesamiento asíncrono con retraso máximo de 5 minutos post-evento. (No incide en el *p95* del usuario).
* **Métricas Exportables:** Tasa STP general y por tenant, Precisión de campos, Volumen de documentos sintéticos evaluados.

## Dependencias
* **Kafka:** Para suscripción a los tópicos de extracción y revisión.
* **PostgreSQL (Base Compartida):** Persistencia de métricas y documentos del Golden Set.
* **LLM Provider (Opcional):** Si la evaluación del Golden Set requiere re-ejecutar *prompts* sintéticos de prueba.