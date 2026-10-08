provider "google" {
  project = var.project_id
  region  = var.region

  default_labels = {
    project     = var.project_name
    environment = var.environment
    managed_by  = "opentofu"
  }
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}

locals {
  prefix       = "${var.project_name}-${var.environment}"
  cluster_name = "${local.prefix}-gke"
  symmetric_keys = {
    openbao_unseal = "openbao-unseal"
    gke_secrets    = "gke-secrets"
    storage        = "storage-cmek"
    registry       = "registry-cmek"
    app_wrap       = "app-wrap"
    nodes_disk     = "nodes-disk-cmek"
  }

  # Los nombres de bucket son globales: el project_id evita colisiones.
  bucket_prefix = "${local.prefix}-${var.project_id}"
  workload_pool = "${var.project_id}.svc.id.goog"

  apis = [
    "cloudkms.googleapis.com",
    "compute.googleapis.com",
    "container.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "artifactregistry.googleapis.com",
    "containerscanning.googleapis.com",
    "storage.googleapis.com",
  ]

  # Agentes de servicio de Google que usan las llaves CMEK en nombre del recurso.
  gke_service_agent = "serviceAccount:service-${data.google_project.this.number}@container-engine-robot.iam.gserviceaccount.com"
  gcs_service_agent = "serviceAccount:${data.google_storage_project_service_account.gcs.email_address}"
  gce_service_agent = "serviceAccount:service-${data.google_project.this.number}@compute-system.iam.gserviceaccount.com"
  ar_service_agent  = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-artifactregistry.iam.gserviceaccount.com"

  # Rol personalizado: solo lo que necesita el adaptador GcsImmutableStore. No incluye
  # storage.objects.delete (WORM) ni ningun permiso cloudkms.* (SEC-011).
  evidence_writer_permissions = [
    "storage.buckets.get",
    "storage.objects.create",
    "storage.objects.get",
    "storage.objects.list",
    "storage.objects.update",
    "storage.objects.setRetention",
  ]
}

data "google_project" "this" {
  project_id = var.project_id
}

data "google_storage_project_service_account" "gcs" {
  project = var.project_id

  depends_on = [time_sleep.service_agents]
}

