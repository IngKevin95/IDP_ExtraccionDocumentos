variable "subscription_id" {
  description = "ID de la suscripcion de Azure donde se despliega la infraestructura"
  type        = string

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$", var.subscription_id))
    error_message = "subscription_id debe ser un GUID valido."
  }
}

variable "location" {
  description = "Region de Azure donde se desplegara la infraestructura"
  type        = string
  default     = "eastus2"
}

variable "environment" {
  description = "Nombre del ambiente (ej. dev, staging, prod)"
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]{2,8}$", var.environment))
    error_message = "environment debe tener entre 2 y 8 caracteres en minuscula o digitos."
  }
}

variable "project_name" {
  description = "Nombre del proyecto, utilizado como prefijo para recursos"
  type        = string
  default     = "idp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,14}$", var.project_name))
    error_message = "project_name debe ser minuscula, iniciar con letra y tener entre 2 y 15 caracteres."
  }
}

variable "unique_suffix" {
  description = "Sufijo para nombres globalmente unicos (Key Vault, Storage, ACR). Vacio si el prefijo ya es unico."
  type        = string
  default     = ""

  validation {
    condition     = can(regex("^[a-z0-9]{0,6}$", var.unique_suffix))
    error_message = "unique_suffix admite hasta 6 caracteres en minuscula o digitos."
  }
}

variable "tags" {
  description = "Etiquetas adicionales para todos los recursos"
  type        = map(string)
  default     = {}
}

# ------------------------------------------------------------------------------
# Red
# ------------------------------------------------------------------------------
variable "vnet_cidr" {
  description = "CIDR de la VNet (prefijo /16 o mayor; las subredes se derivan de el)"
  type        = string
  default     = "10.1.0.0/16"

  validation {
    condition     = can(cidrhost(var.vnet_cidr, 0)) && tonumber(split("/", var.vnet_cidr)[1]) <= 16
    error_message = "vnet_cidr debe ser un CIDR valido con prefijo /16 o mayor."
  }
}

variable "aks_pod_cidr" {
  description = "CIDR de pods para Azure CNI Overlay (no debe solaparse con la VNet ni redes conectadas)"
  type        = string
  default     = "192.168.0.0/16"

  validation {
    condition     = can(cidrhost(var.aks_pod_cidr, 0))
    error_message = "aks_pod_cidr debe ser un CIDR valido."
  }
}

variable "aks_service_cidr" {
  description = "CIDR de servicios de Kubernetes"
  type        = string
  default     = "172.16.0.0/16"

  validation {
    condition     = can(cidrhost(var.aks_service_cidr, 0))
    error_message = "aks_service_cidr debe ser un CIDR valido."
  }
}

variable "aks_dns_service_ip" {
  description = "IP del DNS de Kubernetes; debe estar dentro de aks_service_cidr"
  type        = string
  default     = "172.16.0.10"
}

# ------------------------------------------------------------------------------
# AKS
# ------------------------------------------------------------------------------
variable "aks_kubernetes_version" {
  description = "Version de Kubernetes para AKS (null usa la version por defecto de Azure)"
  type        = string
  default     = null
}

variable "aks_sku_tier" {
  description = "SKU del plano de control (Free, Standard o Premium). Standard incluye SLA."
  type        = string
  default     = "Standard"

  validation {
    condition     = contains(["Free", "Standard", "Premium"], var.aks_sku_tier)
    error_message = "aks_sku_tier debe ser Free, Standard o Premium."
  }
}

variable "node_vm_size" {
  description = "Tamano de VM para los nodos del cluster AKS"
  type        = string
  default     = "Standard_D4ds_v5"
}

variable "node_system_vm_size" {
  description = "Tamano de VM del pool system (solo componentes criticos del cluster)"
  type        = string
  default     = "Standard_D2ds_v5"
}

variable "node_min_count" {
  description = "Minimo de nodos del pool de usuario"
  type        = number
  default     = 2

  validation {
    condition     = var.node_min_count >= 1
    error_message = "node_min_count debe ser al menos 1."
  }
}

variable "node_max_count" {
  description = "Maximo de nodos del pool de usuario"
  type        = number
  default     = 5

  validation {
    condition     = var.node_max_count >= 1
    error_message = "node_max_count debe ser al menos 1."
  }
}

variable "node_availability_zones" {
  description = "Zonas de disponibilidad de los nodos (vacio si la region no las ofrece)"
  type        = list(string)
  default     = ["1", "2", "3"]
}

variable "aks_admin_group_object_ids" {
  description = "Object IDs de grupos de Entra ID con rol de administrador del cluster (las cuentas locales estan deshabilitadas)"
  type        = list(string)
  default     = []
}

