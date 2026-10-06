# 0027. Cliente HTTP saliente de webhooks: Apache HttpClient 5

Estado: Aceptada
Fecha: 2026-10-05

## Contexto
El plan del `notification-service` proponía `WebClient` (Reactor Netty) con un resolvedor DNS propio para aplicar el DNS pinning de SEC-027 (ADR 0017). El servicio es la única identidad con salida a internet, despacha pocos envíos por segundo por tenant y necesita garantías verificables: resolver el nombre una sola vez, validar todas las IP devueltas, conectar a esa IP conservando SNI y verificación de certificado por nombre, no seguir redirects y acotar el tiempo total por envío.

## Decisión
Se usa Apache HttpClient 5 (versión gestionada por el BOM de Spring Boot) con:
- `DnsResolver` propio (`SsrfGuard`): resuelve una vez por conexión, rechaza el destino si cualquier registro cae en un rango prohibido y devuelve las direcciones validadas; el cliente conecta a ellas sin nueva consulta DNS.
- Redirects, reintentos automáticos, cookies, proxy y descompresión deshabilitados; sin reutilización de conexiones (cada envío vuelve a resolver y validar, de modo que un rebinding entre envíos no tiene efecto).
- TLS 1.2+ con verificación de certificado y nombre del cliente por defecto; plazo total por envío (watchdog) y timeouts de conexión y lectura; la respuesta no se lee (solo el código HTTP).
- La política de direcciones (`AddressPolicy`) y la resolución (`HostResolver`) son inyectables para probar con DNS falso; la configuración de producción usa siempre la política estricta y el http plano solo se admite con `idp.security.dev-mode=true`.
El envío es síncrono dentro de un worker planificado con reclamo `FOR UPDATE SKIP LOCKED`, fuera de transacciones largas.

## Alternativas consideradas
- WebClient con `AddressResolverGroup` de Netty: válido, pero el resolvedor asíncrono de Netty y su cache complican la garantía de resolución única y la validación de todos los registros, y arrastra la pila reactiva sólo para un cliente de bajo volumen.
- `java.net.http.HttpClient`: no permite inyectar el resolvedor ni fijar la IP sin romper SNI/verificación del certificado.
- Proxy de salida (Squid): ver ADR 0017.

## Consecuencias
- Positivas: el pinning es una propiedad del cliente, verificable con pruebas unitarias y de integración sin red; menos dependencias (sin WebFlux).
- Negativas o costos: envíos secuenciales por lote (mitigado con timeouts y tamaño de lote configurable); sin reutilización de conexiones (coste de handshake aceptable al volumen esperado).

## Controles relacionados
SEC-027, SEC-028, SEC-050
