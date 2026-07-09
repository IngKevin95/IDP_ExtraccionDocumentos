# ADR-005: Reprocesamiento bajo demanda ante cambio de tipología de documento

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-001 (definir tipología de documento vía configuración YAML) dejaba ambigua la reacción del sistema cuando la config de un dominio cambia después de que ya existen documentos procesados con la versión anterior. Alternativas preguntadas al usuario: reprocesar automáticamente todo el histórico, no reprocesar nunca (solo aplica a documentos nuevos), o reprocesar bajo demanda. El usuario eligió explícitamente la tercera, no la recomendada.

## Decisión

El cambio de config de dominio **no** dispara reprocesamiento automático del histórico. Los documentos ya procesados conservan su resultado bajo la versión de config con la que fueron procesados. El reprocesamiento de un documento o lote específico bajo la nueva config requiere un trigger explícito (endpoint/acción manual), nunca implícito.

## Consecuencias

- Cambiar una config de dominio es una operación segura y de bajo impacto inmediato — no reprocesa nada en cascada ni consume cómputo LLM sin pedirlo.
- El sistema debe conservar la trazabilidad de qué versión de config produjo cada resultado, para que el reprocesamiento bajo demanda sea reproducible y comparable contra el resultado anterior.
- Queda como trabajo de implementación (no de este ADR) definir el endpoint/mecanismo concreto de reprocesamiento bajo demanda.
