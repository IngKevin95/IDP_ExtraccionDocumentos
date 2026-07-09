# ADR-012: Tamaño mínimo de muestra para latencia p95 confiable

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-013 (ver latencia p95 de procesamiento por dominio) dejaba ambiguo el tamaño mínimo de muestra necesario para que un p95 sea estadísticamente confiable. El usuario aceptó la opción recomendada: mínimo 20-30 documentos; por debajo de ese umbral, marcar el valor como "insuficiente" en vez de mostrarlo como confiable.

## Decisión

El KPI de latencia p95 por dominio requiere un mínimo de 20-30 documentos procesados en la ventana de cálculo. Por debajo de ese mínimo, el sistema marca explícitamente el valor como **"insuficiente"** en vez de reportar un p95 numérico que induciría falsa confianza.

## Consecuencias

- Un dominio recién activado no muestra p95 confiable hasta acumular volumen suficiente — consistente con el mismo principio de honestidad de dato aplicado en ADR-011 (precisión sin golden dataset).
- La UI/API de observabilidad de KPIs (EP-005) debe distinguir explícitamente el estado "insuficiente" del estado "p95 calculado", nunca mostrar un número sin ese contexto.
- El umbral 20-30 es un valor de arranque razonable, no una constante estadísticamente derivada — puede ajustarse por dominio si la operación real lo justifica, fuera de alcance de este ADR.
