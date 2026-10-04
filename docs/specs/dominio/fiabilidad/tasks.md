# Tareas: Motor de Fiabilidad

- **T-01:** Diseñar DTOs de entrada (`SignalVector`: logprobs, grounding flag, validator states).
- **T-02:** Implementar `ReliabilityEngine` que procese las señales. (AC-06, AC-08)
- **T-03:** Programar la interfaz e impl. básica de calibración (`IsotonicCalibratorImpl`), alimentable con un modelo pre-entrenado estático o JSON. (AC-01)
- **T-04:** Implementar la lógica de partición en 3 tramos de umbral con cruce sobre `τ_auto` y `τ_revisar`. (AC-03, AC-05)
- **T-05:** Construir el `CascadeTrigger` que orquesta si un campo amerita segunda pasada antes de marcar HITL. (AC-02)
- **T-06:** Implementar inyector de Muestreo Ciego con lanzador probabilístico determinista configurable (ej. 5% de autos-aprobados van a QualityService). (AC-04)
- **T-07:** Escribir el script de Gate CI (Test de integración) que evalúa el ECE y F1 del golden set contra el motor. (AC-07)
- **T-08:** Crear la migración para `extraction_confidence_log` y repositorio asociado.
