# ADR 0004: Orquestación del pipeline de extracción basada en eventos

## Estado
Aprobado

## Contexto
El flujo principal de procesamiento en la plataforma requiere que cada documento transite por múltiples etapas: recepción, escaneo antivirus, renderizado, extracción mediante Inteligencia Artificial, revisión humana (si aplica) y eventual aprobación o rechazo. Es vital establecer qué componente gobierna el ciclo de vida del documento, cómo se propagan los cambios de estado y bajo qué modelo (coreografía o de orquestación) interactúan los servicios de la plataforma (`document-service`, `renderer`, `extraction-service`, `quality-service`), para mantener un rastro de auditoría claro y evitar flujos de control espagueti o cíclicos.

## Decisión
Se adopta un **Modelo de Orquestación Centralizado** donde el microservicio **`document-service` actúa como el orquestador exclusivo** del ciclo de vida del documento.

1.  **Estados Canónicos:** El documento transitará estrictamente por los siguientes estados canónicos, definidos como fuente de verdad:
    *   `RECIBIDO`: Documento cargado, pendiente de escaneo/renderizado.
    *   `RECHAZADO`: Documento inválido o detectado con malware.
    *   `RENDERIZADO`: Archivo sanitizado y convertido a formato interno (PNG/texto).
    *   `EN_EXTRACCION`: Extracción de datos con IA en curso.
    *   `EN_REVISION`: Retenido para validación manual por humanos (cuatro ojos).
    *   `APROBADO`: Datos extraídos verificados y listos para consumo.
    *   `FALLIDO`: Error irrecuperable en el pipeline.
2.  **Responsabilidades del Orquestador:** `document-service` es el único componente autorizado para publicar comandos hacia servicios de dominio y para actualizar el estado central del documento.
    *   Consume el evento interno de finalización del renderizado (`documento.renderizado`) y comanda al servicio de extracción emitiendo `extraccion.solicitada`.
    *   Consume los eventos resultantes del motor de IA (`extraccion.completada` o `extraccion.requiere_revision`).
    *   Consume eventos provenientes de calidad (`revision.completada`).
    *   Publica en solitario el evento final de éxito `extraccion.aprobada`.
3.  **Participantes Secundarios:** Los demás servicios operan de forma reactiva o bajo la instrucción directa de la orquestación. Por ejemplo, `quality-service` reacciona a `extraccion.aprobada` y `revision.completada` para cálculos de métricas y calibración del motor; `extraction-service` se activa con `extraccion.solicitada`.

## Alternativas Consideradas

*   **Coreografía pura (Event-Driven distribuida):** Descartado. En una coreografía pura, cada servicio escucharía eventos y decidiría por sí mismo su siguiente acción sin un controlador central. Dado que el flujo de procesamiento de documentos bancarios posee regulaciones estrictas y la necesidad de auditar trazablemente el estado (ej: para certificar si algo falló por malware o por timeout de IA), la coreografía incrementa la complejidad del rastreo distribuido y dificulta saber con exactitud "dónde" se encuentra atascado un proceso.
*   **Orquestador de procesos genérico (ej. Camunda o Temporal):** Descartado para la fase inicial. Incorporar un motor de orquestación BPMN o de flujos complejos añade una sobrecarga de infraestructura innecesaria, dado que el pipeline del IDP es fundamentalmente lineal (con una ramificación simple para revisión humana). Se resuelve eficientemente con una máquina de estados determinista y persistente dentro de la base de datos de `document-service`.

## Consecuencias
*   **Positivas:** La trazabilidad es absoluta. Para conocer el estado de un documento basta consultar la base de control del `document-service`. Simplifica drásticamente el manejo de compensaciones (Sagas) en caso de fallos, ya que el orquestador conoce el punto exacto de interrupción. Asegura que los eventos de auditoría (ej. rechazos por ClamAV) se centralicen de forma coherente.
*   **Negativas / Riesgos:** El `document-service` se convierte en un cuello de botella arquitectónico (single point of failure de dominio). Requiere ser escalado horizontalmente de manera preventiva y su base de datos experimentará un alto índice de lecturas/escrituras para actualizaciones de estado, requiriendo monitorización cuidadosa.

## Controles de Seguridad Aplicables
*   **SEC-008:** Trazabilidad de operaciones. Cada cambio en el estado canónico del documento dispara un evento hacia el bus, alimentando el registro de auditoría WORM (Write Once Read Many).
*   **SEC-050:** Cero PII en eventos Kafka. El orquestador publicará cambios de estado usando `documentId`, requiriendo que los consumidores autorizados utilicen el patrón Claim-Check.
*   **SEC-034:** Validación de integridad en el orquestador, garantizando que un documento no transite a `EN_EXTRACCION` si previamente fue marcado como `RECHAZADO` por el servicio antivirus.