# ADR 0006: Aislamiento de datos mediante Silo por Tenant

## Estado
Aprobado

## Contexto
El sistema procesa oficios judiciales y datos de embargos (Confidenciales por defecto; Altamente Confidenciales solo si el tenant lo marca) de múltiples instituciones financieras (tenants). Cumplir con la regulación bancaria, mitigar el riesgo catastrófico de contaminación cruzada de datos (exposición de datos de un banco a otro) y proporcionar garantías sólidas de eliminación de datos (Habeas Data, crypto-shredding) requiere una estrategia de aislamiento arquitectónico superior al simple filtrado lógico por columna (row-level security o filtros `tenant_id` en tablas compartidas).

## Decisión
Se adopta el modelo de **Silo de Datos Físico Lógico por Tenant** a nivel de almacenamiento persistente.

1.  **Base de Datos por Tenant:** Cada tenant poseerá su propia base de datos lógica dedicada (schema/database de PostgreSQL). No se compartirán tablas de dominio operativo entre tenants.
2.  **Infraestructura CNPG:** Estas bases de datos residirán físicamente en un `Cluster` compartido gestionado por CloudNativePG (CNPG) para tiers estándar, o en un `Cluster` dedicado para tiers premium. La gestión del ciclo de vida (backup de WAL, instance manager) será responsabilidad de CNPG y Barman Cloud, descartando soluciones como Patroni o pgBackRest directo.
3.  **Enrutamiento Dinámico (Librería `tenant-context`):** La conexión a las bases se resolverá en tiempo de ejecución. Se implementará una librería transversal (`libs/tenant-context`) que utilice `AbstractRoutingDataSource` de Spring. Por cada petición validada, se establecerá el tenant en el contexto, y la aplicación abrirá un pool de conexiones (HikariCP) específico para ese tenant bajo demanda. Existirá un tope máximo de pools para proteger la memoria del servicio.
4.  **Credenciales Dinámicas y Aprovisionamiento:** El `tenant-service` actuará como el plano de control administrativo. Al dar de alta un tenant, conectará con la base administrativa del clúster, creará la base lógica del tenant y aprovisionará roles. Se usarán credenciales dinámicas proporcionadas por OpenBao con TTL corto; los servicios nunca poseerán contraseñas estáticas de los silos.
5.  **Exclusión de Argo CD:** Los recursos de infraestructura del tenant (como su base de datos lógica y sus políticas en OpenBao) son creados imperativamente por el `tenant-service` (vía APIs del proveedor/Kubernetes). Se prohíbe explícitamente que Argo CD gestione recursos a nivel de tenant; Argo CD solo gestiona el despliegue estático de la plataforma. Se emitirán eventos de `tenant.aprovisionado` o `tenant.aprovisionamiento_fallido` (con lógicas de compensación) para mantener coherencia.

## Alternativas Consideradas

*   **Modelo de Pool o Compartido (Base de datos única con columna `tenant_id`):** Descartado de plano. Aunque es la opción más sencilla y económica, introduce un riesgo masivo (Single Point of Compromise) en caso de fallos lógicos en consultas SQL (ej. olvidar un filtro `WHERE tenant_id = ?`). Hace imposible estrategias eficientes de crypto-shredding por tenant o de restauración de backups a un punto en el tiempo (RPO) para un solo banco sin afectar al resto.
*   **Instancias de Servicio por Tenant:** Descartado. Desplegar un pod de microservicio distinto (`document-service`, `extraction-service`, etc.) por cada tenant aislaría la computación, pero incrementaría exponencialmente los costos de infraestructura (compute exhaustion) y la complejidad de las actualizaciones de versión. La computación se mantiene multi-tenant (stateless) garantizando que la separación ocurra en la capa de datos.

## Consecuencias
*   **Positivas:** Riesgo nulo de contaminación cruzada accidental vía consultas SQL erróneas (aislamiento fuerte). Permite encriptación transparente con llaves (KEK) diferenciadas por tenant en repositorios como OpenBao. Facilita la purga total de información (offboarding de un banco) simplemente mediante el borrado de su base de datos lógica y su bucket.
*   **Negativas / Riesgos:** Requiere librerías internas más complejas (`tenant-context`) y exige manejar cuidadosamente la explosión de conexiones a PostgreSQL (utilizando topes de pool Hikari y balanceadores como PgBouncer gestionados por CNPG). Complica la ejecución de migraciones Flyway, requiriendo su ejecución iterativa por el `tenant-service` o sidecars especializados para aplicar DDL a múltiples silos de forma controlada.

## Controles de Seguridad Aplicables
*   **SEC-020:** Aislamiento lógico. Separación de almacenamiento a nivel de base de datos lógica en PostgreSQL para prevenir contaminación cruzada de datos bancarios.
*   **SEC-009:** Credenciales temporales y dinámicas gestionadas vía OpenBao para el acceso de los microservicios a los silos de base de datos de tenant, rotadas frecuentemente (TTL corto).
*   **SEC-016:** Borrado seguro de la información; la arquitectura en silos permite la eliminación del tenant completo y su llave asociada (crypto-shredding) sin afectar a terceros.