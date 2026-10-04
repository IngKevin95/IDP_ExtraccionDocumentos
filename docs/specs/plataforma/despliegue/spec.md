# Especificación: Despliegue y Orquestación

## 1. Propósito
Definir un mecanismo estándar, reproducible y agnóstico a la nube para el despliegue del IDP en entornos de Kubernetes (EKS, AKS, GKE u OpenShift). Centralizar la configuración mediante infra-as-code y automatizar el ciclo de vida de los aplicativos a través de enfoques GitOps, garantizando la consistencia multitenant.

## 2. Alcance y No Alcance
**Alcance:**
* Empaquetado de microservicios usando Helm (Umbrella chart).
* Gestión de configuraciones diferenciadas por entorno (values por destino).
* Aprovisionamiento de infraestructura base mediante OpenTofu.
* Orquestación de entrega continua basada en GitOps (Argo CD).
* Automatización de dependencias complejas de estado usando Operadores de Kubernetes (PostgreSQL, Kafka).

**No Alcance:**
* Construcción del código fuente (compilación, pruebas unitarias y empaquetado Docker), lo cual es dominio de la Cadena de Suministro (CI).
* Despliegue en servidores bare-metal tradicionales (fuera de Kubernetes).

## 3. Requisitos Cubiertos
* **RNF-102:** Disponibilidad multi-cloud. Empaquetado estandarizado para K8s sin amarrarse a características propietarias.
* **RNF-104:** Recuperación frente a desastres. Toda la configuración requerida para recrear el entorno reside en repositorios Git y OpenTofu.

## 4. Reglas
1. **Infraestructura Inmutable:** Todo cambio en el entorno de ejecución debe originarse desde un cambio aprobado en Git. Prohibida la modificación manual mediante `kubectl edit`.
2. **Ejecución sin Privilegios:** Ningún contenedor puede ejecutarse como `root`. Todos los Deployments deben ser compatibles con UIDs arbitrarios (requerimiento duro para compatibilidad con OpenShift).
3. **Desacoplamiento Estado-Lógica:** Los microservicios sin estado se despliegan vía Helm genérico; los componentes con estado (Kafka, CloudNativePG) se gestionan única y exclusivamente a través de sus respectivos Kubernetes Operators.
4. **Verificación de Portabilidad:** Cualquier cambio en los charts o manifiestos debe pasar por herramientas de validación de sintaxis y validaciones de portabilidad nativa.

## 5. Contrato
* **OpenTofu:** Módulos definidos para clústeres de K8s, redes y roles IAM/RBAC.
* **Helm:** Un "Umbrella Chart" que coordina las dependencias de los subcharts de microservicios.
* **Manifiestos GitOps:** Recursos `Application` o `ApplicationSet` de Argo CD referenciando al repositorio de configuración.

## 6. Modelo de Datos
La configuración se maneja en un esquema declarativo de archivos, no en base de datos.
* **Estructura GitOps:**
  * `clusters/`: Configuraciones específicas de los clústeres.
  * `tenants/`: Configuraciones específicas por tenant (cuotas, recursos, URLs).
  * `base/`: Charts base y definiciones genéricas de servicios.

## 7. Controles de Seguridad
* **SEC-RBAC:** Permisos de Argo CD limitados a los namespaces necesarios. Las cuentas de servicio de los Pods (ServiceAccounts) operan con el principio de menor privilegio (sin acceso a la API del clúster si no es necesario).
* **SEC-SECRETS:** Los secretos no se guardan en texto claro en Git. Se integran soluciones como External Secrets Operator apoyado por OpenBao, o encriptación SOPS.
* **SEC-NETPOL:** Aplicación por defecto de NetworkPolicies (Deny-All) en los namespaces; permitiendo explícitamente solo el tráfico entre componentes declarados.

## 8. Escenarios de Aceptación

* **AC-01 [Agnosticidad Multi-Nube]:** Given un Helm chart del IDP, When se despliega sobre clústeres GKE, EKS y OpenShift, Then el aplicativo levanta correctamente sin necesidad de bifurcar (forkear) el código fuente de los manifiestos por cada nube.
* **AC-02 [Restricciones de Root]:** Given un clúster K8s con políticas de seguridad restrictivas, When el Argo CD intenta sincronizar un Deployment, Then los contenedores se ejecutan exitosamente con `runAsNonRoot: true` y UIDs aleatorios (compatibilidad OpenShift).
* **AC-03 [Despliegue GitOps]:** Given un cambio de versión en la imagen Docker aprobada en `values-prod.yaml`, When se aprueba el PR en el repositorio de configuración, Then Argo CD detecta el cambio, sincroniza el clúster y realiza un RollingUpdate sin pérdida de tráfico.
* **AC-04 [Operadores Stateful]:** Given el aprovisionamiento de un nuevo entorno, When se instalan los charts base, Then el CloudNativePG Operator se encarga de crear el clúster de base de datos PostgreSQL, configurar la replicación y la rotación WAL automáticamente.
* **AC-05 [Aislamiento Lógico Tenant]:** Given la adición de la configuración de un nuevo tenant, When se despliega la configuración, Then Argo CD aprovisiona los recursos lógicos específicos (como bases de datos en CloudNativePG y roles en Kafka) sin afectar a los tenants existentes.
* **AC-06 [Validación Previa]:** Given un PR modificando un Helm Chart, When se lanza el pipeline, Then `helm lint` y `kubeval` verifican la completitud y corrección de los manifiestos, bloqueando el PR si introducen errores.
* **AC-07 [Rollback Declarativo]:** Given un despliegue fallido que no pasa sus probes (liveness/readiness), When el operador revierte el commit en Git, Then Argo CD revierte de forma automática el estado del clúster a la versión anterior estable.
* **AC-08 [OpenTofu Idempotencia]:** Given el código de infraestructura de red y clúster, When se ejecuta `tofu plan` sin cambios en el código, Then la herramienta no debe reflejar ninguna desviación ni propuesta de alteración de recursos.

## 9. Métricas y SLO
* **Métricas:**
  * `argocd_app_sync_status`: Estado de sincronización de las aplicaciones.
  * Tiempos de ejecución de los pipelines de OpenTofu (Apply time).
* **SLO:**
  * Tiempo de recuperación de infraestructura completa (RTO infraestructura base) <= 4 horas.

## 10. Dependencias
* **Infraestructura As Code:** OpenTofu, Argo CD.
* **Empaquetado:** Helm v3.
* **Controladores Kubernetes:** CloudNativePG (PostgreSQL), Strimzi (Kafka), External Secrets Operator.
