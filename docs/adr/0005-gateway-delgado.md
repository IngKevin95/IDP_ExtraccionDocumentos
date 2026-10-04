# ADR 0005: API Gateway Delgado y centralización de la seguridad perimetral

## Estado
Aprobado

## Contexto
La plataforma de Extracción de Documentos cuenta con múltiples microservicios que exponen interfaces al mundo exterior, a otros servicios internos y a componentes administrativos (frontend omitido según el alcance, pero asumiendo consumo por sistemas externos). Es necesario definir el modelo de exposición de las APIs, la gestión del enrutamiento y, críticamente, cómo se aplica el control de acceso, la limitación de tasa (rate limiting) y la protección contra ataques volumétricos sin acoplar lógicas de negocio al nivel de red.

## Decisión
Se implementará un **API Gateway "Delgado"** (usando Spring Cloud Gateway) posicionado como el único punto de entrada público. Sus responsabilidades serán estrictamente no funcionales y de seguridad perimetral, descargando de lógica de negocio a la capa de red.

1.  **Enrutamiento y Validación de JWT (Stateless):** El Gateway realizará la validación inicial del token JWT de acceso (firma contra la llave pública del IdP OIDC, expiración y emisor). No accederá a bases de datos ni almacenará sesiones estado. La validación de negocio (tenant, rol, permisos granulares) recae enteramente en cada microservicio interno de destino.
2.  **Rate Limiting Global Equitativo (Redis Compartido):** Para prevenir el abuso y garantizar equidad entre tenants (bulkhead), el Gateway aplicará políticas de límite de tasa. Este límite estará respaldado por un clúster de **Redis compartido de plataforma** en alta disponibilidad (HA con réplica). Es crucial destacar que este Redis **SÓLO** contendrá contadores estadísticos de peticiones (ej. claves tipo `ratelimit:{tenantId}:{endpoint}`), y jamás alojará datos de sesión, metadatos de usuario o PII. En caso de caída catastrófica de Redis, el Gateway caerá con gracia a un limitador de tasa local en memoria por réplica (degradación segura), priorizando la disponibilidad sobre la exactitud estricta del límite.
3.  **Auditoría y Exportación al SIEM:** El Gateway exportará directamente todos sus eventos de rechazo (HTTP 401 Unauthorized, 403 Forbidden, 429 Too Many Requests, intentos de payload masivo) hacia el colector central de logs (Fluent Bit/Vector) para su análisis en el SIEM corporativo.
4.  **Protección Anti-SSRF y Cabeceras:** El Gateway sanitizará las cabeceras HTTP externas y actuará como barrera primaria contra Server-Side Request Forgery.

## Alternativas Consideradas

*   **Gateway "Gordo" con lógica de autorización de tenant:** Descartado. Hacer que el Gateway consulte la base de datos de asignación de roles o `tenant-service` para verificar permisos específicos acopla un componente perimetral a la base de datos de control, convirtiéndolo en un cuello de botella de rendimiento y violando el principio de frontera de confianza (Zero Trust), donde cada microservicio debe desconfiar de la red y revalidar sus accesos.
*   **Service Mesh externo (ej. Istio) como Gateway público exclusivo:** Descartado para la capa de negocio HTTP inicial. Aunque internamente se utiliza cert-manager para mTLS, delegar todo el manejo de rate limit, manipulación de JWT y exportación SIEM a un Ingress Controller genérico o Envoy reduce la observabilidad nativa de Java y dificulta aplicar lógicas de limitación basadas en atributos específicos del claim del token de forma ágil, comparado con Spring Cloud Gateway.

## Consecuencias
*   **Positivas:** Separación clara de responsabilidades: el perímetro protege de avalanchas y accesos no autenticados; el microservicio protege los datos. La arquitectura es resiliente; si el Redis compartido cae, la plataforma sigue sirviendo tráfico bajo límites en memoria. La exportación directa al SIEM acelera la detección de ataques de fuerza bruta.
*   **Negativas / Riesgos:** Los microservicios reciben tráfico con un JWT válido pero deben realizar el trabajo de resolver la autorización fina localmente (ver ADR de Identidad/Autorización). Requiere una configuración cuidadosa del TTL y sincronización del rate limit en memoria durante ventanas de degradación.

## Controles de Seguridad Aplicables
*   **SEC-002:** Rate Limiting por tenant soportado en Redis, para prevenir abusos (DoS) y asegurar equidad.
*   **SEC-001:** Validación centralizada y stateless de tokens JWT (firma y expiración) en el punto de entrada.
*   **SEC-025:** Centralización de logs de seguridad; volcado de rechazos (401/429) directamente al recolector de logs (SIEM) para monitoreo de actividad maliciosa perimetral.
*   **SEC-027:** Prevención Anti-SSRF. El Gateway sanitiza rutas y cabeceras antes de redirigir internamente.