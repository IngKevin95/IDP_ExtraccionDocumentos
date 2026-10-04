# ADR 0010: Gestión de Identidad y Federaciones con Keycloak

## Estado
Aprobado

## Contexto
El IDP es una plataforma B2B consumida por múltiples entidades financieras. La plataforma no contará con un frontend propio para los usuarios finales de los bancos, ya que se integrará vía API. Sin embargo, requiere manejar la autenticación, propagación de identidad y federación con los múltiples Proveedores de Identidad (IdP) corporativos de los bancos (como Azure AD o PingIdentity), validando rigurosamente que las peticiones se correspondan con los accesos permitidos sin mantener información de contraseñas locales (Zero Knowledge de contraseñas).

## Decisión
Se establece **Keycloak** como el Identity Broker y proveedor OIDC (OpenID Connect) central en todos los destinos (Cloud y On-Premise).

1.  **Federación OIDC con IdPs de Bancos:** Keycloak se configurará para actuar como puente (broker). Las identidades de los usuarios bancarios residirán en sus propios IdP corporativos. La plataforma del IDP confiará en Keycloak, y Keycloak establecerá relaciones de confianza (federación) con cada IdP bancario.
2.  **Claims Extendidos (Tenant y Rol):** Durante el proceso de emisión del token JWT hacia los servicios externos/internos de la plataforma, Keycloak inyectará atributos obligatorios en el token (Claims): `tenant_id` y roles fundamentales (si es provisto). Este JWT es el que el Gateway valida de manera stateless (ADR 0005).
3.  **Autorización Descentralizada Continua:** Aunque Keycloak emite el token con el rol nominal, el control fino de autorización NO recae en el Gateway ni confía ciegamente en la expiración prolongada del token.
    *   **Revalidación Local (Librería Security):** Cada microservicio (ej. `document-service`, `chat-service`), interceptará el JWT. Utilizando la librería base `libs/security`, consultará una caché local rápida (máximo 30 segundos de TTL).
    *   **Base de Control de Autorización:** Si la caché falla, consultará sincrónicamente la base de control maestra del sistema (tabla `role_assignment` gestionada por el `tenant-service`) para certificar que el acceso del usuario al tenant y sus roles siguen vigentes en ese mismo milisegundo.
    *   **Revocación Inmediata:** Si un administrador revoca un permiso, el `tenant-service` actualizará la base de control y emitirá el evento de broadcast `acceso.revocado`, que purgará instantáneamente las cachés locales de los microservicios.
4.  **Trazabilidad de Negativas:** Cualquier fallo de autorización interno resultará en la publicación del evento obligatorio `seguridad.acceso_denegado`.

## Alternativas Consideradas

*   **Autorización Centralizada en el Gateway / Keycloak (RBAC perimetral):** Descartado. Confiar todo a Keycloak o al Ingress requiere un acoplamiento donde los componentes de red deben entender permisos granulares de negocio. Además, un JWT estándar con validez de horas no maneja revocaciones inmediatas requeridas por regulaciones bancarias si el empleado es despedido o su dispositivo comprometido.
*   **Desarrollo In-House de Servidor de Autenticación:** Descartado. Implementar desde cero OIDC, OAuth2, SAML (para federaciones complejas), gestión de llaves de firma (JWKS) y rotación introduce un riesgo de seguridad injustificable y tiempo de desarrollo masivo. Keycloak es un estándar de facto robusto y open-source.

## Consecuencias
*   **Positivas:** La federación permite a los bancos usar su propio MFA y políticas de contraseñas, aliviando a la plataforma de responsabilidades y riesgos de custodia de credenciales. La revalidación descentralizada garantiza que una revocación tenga efecto inmediato (en segundos), satisfaciendo normativas financieras críticas.
*   **Negativas / Riesgos:** Los microservicios sufren una ligera penalización de latencia (menos de 5ms en promedio) por la revalidación constante en la caché local o consultas a la base de control. La alta disponibilidad de la base de control del `tenant-service` y de la red de broadcast de invalidación es vital para evitar degradación.

## Controles de Seguridad Aplicables
*   **SEC-001:** Validación de Token JWT en perímetro (Gateway) y validación fina en destino.
*   **SEC-011:** Revocación de acceso en tiempo real; invalidación de sesiones propagada a las cachés locales vía eventos y consulta a la fuente de verdad.
*   **SEC-025:** Centralización de logs de seguridad, registrando cada denegación de acceso localmente a través de `seguridad.acceso_denegado`.
*   **SEC-012:** Certificación de accesos exportable (generado por `tenant-service` para revisiones trimestrales).