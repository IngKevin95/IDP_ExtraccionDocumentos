# ADR 0013: Estructura de Revisión Humana y Regla de Cuatro Ojos (Four Eyes)

## Estado
Aprobado

## Contexto
Cuando un documento no cumple con el umbral automático de confianza (τ_auto) del motor de IA (ADR 0012), o su tipología exige un nivel de supervisión extremo (Altamente Confidencial), el sistema debe retener el documento en estado `EN_REVISION`. La intervención humana introduce riesgos de error por cansancio, digitación o manipulación maliciosa. Dada la criticidad de un embargo bancario, es vital estructurar reglas que eviten que un solo operador tenga la potestad unilateral de ejecutar un embargo erróneo, mitigando riesgos operativos y de fraude interno.

## Decisión
Se implementa un flujo de supervisión estructurado en la plataforma, gobernado por la **Regla de Cuatro Ojos por Campo y Tipo de Documento**.

1.  **Regla General de Cuatro Ojos (Correcciones Críticas):** Si en un oficio, un campo clasificado como **CRÍTICO** (ej. monto a embargar, número de identificación tributaria o personal, cuenta/producto, y tipo de medida) es modificado o corregido respecto a la extracción original de la IA, esa corrección exigirá **obligatoriamente** la aprobación de un segundo revisor humano (un usuario distinto al que operó la modificación inicial).
2.  **Manejo de Campos Dudosos sin Corrección:** Si el documento fue derivado a revisión (ej. score de 95% para un umbral de 98%) pero el primer operador humano determina que el LLM acertó y valida el dato **sin modificarlo**, el flujo se satisface con ese único revisor para agilizar operaciones.
3.  **Clasificación de Información (Altamente Confidencial):** El sistema opera bajo taxonomías: Público, Interno, Confidencial (oficios estándar), y Altamente Confidencial. Si un documento posee una marca de "Altamente Confidencial", su proceso exigirá la intervención explícita y aprobación final de un usuario con el rol de **Data Steward**, sin importar el nivel de confianza de la IA. Este Data Steward debe ser, obligatoriamente, un usuario distinto de aquel que cargó el documento en la plataforma.
4.  **Trazabilidad Inmutable:** Cada interacción humana en la revisión (clics, validaciones sin modificación, teclas presionadas en correcciones) generará eventos operativos enviados a auditoría y a `quality-service` para la mejora continua y calibración.

## Alternativas Consideradas

*   **Doble Revisión Ciega Obligatoria para Todos los Campos:** Descartado. Forzar que dos operadores independientes digiten manualmente todos los datos (como en los BPOs tradicionales) para que el sistema compare, anula los ahorros operativos de usar inteligencia artificial, y satura los equipos de back-office con dobles trabajos innecesarios cuando el LLM tiene alta certidumbre.
*   **Permitir Aprobación de Corrección al Mismo Supervisor (Rol Híbrido):** Descartado de plano. Permitir que un usuario ostente tanto el rol de Maker (operador que edita) como de Checker (supervisor que aprueba) sobre la misma transacción en curso es una violación frontal a las directrices de Segregación de Funciones exigidas por la regulación bancaria (circular básica contable/financiera).

## Consecuencias
*   **Positivas:** Reducción drástica del riesgo operativo y de fraude interno asociado a la manipulación de valores de embargo. Cumplimiento holgura con las auditorías de procesos de la banca. Enrutamiento dinámico minimiza el costo laboral al exigir doble verificación solo donde matemáticamente el riesgo de error humano o discrepancia supera el beneficio de la velocidad.
*   **Negativas / Riesgos:** Aumenta la complejidad del orquestador del front-end o la capa API que sirve las bandejas, requiriendo lógica de bloqueo de registros para evitar concurrencias indeseadas. Retrasa la disponibilidad final de los datos en casos atípicos o de modificaciones profundas al documento, impactando los SLAs (Service Level Agreements) si no existe suficiente personal disponible con roles diferenciados.

## Controles de Seguridad Aplicables
*   **SEC-014:** Segregación de Funciones (Segregation of Duties - SoD). Implementado rígidamente en el flujo de aprobación dual (Cuatro Ojos y requerimiento de Data Steward distinto al creador).
*   **SEC-008:** Rastro inmutable en auditoría para toda intervención y aprobación humana dentro del pipeline (WORM).
*   **SEC-037:** Autorización estricta por rol (ej. Data Steward para acceso a material Altamente Confidencial) validado síncronamente antes de la carga de la tarea de revisión (ADR 0010).