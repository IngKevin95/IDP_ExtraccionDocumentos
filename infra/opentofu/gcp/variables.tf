variable "project_id" {
  description = "ID del proyecto de GCP donde se despliega la infraestructura"
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id debe tener 6 a 30 caracteres: minusculas, digitos o guiones, empezar con letra y no terminar en guion."
  }
}

variable "region" {
  description = "Region de GCP (el cluster GKE y la subred son regionales)"
  type        = string
  default     = "us-central1"

  validation {
    condition     = can(regex("^[a-z]+-[a-z]+[0-9]+$", var.region))
    error_message = "region debe ser una region de GCP, por ejemplo us-central1."
  }
}

variable "environment" {
  description = "Nombre del ambiente (ej. dev, staging, prod)"
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,9}$", var.environment))
    error_message = "environment debe tener 2 a 10 caracteres en minuscula alfanumerica y empezar con letra."
  }
}

variable "project_name" {
  description = "Nombre del proyecto, utilizado como prefijo para recursos"
  type        = string
  default     = "idp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,13}[a-z0-9]$", var.project_name))
    error_message = "project_name debe tener 3 a 15 caracteres en minuscula, digitos o guiones, y empezar con letra."
  }
}

# ------------------------------------------------------------------------------
# Red
# ------------------------------------------------------------------------------
variable "subnet_cidr" {
  description = "CIDR primario de la subred de los nodos"
  type        = string
  default     = "10.10.0.0/20"

  validation {
    condition     = can(cidrhost(var.subnet_cidr, 0))
    error_message = "subnet_cidr debe ser un CIDR IPv4 valido."
  }
}

variable "pods_cidr" {
  description = "Rango secundario para los pods de GKE"
  type        = string
  default     = "10.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.pods_cidr, 0))
    error_message = "pods_cidr debe ser un CIDR IPv4 valido."
  }
}

variable "services_cidr" {
  description = "Rango secundario para los servicios (ClusterIP) de GKE"
  type        = string
  default     = "10.30.0.0/20"

  validation {
    condition     = can(cidrhost(var.services_cidr, 0))
    error_message = "services_cidr debe ser un CIDR IPv4 valido."
  }
}

variable "master_ipv4_cidr" {
  description = "CIDR /28 reservado para el plano de control privado de GKE"
  type        = string
  default     = "172.16.0.0/28"

  validation {
    condition     = can(cidrhost(var.master_ipv4_cidr, 0)) && endswith(var.master_ipv4_cidr, "/28")
    error_message = "master_ipv4_cidr debe ser un CIDR IPv4 /28 valido."
  }
}

# ------------------------------------------------------------------------------
# GKE
# ------------------------------------------------------------------------------
variable "gke_release_channel" {
  description = "Release channel de GKE (gestiona la version de Kubernetes)"
  type        = string
  default     = "REGULAR"

  validation {
    condition     = contains(["RAPID", "REGULAR", "STABLE"], var.gke_release_channel)
    error_message = "gke_release_channel debe ser RAPID, REGULAR o STABLE."
  }
}

variable "master_authorized_cidrs" {
  description = "CIDRs externos autorizados a llegar al API de GKE. Si esta vacia, el endpoint publico se deshabilita y solo se accede desde la VPC."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.master_authorized_cidrs, "0.0.0.0/0")
    error_message = "El CIDR 0.0.0.0/0 no esta permitido por seguridad."
  }

  validation {
    condition     = alltrue([for c in var.master_authorized_cidrs : can(cidrhost(c, 0)) && tonumber(split("/", c)[1]) >= 8])
    error_message = "Todos los elementos de master_authorized_cidrs deben ser CIDRs IPv4 validos con prefijo /8 o mas especifico (se rechazan /0 y rangos muy amplios)."
  }
}

variable "node_machine_type" {
  description = "Tipo de maquina de los nodos de GKE"
  type        = string
  default     = "e2-standard-4"
}

variable "node_disk_size_gb" {
  description = "Tamano del disco de cada nodo en GB"
  type        = number
  default     = 100

  validation {
    condition     = var.node_disk_size_gb >= 50
    error_message = "node_disk_size_gb debe ser al menos 50."
  }
}

variable "node_min_count" {
  description = "Nodos minimos por zona en el node pool"
  type        = number
  default     = 1

  validation {
    condition     = var.node_min_count >= 1
    error_message = "node_min_count debe ser al menos 1."
  }
}

variable "node_max_count" {
  description = "Nodos maximos por zona en el node pool (debe ser >= node_min_count; se verifica en un precondition de main.tf)"
  type        = number
  default     = 3

  validation {
    condition     = var.node_max_count >= 1
    error_message = "node_max_count debe ser al menos 1."
  }
}

# ------------------------------------------------------------------------------
# Cloud KMS
# ------------------------------------------------------------------------------
variable "kms_rotation_period" {
  description = "Periodo de rotacion automatica de las llaves simetricas, en segundos con sufijo s (90 dias por defecto)"
  type        = string
  default     = "7776000s"

  validation {
    condition     = can(regex("^[0-9]+s$", var.kms_rotation_period)) && tonumber(trimsuffix(var.kms_rotation_period, "s")) >= 86400
    error_message = "kms_rotation_period debe tener formato <segundos>s y ser al menos 86400s (1 dia)."
  }
}

