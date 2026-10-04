# 0019. Kubernetes con operadores

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El desarrollo de una plataforma multi-tenant compleja basada en eventos, con bases de datos stateful y ejecución en modalidades multi-nube y on-premise, impone un desafío en gestión operativa. Confiar exclusivamente en scripts bash locales o despliegues manuales conduce a la deriva de configuración.

## Decisión
Se elige la orquestación en Kubernetes mediante el patrón de Operadores (Operator Pattern) integrado estrechamente con prácticas de GitOps (Argo CD).
1. Estándar de facto unificado: Kubernetes funciona como la plataforma base aislando las diferencias de las IaaS. Argo CD gestiona en exclusividad la plataforma base, mientras que los recursos de tenants los crea `tenant-service` vía API de Kubernetes/proveedor en namespaces excluidos de Argo CD.
2. Operadores para cargas críticas: Se usa Strimzi para Kafka (en modo KRaft, sin Zookeeper). Para PostgreSQL, se descartan Patroni y pgBackRest en favor del Operador CloudNativePG (CNPG) utilizando su instance manager y Barman Cloud para lograr RPO eficiente por archivado continuo de WAL.
3. Seguridad a nivel pod: Manifiestos con `securityContexts` estrictos (no root, root filesystem read-only, capacidades caídas), compatibles con SCCs de OpenShift.

## Alternativas consideradas
- Serverless (Lambda, Cloud Run): Rompen el núcleo de nuestra estrategia multi-destino (multi-cloud y self-hosted).
- Máquinas virtuales o Docker Swarm: Rechazados por carencias de resiliencia automatizada.
- Infraestructura gestionada completamente por Terraform para las cargas: Terraform/OpenTofu aprovisiona los cimientos IaaS, pero el ciclo de vida del contenedor se delega al orquestador Kubernetes vía GitOps (pull) para evitar bloqueos de estado.

## Consecuencias
- Positivas: Prevención de ventanas de inactividad que violen los SLO. Compatibilidad temprana con regulaciones estrictas.
- Negativas o costos: Curva de aprendizaje empinada para los equipos en la gestión de CRDs y almacenamiento persistente a través de CSI.

## Controles relacionados
SEC-014, SEC-020, SEC-045, SEC-046