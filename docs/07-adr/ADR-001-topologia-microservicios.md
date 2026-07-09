# ADR-001: Topología de microservicios por capa lógica

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

El sistema abstrae 11 microservicios de referencia (DocFly) agrupados por funcionalidad en 4 capas: Ingesta, Procesamiento LLM, Consulta, Orquestación + Administración. El proyecto es de portafolio y debe demostrar una arquitectura escalable, segura y desacoplada — no un monolito ni un mapeo 1:1 de los 11 repos originales.

Alternativas consideradas:
- **Monolito modular**: menor complejidad operativa, pero no demuestra separación de responsabilidades a nivel de despliegue ni escalado independiente.
- **Microservicio por repo original (11 servicios)**: hereda la fragmentación de DocFly sin revisarla; varios de esos repos comparten responsabilidad real (ej. validación y encolado).
- **Microservicio por capa lógica (4 servicios)**: agrupa por responsabilidad funcional real, no por historia accidental del código de referencia.

## Decisión

Un microservicio por capa lógica:
1. **Ingesta** — recepción, validación fail-fast, resolución de config de dominio, encolado (EP-001).
2. **Procesamiento LLM** — extracción de campos, clasificación, extracción de tablas (EP-002).
3. **Consulta** — estado y resultado, publish/poll y push/webhook (EP-003).
4. **Orquestación + Administración** — configuración de dominio (EP-004) y observabilidad de KPIs (EP-005).

Comunicación asíncrona entre capas vía cola/bus de eventos (mecanismo concreto fuera de alcance de este ADR — se define en implementación, no amarra la decisión a un proveedor).

## Consecuencias

- Cada capa escala de forma independiente según su perfil de carga (Procesamiento LLM es previsiblemente el cuello de botella de cómputo, Consulta el de lectura).
- El límite entre capas es el límite de despliegue — un cambio en la capa de Procesamiento no requiere redesplegar Ingesta ni Consulta.
- Se pierde algo de simplicidad operativa frente a un monolito (4 servicios a desplegar y observar en vez de 1), aceptado como costo de demostrar la separación en el portafolio.
- EP-006 (piloto embargos) no introduce una capa nueva — consume las 4 existentes vía configuración, validando que la topología no está atada al dominio.
