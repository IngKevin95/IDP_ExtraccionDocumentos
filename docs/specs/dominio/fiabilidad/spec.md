# Especificación del Motor de Fiabilidad

## 1. Propósito
Calcular un puntaje de fiabilidad calibrado por campo y por documento completo, basándose en la salida del LLM (logprobs), los validadores determinísticos y la presencia de soporte (grounding), para tomar la decisión automatizada (auto-aprobar, ruteo a revisión humana, o rechazo).

## 2. Alcance y no alcance
**Alcance:**
- Combinación de señales probabilísticas y determinísticas.
- Modelos de calibración ligera (isotónica o escalado de Platt) ajustados por modelo LLM y prompt.
- Lógica de la segunda pasada en cascada para campos dudosos.
- Muestreo ciego (blind sampling) a producción para quality-service.

**No alcance:**
- Interfaz gráfica de revisión.
- Entrenamiento del modelo base LLM.

## 3. Requisitos cubiertos
- **RF-203**: Ruteo por Score (Rutea a APROBADO, cascada, o HITL).
- **RF-301**: Corrección acotada (Revisión humana para dudosos).
- **SEC-035**: Gate de riesgo en CI (Muestreo continuo y métricas contra golden set).

## 4. Reglas
- La decisión sobre un campo usa tres tramos definidos por los umbrales de la tipología (ej: `τ_auto`, `τ_revisar`):
  - Score >= `τ_auto`: Auto-aprobado.
  - `τ_revisar` <= Score < `τ_auto`: Va a HITL (Human In The Loop). Si el modelo soporta cascada, primero se dispara la cascada.
  - Score < `τ_revisar`: Falla o fuerza cascada obligatoria.
- La cascada intenta resolver la duda usando otro prompt o modelo; si persiste en franja de duda, va a HITL.
- Las probabilidades puras del LLM (logprobs) NO se usan crudas; deben pasar por calibración Isotónica/Platt específica de ese modelo y tipo de campo.

## 5. Contrato
- **Eventos Publicados (por ExtractionService usando esta lógica):**
  - `extraccion.aprobada.v1`: Aprobación total automática.
  - `extraccion.requiere_revision.v1`: Al menos un campo quedó en franja dudosa.
- **Eventos Consumidos:** Ninguno directamente por este motor de cálculo.

## 6. Modelo de datos
Base de datos: **Tenant** (guardado como metadato del oficio).
- Tabla `extraction_confidence_log`: Guarda las señales de entrada y el cálculo calibrado final por documento para su reconstrucción en caso de auditoría, separando cada campo.

## 7. Controles de seguridad
- **SEC-050 (Aislamiento PII):** Los logs de fiabilidad y muestreo que se manden al `quality-service` aplican claim-check; el evento no tiene datos, solo un ID transaccional protegido.

## 8. Escenarios de aceptación

- **AC-01 (Calibración exitosa):** Given un campo con logprob=0.9 pero validador=FAIL, When se calibra la señal conjunta, Then el score penaliza fuertemente a 0.2 (debajo de `τ_revisar`).
- **AC-02 (Cascada resolutiva):** Given un score en franja de duda (0.8 con `τ_auto=0.9`), When el sistema de cascada pide re-ejecución, Then el segundo intento obtiene score calibrado 0.95 y aprueba automáticamente.
- **AC-03 (HITL inevitable):** Given que el score inicial y el de cascada quedan en zona de duda (0.8), When se evalúa el documento final, Then el campo es marcado con flag `requiresReview=true`.
- **AC-04 (Muestreo ciego):** Given un oficio auto-aprobado exitosamente, When la función de muestreo lanza el dado (ej. 5% tasa), Then se levanta una copia anónima hacia la cola de evaluación manual de calidad en `quality-service`.
- **AC-05 (Dependencia de umbrales):** Given una tipología EC donde un campo crítico exige `τ_auto=0.99`, When la calibración arroja 0.98, Then ese campo específico debe derivarse a HITL (con regla de 4 ojos).
- **AC-06 (Evidencia obligatoria):** Given una respuesta del LLM sin caja delimitadora de grounding o cita exacta en el documento, When se procesa la fiabilidad, Then el score del campo decrece obligando una cascada y alertando "Ausencia de Evidencia".
- **AC-07 (Gate de CI):** Given un pipeline de CD, When se valida el golden set pre-despliegue con el nuevo modelo de calibración Platt, Then el gate requiere que las métricas (F1 y calibración ECE) sean iguales o superiores al baseline actual.
- **AC-08 (Validación sin estado LLM):** Given que el LLM base omitió mandar logprobs, When se evalúa fiabilidad, Then el motor se apoya 100% en el grounding + validadores determinísticos degradando su confianza base.

## 9. Métricas y SLO
- Latencia de cálculo del score: < 10ms (es matemático puro).
- ECE (Expected Calibration Error) del modelo en producción < 0.05.

## 10. Dependencias
- Módulo `validadores`.
- Metadatos de la tipología (para `τ_auto` y `τ_revisar`).