variable "kms_destroy_scheduled_duration" {
  description = "Tiempo que una version de llave permanece en DESTROY_SCHEDULED antes de destruirse. No se puede cambiar despues de crear la llave."
  type        = string
  default     = "2592000s"

  validation {
    condition     = can(regex("^[0-9]+s$", var.kms_destroy_scheduled_duration)) && tonumber(trimsuffix(var.kms_destroy_scheduled_duration, "s")) >= 86400
    error_message = "kms_destroy_scheduled_duration debe tener formato <segundos>s y ser al menos 86400s (1 dia)."
  }
}

variable "kms_protection_level" {
  description = "Nivel de proteccion de las llaves: SOFTWARE o HSM"
  type        = string
  default     = "SOFTWARE"

  validation {
    condition     = contains(["SOFTWARE", "HSM"], var.kms_protection_level)
    error_message = "kms_protection_level debe ser SOFTWARE o HSM."
  }
}

# ------------------------------------------------------------------------------
# Almacenamiento
# ------------------------------------------------------------------------------
variable "cnpg_backups_retention_days" {
  description = "Dias tras los cuales se eliminan los backups de CloudNativePG"
  type        = number
  default     = 30

  validation {
    condition     = var.cnpg_backups_retention_days >= 1
    error_message = "cnpg_backups_retention_days debe ser al menos 1."
  }
}

variable "access_logs_retention_days" {
  description = "Dias de retencion de los logs de acceso a buckets"
  type        = number
  default     = 365

  validation {
    condition     = var.access_logs_retention_days >= 1
    error_message = "access_logs_retention_days debe ser al menos 1."
  }
}

variable "evidence_retention_days" {
  description = "Retencion por defecto (dias) del bucket WORM de evidencia de auditoria; aplica a objetos sin retencion propia"
  type        = number
  default     = 365

  validation {
    condition     = var.evidence_retention_days >= 1
    error_message = "evidence_retention_days debe ser al menos 1."
  }
}

variable "evidence_retention_locked" {
  description = "Bloquea de forma IRREVERSIBLE la politica de retencion del bucket de evidencia (Bucket Lock). Activar solo en prod con el periodo definitivo."
  type        = bool
  default     = false
}

# ------------------------------------------------------------------------------
# IAM (SEC-011)
# ------------------------------------------------------------------------------
variable "key_admin_members" {
  description = "Miembros IAM (ej. group:kms-admins@example.com) que administran llaves (roles/cloudkms.admin). Nunca reciben lectura de objetos."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for m in var.key_admin_members : can(regex("^(user|group|serviceAccount):[^ ]+$", m))])
    error_message = "key_admin_members debe contener miembros con formato user:, group: o serviceAccount:."
  }
}

variable "evidence_reader_members" {
  description = "Miembros IAM con lectura de los objetos del bucket de evidencia (roles/storage.objectViewer). Nunca reciben administracion de llaves."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for m in var.evidence_reader_members : can(regex("^(user|group|serviceAccount):[^ ]+$", m))])
    error_message = "evidence_reader_members debe contener miembros con formato user:, group: o serviceAccount:."
  }
}

variable "workload_identities" {
  description = <<-EOT
    Cuentas de Kubernetes (namespace y nombres) que asumen cada cuenta de servicio de GCP via Workload Identity.
    Confirmados en el repo: namespaces idp-core e idp-platform (deploy/platform/namespaces.yaml) y la SA de
    CNPG idp-postgres (deploy/platform/postgres/cluster.yaml). Dependen del nombre de release de Helm
    (<release>-<servicio>; aqui release idp-app-prod, deploy/argocd/applications/prod) o del chart de OpenBao
    (SA "openbao" asumida, sin confirmar) y deben ajustarse si cambian.
  EOT
  type = map(object({
    namespace        = string
    service_accounts = list(string)
  }))
  default = {
    openbao = { namespace = "idp-platform", service_accounts = ["openbao"] }
    cnpg    = { namespace = "idp-platform", service_accounts = ["idp-postgres"] }
    audit   = { namespace = "idp-core", service_accounts = ["idp-app-prod-audit-service"] }
    app = {
      namespace = "idp-core"
      service_accounts = [
        "idp-app-prod-tenant-service",
        "idp-app-prod-document-service",
        "idp-app-prod-extraction-service",
        "idp-app-prod-review-service",
        "idp-app-prod-quality-service",
        "idp-app-prod-chat-service",
        "idp-app-prod-notification-service",
      ]
    }
    onboarding = { namespace = "idp-core", service_accounts = ["tenant-onboarding"] }
  }

  validation {
    condition     = toset(keys(var.workload_identities)) == toset(["openbao", "cnpg", "audit", "app", "onboarding"])
    error_message = "workload_identities debe definir exactamente las claves openbao, cnpg, audit, app y onboarding."
  }
}