resource "google_project_service" "apis" {
  for_each = toset(local.apis)

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# Validaciones entre variables: los validation de variables no pueden referenciar
# otras variables en OpenTofu/Terraform 1.8, por eso viven aqui.
# Los agentes de servicio de Google (GKE, Compute) se crean de forma asincrona tras
# habilitar la API; sin esta espera el binding de IAM puede fallar por "no existe".
resource "time_sleep" "service_agents" {
  create_duration = "60s"

  depends_on = [google_project_service.apis]
}

resource "terraform_data" "input_checks" {
  lifecycle {
    precondition {
      condition     = var.node_max_count >= var.node_min_count
      error_message = "node_max_count debe ser mayor o igual que node_min_count."
    }

    # account_id de las cuentas de servicio admite hasta 30 caracteres (sufijo mas largo: -gke-nodes).
    precondition {
      condition     = length(local.prefix) <= 20
      error_message = "project_name y environment combinados (project_name-environment) no pueden superar 20 caracteres."
    }

    # Nombre mas largo de bucket: <bucket_prefix>-audit-evidence (limite de 63 caracteres).
    precondition {
      condition     = length(local.bucket_prefix) + length("-audit-evidence") <= 63
      error_message = "Los nombres de bucket superan 63 caracteres: acorta project_name, environment o usa un project_id mas corto."
    }

    precondition {
      condition     = var.environment != "prod" || var.evidence_retention_locked
      error_message = "En prod evidence_retention_locked debe ser true (Bucket Lock del bucket de evidencia)."
    }

    precondition {
      condition = var.environment != "prod" || (
        tonumber(trimsuffix(var.kms_rotation_period, "s")) <= 7776000 &&
        tonumber(trimsuffix(var.kms_destroy_scheduled_duration, "s")) >= 2592000
      )
      error_message = "En prod kms_rotation_period debe ser <= 7776000s (90 dias) y kms_destroy_scheduled_duration >= 2592000s (30 dias)."
    }

    precondition {
      condition     = length(setintersection(toset(var.key_admin_members), toset(var.evidence_reader_members))) == 0
      error_message = "SEC-011: un miembro no puede estar a la vez en key_admin_members y evidence_reader_members."
    }
  }
}

# ------------------------------------------------------------------------------
# Red privada
# ------------------------------------------------------------------------------
resource "google_compute_network" "vpc" {
  name                    = "${local.prefix}-vpc"
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"

  depends_on = [google_project_service.apis]
}

resource "google_compute_subnetwork" "nodes" {
  name                     = "${local.prefix}-nodes"
  region                   = var.region
  network                  = google_compute_network.vpc.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }

  log_config {
    aggregation_interval = "INTERVAL_10_MIN"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_router" "nat" {
  name    = "${local.prefix}-router"
  region  = var.region
  network = google_compute_network.vpc.id
}

# Egress de los nodos privados; los nodos no tienen IP publica.
resource "google_compute_router_nat" "egress" {
  name                               = "${local.prefix}-nat"
  region                             = var.region
  router                             = google_compute_router.nat.name
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# ------------------------------------------------------------------------------
# Cloud KMS
# ------------------------------------------------------------------------------
# Un key ring no se puede eliminar en GCP; destruirlo con tofu solo lo saca del estado.
resource "google_kms_key_ring" "this" {
  name     = "${local.prefix}-keyring"
  location = var.region

  depends_on = [google_project_service.apis]
}

# Llaves simetricas con rotacion automatica. prevent_destroy es literal (HCL no admite
# variables en lifecycle): para desmontar un entorno de pruebas hay que quitarlo a mano.
resource "google_kms_crypto_key" "symmetric" {
  for_each = local.symmetric_keys

  name                       = each.value
  key_ring                   = google_kms_key_ring.this.id
  purpose                    = "ENCRYPT_DECRYPT"
  rotation_period            = var.kms_rotation_period
  destroy_scheduled_duration = var.kms_destroy_scheduled_duration

  version_template {
    algorithm        = "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = var.kms_protection_level
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Firma Ed25519 de GcpKmsKeyService (AC-15). Ed25519 solo existe con proteccion SOFTWARE
# (no hay HSM para este algoritmo), por eso no usa kms_protection_level. Las llaves
# asimetricas no admiten rotation_period: se rota creando una version.
resource "google_kms_crypto_key" "audit_signing" {
  name                       = "audit-signing"
  key_ring                   = google_kms_key_ring.this.id
  purpose                    = "ASYMMETRIC_SIGN"
  destroy_scheduled_duration = var.kms_destroy_scheduled_duration

  version_template {
    algorithm        = "EC_SIGN_ED25519"
    protection_level = "SOFTWARE"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Los agentes de servicio usan las llaves CMEK; son los unicos con
# cryptoKeyEncrypterDecrypter sobre ellas ademas de las cuentas de la aplicacion.
resource "google_kms_crypto_key_iam_member" "gke_secrets_agent" {
  crypto_key_id = google_kms_crypto_key.symmetric["gke_secrets"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = local.gke_service_agent

  depends_on = [time_sleep.service_agents]
}

resource "google_kms_crypto_key_iam_member" "storage_agent" {
  crypto_key_id = google_kms_crypto_key.symmetric["storage"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = local.gcs_service_agent

  depends_on = [time_sleep.service_agents]
}

# Discos de arranque de los nodos (CMEK). El mismo agente de Compute se usa para el
# StorageClass CMEK de los PVC (ver README).
resource "google_kms_crypto_key_iam_member" "disk_agent" {
  crypto_key_id = google_kms_crypto_key.symmetric["nodes_disk"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = local.gce_service_agent

  depends_on = [time_sleep.service_agents]
}

resource "google_kms_crypto_key_iam_member" "registry_agent" {
  crypto_key_id = google_kms_crypto_key.symmetric["registry"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = local.ar_service_agent

  depends_on = [google_project_service_identity.artifactregistry]
}

# Administradores de llaves: gestionan el ciclo de vida, no pueden cifrar/descifrar
# ni leer objetos (SEC-011).
resource "google_kms_key_ring_iam_member" "key_admins" {
  for_each = toset(var.key_admin_members)

  key_ring_id = google_kms_key_ring.this.id
  role        = "roles/cloudkms.admin"
  member      = each.value
}

# ------------------------------------------------------------------------------
# GKE privado
# ------------------------------------------------------------------------------
resource "google_service_account" "gke_nodes" {
  account_id   = "${local.prefix}-gke-nodes"
  display_name = "Nodos GKE ${local.cluster_name}"
}

resource "google_project_iam_member" "gke_nodes" {
  for_each = toset([
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.gke_nodes.email}"
}

resource "google_container_cluster" "this" {
  name     = local.cluster_name
  location = var.region

  network    = google_compute_network.vpc.id
  subnetwork = google_compute_subnetwork.nodes.id

  # El node pool por defecto no se puede configurar con cuenta propia: se elimina y
  # se crea uno gestionado aparte.
  remove_default_node_pool = true
  initial_node_count       = 1

  networking_mode = "VPC_NATIVE"
  # Dataplane V2 (Cilium) aplica NetworkPolicy de Kubernetes de forma nativa.
  datapath_provider = "ADVANCED_DATAPATH"

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  private_cluster_config {
    enable_private_nodes = true
    # Sin CIDRs autorizados externos el endpoint publico se deshabilita (equivale a AWS).
    enable_private_endpoint = length(var.master_authorized_cidrs) == 0
    master_ipv4_cidr_block  = var.master_ipv4_cidr
  }

  master_authorized_networks_config {
    cidr_blocks {
      cidr_block   = var.subnet_cidr
      display_name = "nodes-subnet"
    }

    dynamic "cidr_blocks" {
      for_each = var.master_authorized_cidrs
      content {
        cidr_block   = cidr_blocks.value
        display_name = "authorized-${cidr_blocks.key}"
      }
    }
  }

  workload_identity_config {
    workload_pool = local.workload_pool
  }

  release_channel {
    channel = var.gke_release_channel
  }

  # Cifra los Secrets de Kubernetes en etcd con CMEK.
  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.symmetric["gke_secrets"].id
  }

  enable_shielded_nodes = true

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "APISERVER", "SCHEDULER", "CONTROLLER_MANAGER", "WORKLOADS"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS", "APISERVER", "SCHEDULER", "CONTROLLER_MANAGER"]

    managed_prometheus {
      enabled = true
    }
  }

  # Prod protegido contra destroy accidental.
  deletion_protection = var.environment == "prod"

  depends_on = [
    google_kms_crypto_key_iam_member.gke_secrets_agent,
    google_kms_crypto_key_iam_member.disk_agent,
    google_project_iam_member.gke_nodes,
    google_compute_router_nat.egress,
  ]
}

resource "google_container_node_pool" "default" {
  name     = "default"
  cluster  = google_container_cluster.this.id
  location = var.region

  autoscaling {
    min_node_count = var.node_min_count
    max_node_count = var.node_max_count
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  node_config {
    machine_type    = var.node_machine_type
    disk_size_gb    = var.node_disk_size_gb
    disk_type       = "pd-balanced"
    image_type      = "COS_CONTAINERD"
    service_account = google_service_account.gke_nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    spot            = var.environment != "prod"

    boot_disk_kms_key = google_kms_crypto_key.symmetric["nodes_disk"].id

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }

    # Los pods no deben alcanzar el servidor de metadatos del nodo.
    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    metadata = {
      disable-legacy-endpoints = "true"
    }
  }
}

# ------------------------------------------------------------------------------
# Buckets GCS
# ------------------------------------------------------------------------------
resource "google_storage_bucket" "access_logs" {
  name                        = "${local.bucket_prefix}-access-logs"
  location                    = var.region
  force_destroy               = var.environment != "prod"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = true
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.symmetric["storage"].id
  }

  lifecycle_rule {
    condition {
      age = var.access_logs_retention_days
    }
    action {
      type = "Delete"
    }
  }

  # Con versionado, las versiones no vigentes tambien caducan.
  lifecycle_rule {
    condition {
      days_since_noncurrent_time = var.access_logs_retention_days
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [google_kms_crypto_key_iam_member.storage_agent]
}

# Grupo de Google que entrega los logs de acceso de Cloud Storage; solo puede crear objetos.
resource "google_storage_bucket_iam_member" "access_logs_writer" {
  bucket = google_storage_bucket.access_logs.name
  role   = "roles/storage.objectCreator"
  member = "group:cloud-storage-analytics@google.com"
}

resource "google_storage_bucket" "cnpg_backups" {
  name                        = "${local.bucket_prefix}-cnpg-backups"
  location                    = var.region
  force_destroy               = var.environment != "prod"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = true
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.symmetric["storage"].id
  }

  logging {
    log_bucket        = google_storage_bucket.access_logs.name
    log_object_prefix = "cnpg-backups-logs"
  }

  lifecycle_rule {
    condition {
      age = var.cnpg_backups_retention_days
    }
    action {
      type = "Delete"
    }
  }

  # Con versionado, las versiones no vigentes tambien caducan.
  lifecycle_rule {
    condition {
      days_since_noncurrent_time = var.cnpg_backups_retention_days
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [
    google_kms_crypto_key_iam_member.storage_agent,
    google_storage_bucket_iam_member.access_logs_writer,
  ]
}

# Bucket WORM de evidencia de auditoria. GcsImmutableStore exige retencion por objeto
# (enable_object_retention) y falla al arrancar si no esta habilitada (AC-09). La
# retencion por defecto del bucket aplica a objetos sin retencion propia.
resource "google_storage_bucket" "audit_evidence" {
  name                        = "${local.bucket_prefix}-audit-evidence"
  location                    = var.region
  force_destroy               = false
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  enable_object_retention     = true

  versioning {
    enabled = true
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.symmetric["storage"].id
  }

  retention_policy {
    retention_period = var.evidence_retention_days * 86400
    is_locked        = var.evidence_retention_locked
  }

  logging {
    log_bucket        = google_storage_bucket.access_logs.name
    log_object_prefix = "audit-evidence-logs"
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [
    google_kms_crypto_key_iam_member.storage_agent,
    google_storage_bucket_iam_member.access_logs_writer,
  ]
}

# ------------------------------------------------------------------------------
# Artifact Registry
# ------------------------------------------------------------------------------
# El agente de servicio de Artifact Registry no existe hasta que se solicita.
resource "google_project_service_identity" "artifactregistry" {
  provider = google-beta

  project = var.project_id
  service = "artifactregistry.googleapis.com"

  depends_on = [google_project_service.apis]
}

resource "google_artifact_registry_repository" "services" {
  repository_id = "${local.prefix}-services"
  location      = var.region
  format        = "DOCKER"
  description   = "Imagenes de los servicios de la plataforma IDP"
  kms_key_name  = google_kms_crypto_key.symmetric["registry"].id

  docker_config {
    immutable_tags = true
  }

  depends_on = [google_kms_crypto_key_iam_member.registry_agent]
}

resource "google_artifact_registry_repository_iam_member" "nodes_reader" {
  repository = google_artifact_registry_repository.services.name
  location   = google_artifact_registry_repository.services.location
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.gke_nodes.email}"
}

# ------------------------------------------------------------------------------
# Cuentas de servicio por componente (Workload Identity) y roles minimos
# ------------------------------------------------------------------------------
resource "google_service_account" "workload" {
  for_each = var.workload_identities

  account_id   = "${local.prefix}-${each.key}"
  display_name = "Workload ${each.key} (${each.value.namespace})"
}

locals {
  # Una entrada por cuenta de Kubernetes: varias KSA pueden asumir la misma cuenta de GCP.
  workload_bindings = merge([
    for key, w in var.workload_identities : {
      for ksa in w.service_accounts : "${key}/${ksa}" => { key = key, namespace = w.namespace, ksa = ksa }
    }
  ]...)
}

resource "google_service_account_iam_member" "workload_identity" {
  for_each = local.workload_bindings

  service_account_id = google_service_account.workload[each.value.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${local.workload_pool}[${each.value.namespace}/${each.value.ksa}]"

  depends_on = [google_container_cluster.this]
}

# OpenBao: solo usa la llave de auto-unseal (no administra llaves ni lee buckets).
resource "google_kms_crypto_key_iam_member" "openbao_unseal" {
  crypto_key_id = google_kms_crypto_key.symmetric["openbao_unseal"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${google_service_account.workload["openbao"].email}"
}

# El seal gcpckms de OpenBao consulta los metadatos de la llave (cryptoKeys.get).
resource "google_kms_crypto_key_iam_member" "openbao_unseal_viewer" {
  crypto_key_id = google_kms_crypto_key.symmetric["openbao_unseal"].id
  role          = "roles/cloudkms.viewer"
  member        = "serviceAccount:${google_service_account.workload["openbao"].email}"
}

# CloudNativePG: lee/escribe/borra backups; el cifrado CMEK lo hace el agente de GCS.
resource "google_storage_bucket_iam_member" "cnpg_backups" {
  bucket = google_storage_bucket.cnpg_backups.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.workload["cnpg"].email}"
}

# audit-service: escribe evidencia WORM (sin delete) y firma con la llave de auditoria.
resource "google_project_iam_custom_role" "evidence_writer" {
  role_id     = "${replace(local.prefix, "-", "_")}_evidence_writer"
  title       = "IDP evidence writer (${var.environment})"
  description = "Escritura WORM de evidencia: crear, leer, listar, holds y retencion por objeto. Sin delete ni permisos de KMS."
  permissions = local.evidence_writer_permissions
}

resource "google_storage_bucket_iam_member" "audit_evidence_writer" {
  bucket = google_storage_bucket.audit_evidence.name
  role   = google_project_iam_custom_role.evidence_writer.id
  member = "serviceAccount:${google_service_account.workload["audit"].email}"
}

resource "google_kms_crypto_key_iam_member" "audit_signer" {
  crypto_key_id = google_kms_crypto_key.audit_signing.id
  role          = "roles/cloudkms.signerVerifier"
  member        = "serviceAccount:${google_service_account.workload["audit"].email}"
}

# Servicios de aplicacion: wrap/unwrap de DEKs con la llave de aplicacion.
resource "google_kms_crypto_key_iam_member" "app_wrap" {
  crypto_key_id = google_kms_crypto_key.symmetric["app_wrap"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${google_service_account.workload["app"].email}"
}

# Lectores de evidencia (auditoria): solo lectura de objetos, sin permisos de KMS.
resource "google_storage_bucket_iam_member" "evidence_readers" {
  for_each = toset(var.evidence_reader_members)

  bucket = google_storage_bucket.audit_evidence.name
  role   = "roles/storage.objectViewer"
  member = each.value
}

# ------------------------------------------------------------------------------
# Llaves por tenant (kms-gcp): una CryptoKey idp-<hex> por tenant, creada en el onboarding
# ------------------------------------------------------------------------------
# El onboarding crea y configura llaves pero no puede usarlas ni leer objetos: el rol no
# incluye useTo*, setIamPolicy ni permisos de storage (SEC-011). Se prefiere un rol
# personalizado a roles/cloudkms.admin porque admin permite reasignarse acceso de uso.
resource "google_project_iam_custom_role" "tenant_key_provisioner" {
  role_id     = "${replace(local.prefix, "-", "_")}_tenant_key_provisioner"
  title       = "IDP tenant key provisioner (${var.environment})"
  description = "Crea y configura CryptoKeys por tenant. Sin uso criptografico, sin IAM y sin storage."
  permissions = [
    "cloudkms.cryptoKeys.create",
    "cloudkms.cryptoKeys.get",
    "cloudkms.cryptoKeys.list",
    "cloudkms.cryptoKeys.update",
    "cloudkms.cryptoKeyVersions.create",
    "cloudkms.cryptoKeyVersions.get",
    "cloudkms.cryptoKeyVersions.list",
    "cloudkms.cryptoKeyVersions.update",
  ]
}

resource "google_kms_key_ring_iam_member" "tenant_onboarding" {
  key_ring_id = google_kms_key_ring.this.id
  role        = google_project_iam_custom_role.tenant_key_provisioner.id
  member      = "serviceAccount:${google_service_account.workload["onboarding"].email}"
}

# Uso de las llaves de tenant: solo recursos cuyo nombre empieza por idp- (las llaves del
# modulo no usan ese prefijo, asi que quedan fuera de esta concesion).
resource "google_kms_key_ring_iam_member" "tenant_keys_wrap" {
  key_ring_id = google_kms_key_ring.this.id
  role        = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member      = "serviceAccount:${google_service_account.workload["app"].email}"

  condition {
    title       = "solo-llaves-de-tenant"
    description = "Limita el uso a CryptoKeys idp-<hex>"
    expression  = "resource.name.contains(\"/cryptoKeys/idp-\")"
  }
}

resource "google_kms_key_ring_iam_member" "tenant_keys_sign" {
  key_ring_id = google_kms_key_ring.this.id
  role        = "roles/cloudkms.signerVerifier"
  member      = "serviceAccount:${google_service_account.workload["audit"].email}"

  condition {
    title       = "solo-llaves-de-tenant"
    description = "Limita la firma a CryptoKeys idp-<hex>"
    expression  = "resource.name.contains(\"/cryptoKeys/idp-\")"
  }
}
