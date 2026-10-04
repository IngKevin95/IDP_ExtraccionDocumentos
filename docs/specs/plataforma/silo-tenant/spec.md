# Especificación de Plataforma: Silo por Tenant

## Propósito
Establecer el mecanismo de aislamiento de datos y recursos para cada cliente (tenant) del sistema IDP, garantizando que los datos de un banco no puedan ser accedidos por otro bajo ninguna circunstancia.

## Alcance y no alcance
Alcance: Enrutamiento dinámico de conexiónes a bases de datos, provisionamiento de esquemas dedicados (alta de tenant), rotación de credenciales dinámicas y proceso de baja/borrado de datos.
No alcance: Facturacion por tenant, personalizacion de la interfaz de usuario por tenant.

## Requisitos cubiertos
RNF-101.

## Reglas
1. Cada tenant debe tener credenciales de base de datos únicas generadas dinamicamente.
2. El enrutamiento de base de datos se basa en el contexto de seguridad (tenantId en el JWT).
3. Todo acceso a base de datos sin contexto de tenant sera rechazado.
4. La baja de un tenant requiere borrado criptografico y fisico de su silo.

## Contrato
Endpoints: Referencia a `contracts/openapi/tenant-manager.yaml`.
Eventos publicados: `contracts/events/tenant.creado.v1.schema.json`, `contracts/events/tenant.eliminado.v1.schema.json`.

## Modelo de datos
Base de datos de control: Tabla `tenant_registry` (id, nombre, estado, db_url, db_username, created_at).
Base de datos de tenant: Tablas operativas aisladas sin columna tenantId, ya que el esquema es dedicado.

## Controles de seguridad
SEC-001 Aislamiento logico de datos por tenant mediante esquemas dedicados.
SEC-043 Credenciales efímeras de acceso a BD por tenant inyectadas en tiempo de ejecucion.

## Escenarios de aceptacion

### AC-01 Alta de nuevo tenant exitosa
Given una solicitud de alta de tenant valida
When el sistema procesa la solicitud
Then aprovisiona un nuevo esquema, genera credenciales dinámicas y emite el evento tenant.creado.

### AC-02 Bloqueo de acceso sin tenantId
Given una petición a un endpoint operativo sin tenantId en el token
When se intenta resolver la conexión a base de datos
Then el sistema lanza una excepción de seguridad y deniega el acceso.

### AC-03 Enrutamiento correcto al silo
Given un usuario autenticado con tenantId T1
When consulta sus documentos
Then el enrutador de base de datos utiliza exclusivamente las credenciales y URL del esquema de T1.

### AC-04 Aislamiento estricto de datos
Given un usuario autenticado con tenantId T1
When intenta acceder a un identificador de documento perteneciente a T2
Then el sistema devuelve 404 No Encontrado al no existir el registro en el esquema T1.

### AC-05 Baja de tenant y borrado
Given una orden de baja definitiva de un tenant
When el proceso de baja se ejecuta
Then se eliminan las credenciales, se realiza el drop del esquema y se emite tenant.eliminado.

### AC-06 Reconciliacion de estado
Given una discrepancia entre el registro de tenants y los esquemas creados
When se ejecuta el proceso de reconciliación nocturno
Then se genera un reporte de auditoria con las anomalias detectadas.

### AC-07 Rotacion de credenciales dinámicas
Given que expira el ciclo de vida de las credenciales de base de datos
When el programador de rotación se activa
Then actualiza las credenciales en la base de datos subyacente y en el registro de control sin inactividad.

### AC-08 Acceso denegado a tenant inactivo
Given un tenant marcado como suspendido en el registro de control
When un usuario de dicho tenant intenta realizar una operación
Then el enrutador de base de datos bloquea la conexión y devuelve error 403.


### AC-09 Certificación de accesos trimestral (SEC-012)
Given el ciclo operativo regular de un tenant
When transcurre un trimestre
Then el tenant-service genera automáticamente un reporte exportable de certificación de accesos, firmado digitalmente como evidencia.

## Métricas y SLO
SLO: 99.9% de exito en enrutamiento a la base de datos correcta.
Métricas: Tiempo de resolucion de conexión por tenant, conteo de errores de enrutamiento.

## Dependencias
Servicio de Secretos (Vault/KMS) para almacenamiento seguro de credenciales maestras.
PostgreSQL u otro motor de BD compatible con esquemas dinámicos.
