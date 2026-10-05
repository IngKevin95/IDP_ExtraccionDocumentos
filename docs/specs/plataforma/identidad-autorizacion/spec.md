# Especificación de Plataforma: Identidad y Autorizacion

## Propósito
Gestionar la autenticacion de usuarios, validacion de tokens JWT y control de acceso basado en roles (RBAC) con integración a Keycloak como broker de identidad.

## Alcance y no alcance
Alcance: Integracion con Keycloak, validacion de tokens, extracción de claims, revalidacion con cache de 30 segundos, MFA obligatorio para roles criticos y acceso de emergencia (break-glass).
No alcance: Administracion de usuarios y creacion de pantallas de login (delegado al frontend y Keycloak).

## Requisitos cubiertos
SEC-002, SEC-006, SEC-008, SEC-010.

## Reglas
1. Todo endpoint privado requiere un JWT valido emitido por el broker de identidad.
2. El estado de revocacion del token o usuario debe validarse contra el broker, usando una cache local de maximo 30 segundos.
3. Operaciones destructivas o de configuración requieren validacion MFA en el token.
4. El acceso break-glass emitira alertas críticas inmediatas al equipo de seguridad.

## Contrato
Endpoints: Filtros de seguridad Spring Security, sin endpoints de negocio expuestos (Keycloak maneja el flujo OAuth2).
Eventos consumidos: `contracts/events/acceso.revocado.v1.schema.json`.

## Modelo de datos
Base de datos de control: No aplica directamente, confia en el broker de identidad. Registros de auditoria en tabla `audit_logs` (id, user_id, action, resource, timestamp, ip).

## Controles de seguridad
SEC-020 Autenticación delegada a proveedor OIDC.
SEC-008 Validación de revocación de sesión (caché corta).
SEC-002 Revalidación de rol (role_assignment) independiente del JWT.

## Escenarios de aceptacion

### AC-01 Acceso con JWT valido
Given una petición con un JWT activo y firma valida
When accede a un recurso protegido
Then el sistema permite el acceso y extrae el tenantId y roles.

### AC-02 Rechazo por JWT expirado o invalido
Given una petición con un JWT manipulado o caducado
When accede a un recurso protegido
Then el sistema responde con 401 Unauthorized.

### AC-03 Revalidacion de token desde cache
Given un usuario con JWT valido que hace múltiples peticiónes en 10 segundos
When el sistema valida el estado del usuario
Then utiliza la validacion de la cache local sin consultar a Keycloak.

### AC-04 Acceso denegado por evento de revocacion
Given un JWT valido en tiempo
When se recibe un evento acceso.revocado para ese usuario y la cache expira (30s)
Then la siguiente petición del usuario es rechazada con 401 Unauthorized.

### AC-05 Protección de endpoint sensible sin MFA
Given un endpoint que requiere el claim de MFA
When un usuario autenticado sin MFA intenta acceder
Then el sistema rechaza la petición con 403 Forbidden.

### AC-06 Procedimiento Break-Glass
Given una falla total del broker de identidad
When un administrador utiliza credenciales de emergencia aprovisionadas en frio
Then obtiene acceso a las APIs críticas y se dispara una alerta de maxima prioridad.

### AC-07 Aislamiento de tokens por tenant
Given un token emitido para el tenant T1
When el usuario intenta forzar la cabecera X-Tenant-ID a T2
Then el filtro de seguridad prioriza el claim del JWT e ignora la cabecera maliciosa.

### AC-08 Validación de firma RSA
Given un JWT firmado con una clave antigua o desconocida
When el sistema valida el token contra el JWKS
Then el acceso es denegado inmediatamente sin evaluar claims.

## Métricas y SLO
SLO: Validación de tokens en menos de 5ms (cache hit).
Métricas: Tasa de aciertos en cache, cantidad de accesos denegados, invocaciones de break-glass.

## Dependencias
Keycloak (OIDC Broker).
Redis o cache en memoria (Caffeine) para estado de revalidacion.
