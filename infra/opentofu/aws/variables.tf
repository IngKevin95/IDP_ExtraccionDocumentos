variable "aws_region" {
  description = "Region de AWS donde se desplegara la infraestructura"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Nombre del ambiente (ej. dev, staging, prod)"
  type        = string
}

variable "project_name" {
  description = "Nombre del proyecto, utilizado como prefijo para recursos"
  type        = string
  default     = "idp"
}

variable "vpc_cidr" {
  description = "CIDR block para la VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "eks_cluster_version" {
  description = "Version de Kubernetes para EKS"
  type        = string
  default     = "1.31"
}

variable "node_instance_types" {
  description = "Tipos de instancia para los nodos del cluster EKS"
  type        = list(string)
  default     = ["t3.large"]
}

variable "eks_public_access_cidrs" {
  description = "Lista de CIDRs permitidos para acceder al API de EKS. Si esta vacia, el acceso publico se deshabilita."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.eks_public_access_cidrs, "0.0.0.0/0")
    error_message = "El CIDR 0.0.0.0/0 no esta permitido por seguridad."
  }
}

variable "kms_deletion_window_in_days" {
  description = "Dias de retencion para llaves KMS antes de su eliminacion (ej. 30 en prod)"
  type        = number
  default     = 7
}
