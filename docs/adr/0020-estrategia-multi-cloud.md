# 0020. Estrategia multi-cloud

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El producto IDP debe ser desplegable eficientemente en múltiples infraestructuras (AWS, Azure, GCP y datacenters on-premise) para cumplir con requerimientos comerciales y normativos de los clientes. Acoplarse excesivamente a tecnologías propietarias (como Spanner, SQS o Cognito) crearía ramas de código paralelas inmanejables y altos sobrecostos, violando la portabilidad del producto.

## Decisión
Se implementa una estrategia de "Núcleo Portable con Adaptadores Específicos" cimentada en inyección de dependencias.
1. Puertos e Interfaces Java: Se exponen interfaces abstractas (`ObjectStore`, `KeyService`, `LlmProvider`, `ImmutableStore`) en el módulo `libs/`.
2. Adaptadores enchufables por destino: Se codifican implementaciones específicas. Para entornos on-premise, el objeto de almacenamiento local utilizará el adaptador `s3-compatible-adapter` validando obligatoriamente tecnologías compatibles con Object Lock como Ceph RGW o SeaweedFS (excluyendo a MinIO de la arquitectura de referencia por limitaciones operativas).
3. Independencia de Servicios Core: Las bases de datos operarán siempre sobre PostgreSQL (exigiendo su protocolo estándar para implementaciones como Aurora). 
4. Agnosticismo en orquestación: Kubernetes provee el plano de ejecución homogéneo, aprovisionando la infraestructura física externa mediante recursos modulares de OpenTofu para la nube respectiva.

## Alternativas consideradas
- Acoplamiento directo a un Proveedor IaaS único: Limitaría comercialmente al producto frente a clientes bancarios comprometidos con nubes particulares u on-premise.
- Capas de abstracción genéricas (tipo Apache jclouds): Limitan la integración de seguridad con identidades nativas como Workload Identity de GCP o Pod Identity de EKS.
- Forzar servicios open-source locales en todos los destinos: Desplegar Kafka/PostgreSQL internos en clúster cuando las nubes ofrecen PaaS superiores frustra a los clientes. El núcleo nativo cloud prefiere PaaS y se degrada a auto-hospedado solo en `self-hosted`.

## Consecuencias
- Positivas: Disminuye a cero el bloqueo comercial frente a licitaciones B2B. Aísla dependencias de borde posibilitando una integración y testing unitario fluidos.
- Negativas o costos: Fuerte esfuerzo inicial en integración y validación cruzada. Riesgos asociados a licenciamiento *on-premise* forzando la transición a OpenBao (MPL) y OpenTofu frente a herramientas en la nube.

## Controles relacionados
SEC-011, SEC-020, SEC-043, SEC-046