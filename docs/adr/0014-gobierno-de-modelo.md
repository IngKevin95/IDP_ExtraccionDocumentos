# 0014. Gobierno de modelo

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
En el contexto del IDP bancario para extracción de oficios de embargo, la utilización de modelos de lenguaje grandes (LLMs) multimodales introduce un riesgo sistémico. Las instituciones financieras están sujetas a regulaciones estrictas respecto a los modelos algorítmicos que toman decisiones o procesan información crítica. El rendimiento de un modelo puede degradarse con el tiempo (model drift), y una actualización del proveedor del modelo base o una modificación menor en el prompt estructurado puede alterar significativamente las tasas de precisión, recall o introducir alucinaciones en campos críticos. 
Es indispensable contar con un mecanismo determinístico y auditable para gobernar las versiones exactas del par modelo-prompt utilizadas, evaluar cuantitativamente cualquier cambio antes de su paso a producción y monitorear su fiabilidad continua en tiempo real mediante un conjunto de datos validado o *golden set*.

## Decisión
Implementamos un motor estricto de gobierno de modelo gestionado a través de un servicio independiente (`quality-service`). La decisión técnica contempla las siguientes reglas invariantes:
1. Inmutabilidad del par Modelo-Prompt: Cualquier modificación en las instrucciones del prompt o cualquier cambio en la versión del LLM subyacente (utilizando siempre la versión del modelo vigente homologado) se registra como una nueva versión inmutable en la base de datos.
2. Calibración obligatoria mediante Golden Set: Antes del despliegue en producción, toda nueva versión de modelo-prompt debe ejecutarse contra un conjunto de oficios exclusivamente sintéticos (nunca contenido real con PII) que abarcan todos los escenarios límite y tipologías. 
3. Umbrales en cascada: Se aplican dos pasadas en cascada con umbrales `τ_revisar` y `τ_auto`, calibrados matemáticamente mediante regresión isotónica o escalado de Platt para garantizar que la tasa de error esperada esté por debajo del límite de tolerancia acordado.
4. Muestreo ciego en producción y regla de cuatro ojos: El sistema enrutará de manera aleatoria un porcentaje de las extracciones auto-aprobadas hacia revisores humanos. Para campos críticos (monto, identificación, cuenta, medida), cualquier corrección exige siempre la regla de cuatro ojos (aprobación de un segundo revisor distinto).
5. Registro inmutable (SEC-049): Se mantiene un registro inmutable y firmado criptográficamente del conjunto exacto de prompt + configuración + versión de modelo usado por cada extracción/respuesta.

## Alternativas consideradas
- Pruebas manuales ad-hoc antes de despliegues: Se descartó por la variedad de formatos de oficios.
- Auto-evaluación con otro modelo (LLM as a Judge) como único gate: Se rechazó como filtro de despliegue principal ya que los modelos evaluadores también sufren desviaciones. El enfoque *golden set* sintético es el único válido.
- Configuración dinámica del prompt sin versionamiento: Se descartó por ser una vulnerabilidad inaceptable.

## Consecuencias
- Positivas: Previsibilidad total en la calidad de extracción. Aprobación segura y transparente.
- Negativas o costos: Costos computacionales elevados al momento de la integración continua. Exige mantener el `tools/synthetic-oficios` perpetuamente actualizado.

## Controles relacionados
SEC-034, SEC-035, SEC-036, SEC-037, SEC-049