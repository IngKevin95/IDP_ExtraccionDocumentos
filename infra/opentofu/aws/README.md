# AWS Infrastructure para Plataforma IDP

Este módulo despliega la infraestructura base necesaria para ejecutar la plataforma IDP en AWS. Utiliza las mejores prácticas y los módulos oficiales de AWS para Terraform.

## Recursos Creados

1. **VPC Privada (Módulo Oficial `terraform-aws-modules/vpc/aws`)**
   - Distribuida en 3 Availability Zones.
   - Subnets públicas y privadas.
   - NAT Gateway (único en entornos no-prod, HA en prod).

2. **EKS Cluster (Módulo Oficial `terraform-aws-modules/eks/aws`)**
   - Cluster gestionado EKS.
   - Nodos gestionados (Spot Instances en ambientes no-prod).
   - Uso de Pod Identity (reemplazo moderno de IRSA).

3. **Almacenamiento (S3)**
   - Bucket S3 para respaldos de PostgreSQL (CloudNativePG).
   - Cifrado SSE-KMS habilitado.
   - Versionado habilitado.
   - Bloqueo total de acceso público.

4. **Gestión de Llaves (KMS)**
   - Llave KMS específica para la funcionalidad de *auto-unseal* de OpenBao.
   - Llave KMS para cifrado de S3.

5. **Registro de Contenedores (ECR)**
   - Repositorio para las imágenes de los servicios de la plataforma.
   - Etiquetas inmutables y escaneo de vulnerabilidades on-push.

## Requisitos previos

- Instalar [OpenTofu](https://opentofu.org/) (versión `1.8` o superior).
- Configurar credenciales de AWS (ej. vía `aws configure` o variables de entorno `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`).

## Backend

El estado de infraestructura se recomienda almacenar de forma remota (backend S3 + DynamoDB para locks). El bloque está comentado en `versions.tf` a modo de ejemplo y debe ser configurado con un bucket S3 que soporte cifrado y versionado.

## Uso

1. Inicializa el directorio (y el backend):
   ```bash
   tofu init
   ```

2. Crea tu archivo de variables basado en el ejemplo:
   ```bash
   cp terraform.tfvars.example dev.tfvars
   # Edita dev.tfvars según tus necesidades
   ```

3. Revisa el plan de ejecución:
   ```bash
   tofu plan -var-file="dev.tfvars"
   ```

4. Aplica los cambios:
   ```bash
   tofu apply -var-file="dev.tfvars"
   ```