variable "aks_authorized_ip_ranges" {
  description = "CIDRs permitidos para el API server. El cluster es privado y la API de AKS rechaza rangos autorizados en ese caso: debe quedar vacio (precondition en el cluster)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.aks_authorized_ip_ranges : can(cidrhost(c, 0)) && tonumber(split("/", c)[1]) >= 8])
    error_message = "Cada rango debe ser un CIDR valido con prefijo /8 o mayor; 0.0.0.0/0 y prefijos mas cortos no estan permitidos."
  }
}

variable "aks_azure_policy_enabled" {
  description = "Habilita el add-on Azure Policy para AKS"
  type        = bool
  default     = true
}

variable "aks_defender_enabled" {
  description = "Habilita Microsoft Defender for Containers (costo adicional)"
  type        = bool
  default     = false
}

variable "aks_host_encryption_enabled" {
  description = "Cifrado en host de los nodos. Requiere registrar la feature Microsoft.Compute/EncryptionAtHost en la suscripcion."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Dias de retencion en Log Analytics"
  type        = number
  default     = 90

  validation {
    condition     = var.log_retention_days >= 30 && var.log_retention_days <= 730
    error_message = "log_retention_days debe estar entre 30 y 730."
  }
}

# ------------------------------------------------------------------------------
# Key Vault
# ------------------------------------------------------------------------------
variable "key_vault_sku" {
  description = "SKU del Key Vault: premium para llaves respaldadas por HSM (RSA-HSM), standard para llaves por software"
  type        = string
  default     = "premium"

  validation {
    condition     = contains(["standard", "premium"], var.key_vault_sku)
    error_message = "key_vault_sku debe ser standard o premium."
  }
}

variable "key_vault_soft_delete_retention_days" {
  description = "Dias de retencion soft delete del Key Vault (7 a 90). Con purge protection no se puede purgar antes."
  type        = number
  default     = 90

  validation {
    condition     = var.key_vault_soft_delete_retention_days >= 7 && var.key_vault_soft_delete_retention_days <= 90
    error_message = "key_vault_soft_delete_retention_days debe estar entre 7 y 90."
  }
}

variable "key_vault_allowed_ip_ranges" {
  description = "IPs/CIDRs con acceso al plano de datos del Key Vault (runner que aplica OpenTofu y administradores). El trafico de los pods usa el endpoint privado."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.key_vault_allowed_ip_ranges : can(cidrhost(c, 0)) && tonumber(split("/", c)[1]) >= 8])
    error_message = "Cada rango debe ser un CIDR valido (ej. 203.0.113.10/32) con prefijo /8 o mayor; 0.0.0.0/0 y prefijos mas cortos no estan permitidos."
  }
}

variable "enable_resource_locks" {
  description = "Crea locks CanNotDelete sobre los Key Vault y la cuenta de storage. prevent_destroy de los recursos criticos es fijo (HCL no admite variables ahi); para destruir un entorno de pruebas hay que retirar ese bloque lifecycle."
  type        = bool
  default     = true
}

variable "key_admin_object_ids" {
  description = "Object IDs (usuarios, grupos o SPN) con administracion de llaves (Key Vault Crypto Officer). Nunca reciben roles de datos de blobs."
  type        = list(string)
  default     = []
}

# ------------------------------------------------------------------------------
# Storage
# ------------------------------------------------------------------------------
variable "storage_replication_type" {
  description = "Redundancia de la cuenta de storage"
  type        = string
  default     = "ZRS"

  validation {
    condition     = contains(["LRS", "ZRS", "GRS", "GZRS", "RAGRS", "RAGZRS"], var.storage_replication_type)
    error_message = "storage_replication_type debe ser LRS, ZRS, GRS, GZRS, RAGRS o RAGZRS."
  }
}

variable "cnpg_backup_retention_days" {
  description = "Dias de retencion de los backups de CloudNativePG (blobs y versiones previas)"
  type        = number
  default     = 30

  validation {
    condition     = var.cnpg_backup_retention_days >= 7
    error_message = "cnpg_backup_retention_days debe ser al menos 7."
  }
}

variable "audit_worm_default_retention_days" {
  description = "Retencion por defecto (dias) de la politica de inmutabilidad a nivel de contenedor para las versiones nuevas de evidencia de auditoria. 0 no crea politica por defecto (el adaptador aplica retencion por version). La politica nace Unlocked; bloquearla es manual e irreversible."
  type        = number
  default     = 0

  validation {
    condition     = var.audit_worm_default_retention_days >= 0 && var.audit_worm_default_retention_days <= 146000
    error_message = "audit_worm_default_retention_days debe estar entre 0 y 146000."
  }
}

# ------------------------------------------------------------------------------
# ACR
# ------------------------------------------------------------------------------
variable "acr_zone_redundancy_enabled" {
  description = "Redundancia de zona del registro (solo SKU Premium)"
  type        = bool
  default     = true
}

