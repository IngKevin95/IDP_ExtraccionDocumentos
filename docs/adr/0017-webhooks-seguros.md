# 0017. Webhooks seguros

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
Para mantener informados a los sistemas core del banco sobre el cambio de estado de los oficios, el `notification-service` realiza llamadas salientes vía Webhooks. Los webhooks son un vector de ataque clásico para intentos de Server-Side Request Forgery (SSRF), donde un atacante podría explorar metadatos o redes privadas. Los clientes también requieren certeza de que los mensajes provienen legítimamente del IDP.

## Decisión
Todo el tráfico saliente desde el clúster para notificaciones estará gobernado por políticas estrictas en el `notification-service`:
1. Anti-SSRF profundo y DNS Pinning (SEC-027): Bloqueo explícito de resolución DNS hacia rangos privados (RFC 1918), loopback, link-local 169.254/16, CGNAT 100.64/10, IPv6 ULA y metadatos. Se resuelve el DNS una única vez y se conecta a esa IP fija (DNS pinning) para mitigar vulnerabilidades TOCTOU (DNS rebinding). Redirecciones HTTP deshabilitadas.
2. Allowlist de dominios: Configuración por tenant de los dominios receptores permitidos.
3. Forzado estricto de HTTPS: Validación obligatoria de certificados X.509 públicos o de la CA del cliente.
4. Firmas Criptográficas (HMAC): El cuerpo y el timestamp se firman (`X-IDP-Signature`) mediante un secreto rotativo por tenant para evitar ataques de repetición (anti-replay). Existen dos secretos activos simultáneamente durante las ventanas de rotación.
5. Eventos de Trazabilidad: Publicación estricta de eventos `webhook.entregado` y `webhook.fallido` hacia el servicio de auditoría, despojados de PII.

## Alternativas consideradas
- Exponer colas Kafka o RabbitMQ a los clientes: Abre puertos y acopla tecnologías.
- Proxies HTTP tipo Squid: Introducen saltos adicionales y pueden evadirse mediante redirecciones avanzadas si no se aplica pinning a nivel aplicativo.
- Polling extensivo desde clientes: Consume recursos masivos innecesarios.

## Consecuencias
- Positivas: Riesgo de SSRF neutralizado, protegiendo infraestructura vital. Cumplimiento robusto con los requisitos de integración bancaria.
- Negativas o costos: Mantenimiento del bucle de resolución DNS seguro en Java exige precauciones, desactivando redirecciones automáticas en el cliente HTTP. Gestión de ciclo de vida de los secretos HMAC.

## Controles relacionados
SEC-027, SEC-028