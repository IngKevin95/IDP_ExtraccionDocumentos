# 0021. Observabilidad

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El ecosistema microservicios requiere trazabilidad en tiempo real sin sacrificar la confidencialidad. Es necesario unificar métricas, registros y trazas manteniendo agnosticismo frente a herramientas SaaS (ej. CloudWatch vs Prometheus) y garantizando que los datos de identificación personal (PII) no se fuguen hacia los sistemas centralizados.

## Decisión
Se estandariza sobre el protocolo abierto OpenTelemetry (OTel) para todo el ciclo de vida de la observabilidad.
1. Instrumentación Java sin acoplamiento: Se utilizará el agente nativo de OpenTelemetry para aplicaciones Spring Boot y Micrometer, exportando datos en formato estándar OTLP.
2. Agente Colector OTel: Los colectores se encargan de desacoplar los exportadores hacia los sistemas nativos del CSP o herramientas on-premise, ofreciendo agilidad.
3. Propagación de contexto y Seguridad en red: Trazabilidad de extremo a extremo usando `traceparent` (W3C). El enrutamiento interservicio prioriza mTLS apoyado en cert-manager de Kubernetes para securizar comunicaciones sin requerir infraestructuras pesadas como Istio o Linkerd.
4. Higienización obligatoria: El módulo `observability-lib` aplicará filtros para sanitizar los metadatos y detener registros con excepciones estructuradas que contengan PII (ej. identificadores, nombres).

## Alternativas consideradas
- Librerías propietarias como DataDog APM o NewRelic APM: Obligan a los clientes bancarios a adquirir licencias de herramientas que podrían chocar con sus estándares internos de SIEM (vendor lock-in).
- MDC rudimentario basado en logs planos: Ineficiente en arquitecturas distribuidas con colas asíncronas para rastrear de inmediato el flujo de la transacción original.
- Mallas de servicios complejas (Istio/Linkerd): Se rechazaron explícitamente para mantener una operación manejable, delegando la identidad criptográfica y encriptación de tráfico al cert-manager nativo con mTLS.

## Consecuencias
- Positivas: Transparencia total, garantizando que el banco conecte el IDP hacia su infraestructura de observabilidad ya aprobada. 
- Negativas o costos: La sobrecarga al sistema por instrumentación densa obliga a parametrizar políticas cuidadosas de muestreo (*sampling*) durante picos transaccionales.

## Controles relacionados
SEC-041, SEC-044