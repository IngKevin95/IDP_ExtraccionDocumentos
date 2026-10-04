# Argo CD - Configuración de Despliegue

Este directorio contiene la definición GitOps para el proyecto IDP.

## Namespaces de Tenants

**IMPORTANTE:** Los namespaces asociados a cada inquilino (`tenant-*`) quedan **explícitamente fuera** de la gestión de Argo CD. 

La administración, creación y aprovisionamiento de bases de datos lógicas, silos físicos, secretos dinámicos y segregación física de los tenants está delegada de forma programática al servicio de plataforma `tenant-service`. Argo CD gestiona únicamente la plataforma base (operadores) y los microservicios core.