variable "acr_untagged_retention_days" {
  description = "Dias de retencion de manifiestos sin tag (0 deshabilita la politica)"
  type        = number
  default     = 7
}

variable "acr_quarantine_enabled" {
  description = "Cuarentena de imagenes hasta ser liberadas por un escaner (requiere integracion externa)"
  type        = bool
  default     = false
}

# ------------------------------------------------------------------------------
# Identidades de workload (SEC-011)
# ------------------------------------------------------------------------------
variable "workload_identities" {
  description = <<-EOT
    Identidades gestionadas por componente, federadas con la cuenta de servicio de Kubernetes.
    key_role: null, "Key Vault Crypto User", "Key Vault Crypto Service Encryption User" o "Key Vault Crypto Officer".
    platform_keys: nombres de llaves de plataforma que limitan el alcance de key_role (vacio = todo el vault).
    platform_keys: solo para Crypto Service Encryption User: llaves de plataforma concretas (openbao-unseal). Las demas identidades solo reciben roles sobre el vault tenant.
    blob_containers: mapa clave de contenedor (cnpg_backups, audit_worm) a rol de datos: Storage Blob Data Reader, Storage Blob Data Contributor (solo cnpg_backups) o AuditWormWriter (rol custom sin delete, solo audit_worm).
    Un rol de administracion de llaves no puede combinarse con roles de blobs, y las identidades de aplicacion nunca reciben roles sobre llaves de plataforma.
    namespace y service_account deben coincidir con los renderizados por Helm: el chart idp nombra la ServiceAccount <release>-<servicio> (salvo fullnameOverride o release que ya contenga el nombre); los defaults asumen release "idp".
  EOT
  type = map(object({
    namespace       = string
    service_account = string
    key_role        = optional(string)
    platform_keys   = optional(list(string), [])
    blob_containers = optional(map(string), {})
  }))
  default = {
    openbao = {
      namespace       = "idp-platform"
      service_account = "openbao"
      key_role        = "Key Vault Crypto Service Encryption User"
      platform_keys   = ["openbao-unseal"]
    }
    cnpg = {
      namespace       = "idp-platform"
      service_account = "idp-postgres"
      blob_containers = { cnpg_backups = "Storage Blob Data Contributor" }
    }
    audit-service = {
      namespace       = "idp-core"
      service_account = "idp-audit-service"
      key_role        = "Key Vault Crypto User"
      blob_containers = { audit_worm = "AuditWormWriter" }
    }
    tenant-onboarding = {
      namespace       = "idp-core"
      service_account = "idp-tenant-service"
      key_role        = "Key Vault Crypto Officer"
    }
  }

  validation {
    condition = alltrue([
      for k, v in var.workload_identities :
      v.key_role == null || contains([
        "Key Vault Crypto User",
        "Key Vault Crypto Service Encryption User",
        "Key Vault Crypto Officer",
      ], coalesce(v.key_role, "-"))
    ])
    error_message = "key_role debe ser null, Key Vault Crypto User, Key Vault Crypto Service Encryption User o Key Vault Crypto Officer."
  }

  validation {
    condition = alltrue(flatten([
      for k, v in var.workload_identities : [
        for c, role in v.blob_containers :
        (c == "cnpg_backups" && contains(["Storage Blob Data Reader", "Storage Blob Data Contributor"], role)) ||
        (c == "audit_worm" && contains(["Storage Blob Data Reader", "AuditWormWriter"], role))
      ]
    ]))
    error_message = "blob_containers: cnpg_backups admite Storage Blob Data Reader o Contributor; audit_worm admite Storage Blob Data Reader o AuditWormWriter (sin delete)."
  }

  validation {
    condition = alltrue([
      for k, v in var.workload_identities :
      v.key_role == null ? length(v.platform_keys) == 0 : (
        v.key_role == "Key Vault Crypto Service Encryption User" ? length(v.platform_keys) > 0 : length(v.platform_keys) == 0
      )
    ])
    error_message = "Crypto Service Encryption User exige platform_keys (solo llaves de plataforma); Crypto User y Crypto Officer no admiten platform_keys y quedan acotados al vault tenant."
  }

  validation {
    condition = alltrue(flatten([
      for k, v in var.workload_identities : [
        for key_name in v.platform_keys : contains(["openbao-unseal"], key_name)
      ]
    ]))
    error_message = "Las identidades de workload solo pueden usar la llave de plataforma openbao-unseal; disk-encryption, storage-cmk y acr-cmk pertenecen a servicios de Azure."
  }

  validation {
    condition = alltrue([
      for k, v in var.workload_identities :
      !(v.key_role == "Key Vault Crypto Officer" && length(v.blob_containers) > 0)
    ])
    error_message = "SEC-011: una identidad con Key Vault Crypto Officer no puede tener roles de datos de blobs."
  }
}
