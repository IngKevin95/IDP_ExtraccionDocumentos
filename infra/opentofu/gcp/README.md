# GCP Infrastructure para Plataforma IDP

Este directorio contendrá la definición de la infraestructura para desplegar la plataforma IDP en Google Cloud Platform (GCP). 
Su implementación detallada está programada para la **Fase F8**.

## Equivalencia de Recursos (AWS a GCP)

Para asegurar la portabilidad y mantener la misma arquitectura base (multi-tenant, Egress seguro, gestión de llaves y estado), los recursos en GCP se mapearán de la siguiente manera:

* **VPC y Redes:** Se utilizará un VPC nativo de GCP, Cloud NAT y subredes en múltiples zonas.
* **Kubernetes (EKS):** Se utilizará **Google Kubernetes Engine (GKE)** con Workload Identity para el control de accesos.
* **KMS (AWS KMS):** Se utilizará **Cloud KMS** para proveer el servicio de encriptación, especialmente para el *auto-unseal* de OpenBao.
* **Registro de Contenedores (ECR):** Se utilizará **Artifact Registry** para las imágenes OCI de la plataforma.
* **Almacenamiento de Backups (S3):** Se utilizará **Google Cloud Storage (GCS)** con buckets privados, encriptados (CMEK) y versionados para almacenar los respaldos de CloudNativePG.
