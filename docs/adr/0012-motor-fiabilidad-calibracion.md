# 0012. Motor de Fiabilidad y Enrutamiento por Calibración

Estado: Aceptada
Fecha: 2026-09-29

## Contexto

El modelo de extracción multimodal directo (ADR-0011) introduce capacidades de comprensión visual para procesar oficios. Sin embargo, un banco no puede ejecutar bloqueos de cuentas ni transferencias coactivas basadas únicamente en la salida estadística de un LLM. El "Straight-Through Processing" (STP), es decir, la automatización que procesa sin intervención humana, requiere certeza. Un solo número de cuenta o monto de embargo erróneo causará bloqueos financieros incorrectos y contingencias de riesgo legal. Por lo tanto, el sistema requiere un escudo determinístico que audite al LLM antes de aprobar el oficio judicial.

## Decisión

El componente `extraction-service` implementará un **Motor de Fiabilidad Determinístico estructurado en tres defensas en cascada y un Score Calibrado (isotónica)** sobre un Golden Set.

1. **Señales Determinísticas (Reglas duras):** Todo campo extraído por el LLM será forzosamente auditado por validadores codificados en Java.
   - Longitudes y formatos (ej. Radicado de 23 dígitos obligatorios).
   - Coherencia financiera y aritmética (Monto de embargo numérico debe equivaler al extraído en letras de forma exacta; la sumatoria de valores individuales en las filas de la tabla debe empatar con la fila "Total").
   - Catálogos locales (El "Juzgado X" extraído debe existir y coincidir sintácticamente con un catálogo consolidado oficial del país).
   - NIT con dígito de verificación módulo 11; cédula de ciudadanía y extranjería SIN dígito de verificación (solo formato y longitud).
2. **Grounding Híbrido Inverso:** Si el archivo PDF original posee una capa de texto vectorial (así sea desordenada), las cifras críticas identificadas por el LLM (montos, C.C.) serán emparejadas obligatoriamente mediante coincidencia EXACTA tras normalización (sin Levenshtein) con los montos existentes nativos.
3. **Score Calibrado y Enrutamiento:** El sistema asignará un score de fiabilidad POR CAMPO calibrado (pasada en cascada con umbrales τ_revisar y τ_auto) al documento basándose en la aprobación de las señales y el historial estadístico validado por el `quality-service` contra oficios sintéticos. Enrutamiento en tres tramos:
   - Si `score ≥ τ_auto`: Auto-aprobación del campo (STP).
   - Si `τ_revisar ≤ score < τ_auto`: Segunda pasada en cascada (re-extracción con prompt ajustado o modelo secundario).
   - Si `score < τ_revisar` o un validador duro falla: Revisión humana obligatoria del campo en `review-service` (ADR-0013).

## Alternativas consideradas

- **Confianza absoluta en el LLM (Zero-shot blind trust):** *Por qué se descarta:* Conduce inevitablemente al error financiero (alucinaciones), lo cual es inaceptable en un IDP bancario transaccional crítico (viola SEC-032 y SEC-034).
- **Prompting repetitivo forzado (LLM-as-a-judge como validador único):** Preguntarle al LLM o a un modelo secundario: "¿Estás seguro de que extrajiste bien?". *Por qué se descarta:* Un LLM no puede certificar matemáticamente operaciones aritméticas grandes ni dígitos de verificación lógicos. Si alucinó una cuenta por un sello que lo tapaba, el LLM evaluador probablemente repetirá la misma inferencia. Los validadores determinísticos (código Java estricto) son robustos frente a modelos de lenguaje.

## Consecuencias

### Positivas
- **Contención de riesgo financiero y legal:** Ningún oficio con fallos matemáticos o campos inexistentes se ejecutará en las APIs del Core Bancario, asegurando cumplimiento regulatorio de veracidad y fidelidad de los datos (Ley 1266).
- **Escalado seguro:** Las tasas de procesamiento directo (STP) serán totalmente predecibles y auditables. Si un juzgado cambia de formato, el validador duro detectará la falla geométrica y ruteará a los humanos, sin causar un error silencioso de producción.
- **Evaluación objetiva de Modelos:** Provee una metodología matemática (Golden Set `tools/synthetic-oficios`) para medir científicamente si un salto a Java 25 o si la migración de AWS Nova a vLLM mejora o destruye las métricas del pipeline.

### Negativas o Costos
- **Rigidez al inicio:** Escribir, mantener y actualizar decenas de clases validadoras determinísticas de Java para casuísticas específicas de la rama judicial incrementa drásticamente el peso y esfuerzo inicial de codificación en el backend.
- **Tasa de rechazo falsamente inflada inicial:** Algunos oficios estarán extraídos perfectamente por el LLM, pero errores minúsculos de formato obligarán a la revisión manual, limitando la adopción y el STP durante las primeras iteraciones hasta sintonizar los umbrales ($τ$).

## Controles relacionados

- **SEC-032:** Fidelidad absoluta en cifras, montos y fechas extraídas sin redondeos.
- **SEC-034:** Control de alucinaciones mediante revisión humana forzada si score de calibración es bajo.
- **SEC-035:** Gate de riesgo en CI y golden sets determinísticos para bloquear despliegues regresivos.
