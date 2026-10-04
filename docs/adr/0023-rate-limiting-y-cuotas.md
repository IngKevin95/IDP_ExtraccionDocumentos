# 0023. Rate limiting y cuotas

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
En arquitecturas SaaS multi-tenant, las cargas computacionales asimétricas requieren defensas de estrangulamiento rigurosas. Sin límites, peticiones masivas o abusos pueden degradar el procesamiento de Kafka o agotar cuotas del LLM, generando el problema del vecino ruidoso.

## Decisión
Se implementa un modelo de estrangulamiento y cuotas estructurado:
1. Rate Limiting en Edge Gateway: El gateway intercepta el tráfico y aplica algoritmos rápidos para estrangular basándose en el JWT validado. Para esto, utiliza un clúster de Redis (compartido de plataforma y en configuración de Alta Disponibilidad) EXCLUSIVAMENTE para albergar contadores de rate limit (sin almacenar datos funcionales del tenant). Si Redis falla, el gateway degrada de forma segura mediante limitadores locales en memoria por réplica.
2. Equidad y Aislamiento por Tenant (Bulkhead): Para prevenir la monopolización de workers asíncronos y llamadas al LLM, se emplea el patrón Bulkhead mediante Resilience4j configurado con topes de concurrencia estrictos por tenant.
3. Publicación de Eventos y Cuotas: Cuando el inquilino excede umbrales (ej. 80%, 100%), se emite el evento normativo `cuota.umbral_alcanzado`. Todas las extracciones registran el evento de auditoría `consumo.registrado`.
4. Límites duros paramétricos: Restricciones innegociables de megabytes, páginas y DPI por archivo para prevenir el agotamiento de recursos físicos de los pods.

## Alternativas consideradas
- Delegar a IaaS/Ingress genérico WAF externo: Imposibilita la discriminación granular por metadatos del token JWT (tenant) acoplado a cuotas de negocio.
- Redis como almacén central multi-propósito: Rechazado; Redis solo maneja contadores efímeros para evitar fugas de información inter-tenant.
- Instancias de servicio por tenant: Violaba explícitamente la regla de arquitectura del plan maestro, penalizando la utilización elástica y los costos.

## Consecuencias
- Positivas: Autoprotección resiliente, asegurando la continuidad y equidad de la plataforma. Degradación controlada frente a caídas de la infraestructura de contadores.
- Negativas o costos: Orquestación de infraestructura compartida de caché de alta disponibilidad para el gateway y ajustes delicados del patrón Bulkhead para evitar rechazos en picos válidos.

## Controles relacionados
SEC-023, SEC-030