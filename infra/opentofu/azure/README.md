# Azure Infrastructure para Plataforma IDP

Este directorio contendrá la definición de la infraestructura para desplegar la plataforma IDP en Microsoft Azure. 
Su implementación detallada está programada para la **Fase F8**.

## Equivalencia de Recursos (AWS a Azure)

Para asegurar la portabilidad y mantener la misma arquitectura base (multi-tenant, Egress seguro, gestión de llaves y estado), los recursos en Azure se mapearán de la siguiente manera:

* **VPC y Redes:** Se utilizará **Azure Virtual Network (VNet)** con subredes separadas y Azure NAT Gateway para la salida a internet.
* **Kubernetes (EKS):** Se utilizará **Azure Kubernetes Service (AKS)** con Microsoft Entra Workload ID para el acceso seguro a recursos sin secretos fijos.
* **KMS (AWS KMS):** Se utilizará **Azure Key Vault** (específicamente Key Vault Keys) para gestionar las llaves de cifrado, incluyendo la requerida para el *auto-unseal* de OpenBao.
* **Registro de Contenedores (ECR):** Se utilizará **Azure Container Registry (ACR)**.
* **Almacenamiento de Backups (S3):** Se utilizará **Azure Blob Storage** en una Storage Account segura, bloqueando el acceso público y habilitando redundancia para los respaldos de CloudNativePG.
