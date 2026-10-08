output "network_id" {
  description = "El ID de la VPC creada"
  value       = google_compute_network.vpc.id
}

output "subnet_id" {
  description = "El ID de la subred de los nodos"
  value       = google_compute_subnetwork.nodes.id
}

output "gke_cluster_name" {
  description = "El nombre del cluster GKE"
  value       = google_container_cluster.this.name
}

output "gke_cluster_endpoint" {
  description = "El endpoint del plano de control del cluster GKE"
  value       = google_container_cluster.this.endpoint
}

output "workload_identity_pool" {
  description = "Workload pool del cluster (para anotar las cuentas de Kubernetes)"
  value       = local.workload_pool
}

output "kms_key_ring_id" {
  description = "El ID del key ring de Cloud KMS"
  value       = google_kms_key_ring.this.id
}

output "openbao_kms_key_id" {
  description = "El ID de la llave Cloud KMS para el auto-unseal de OpenBao (seal gcpckms)"
  value       = google_kms_crypto_key.symmetric["openbao_unseal"].id
}

output "app_wrap_kms_key_id" {
  description = "El ID de la llave Cloud KMS de aplicacion para wrap/unwrap de DEKs"
  value       = google_kms_crypto_key.symmetric["app_wrap"].id
}

output "audit_signing_kms_key_id" {
  description = "El ID de la llave Cloud KMS asimetrica (Ed25519) de firma de auditoria"
  value       = google_kms_crypto_key.audit_signing.id
}

output "cnpg_backups_bucket_name" {
  description = "El nombre del bucket GCS para los backups de la base de datos (CloudNativePG)"
  value       = google_storage_bucket.cnpg_backups.name
}

output "audit_evidence_bucket_name" {
  description = "El nombre del bucket GCS WORM de evidencia de auditoria"
  value       = google_storage_bucket.audit_evidence.name
}

output "access_logs_bucket_name" {
  description = "El nombre del bucket GCS de logs de acceso"
  value       = google_storage_bucket.access_logs.name
}

output "artifact_registry_repository_url" {
  description = "La URL del repositorio de Artifact Registry"
  value       = "${google_artifact_registry_repository.services.location}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.services.repository_id}"
}

output "service_account_emails" {
  description = "Emails de las cuentas de servicio por componente (openbao, cnpg, audit, app, onboarding) y de los nodos"
  value = merge(
    { for k, sa in google_service_account.workload : k => sa.email },
    { gke_nodes = google_service_account.gke_nodes.email }
  )
}

output "disk_kms_key_id" {
  description = "Llave CMEK de discos: boot disk de los nodos y StorageClass CMEK de los PVC (CNPG)"
  value       = google_kms_crypto_key.symmetric["nodes_disk"].id
}
