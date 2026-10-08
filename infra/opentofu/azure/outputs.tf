output "tenant_id" {
  description = "El ID del tenant de Entra ID (requerido por el sello azurekeyvault de OpenBao y por DefaultAzureCredential)"
  value       = data.azurerm_client_config.current.tenant_id
}

output "resource_group_name" {
  description = "El nombre del resource group"
  value       = azurerm_resource_group.main.name
}

output "vnet_id" {
  description = "El ID de la VNet creada"
  value       = azurerm_virtual_network.main.id
}

output "aks_nodes_subnet_id" {
  description = "El ID de la subred de nodos de AKS"
  value       = azurerm_subnet.aks_nodes.id
}

output "private_endpoints_subnet_id" {
  description = "El ID de la subred de endpoints privados"
  value       = azurerm_subnet.private_endpoints.id
}

output "nat_gateway_public_ip" {
  description = "La IP publica de salida (egress) del NAT Gateway"
  value       = azurerm_public_ip.nat.ip_address
}

output "aks_cluster_name" {
  description = "El nombre del cluster AKS"
  value       = azurerm_kubernetes_cluster.main.name
}

output "aks_private_fqdn" {
  description = "El FQDN privado del plano de control del cluster AKS"
  value       = azurerm_kubernetes_cluster.main.private_fqdn
}

output "aks_oidc_issuer_url" {
  description = "El issuer OIDC del cluster (workload identity)"
  value       = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "key_vault_platform_name" {
  description = "El nombre del Key Vault de plataforma (vault_name del sello azurekeyvault de OpenBao)"
  value       = azurerm_key_vault.vault["platform"].name
}

output "key_vault_tenant_name" {
  description = "El nombre del Key Vault de llaves de tenant y aplicacion"
  value       = azurerm_key_vault.vault["tenant"].name
}

output "key_vault_tenant_uri" {
  description = "El URI del Key Vault de tenant/aplicacion (idp.kms.azure.vault-url del adaptador kms-azure)"
  value       = azurerm_key_vault.vault["tenant"].vault_uri
}

output "openbao_unseal_key_name" {
  description = "El nombre de la llave de Key Vault para el auto-unseal de OpenBao (key_name)"
  value       = azurerm_key_vault_key.openbao_unseal.name
}

output "storage_account_name" {
  description = "El nombre de la cuenta de storage"
  value       = azurerm_storage_account.main.name
}

output "storage_blob_endpoint" {
  description = "El endpoint de blobs de la cuenta de storage"
  value       = azurerm_storage_account.main.primary_blob_endpoint
}

output "cnpg_backups_container_name" {
  description = "El nombre del contenedor para los backups de la base de datos (CloudNativePG)"
  value       = azurerm_storage_container.cnpg_backups.name
}

output "audit_worm_container_name" {
  description = "El nombre del contenedor WORM por version para la evidencia de auditoria"
  value       = azapi_resource.audit_worm.name
}

output "acr_login_server" {
  description = "El login server del registro ACR"
  value       = azurerm_container_registry.main.login_server
}

output "workload_identity_client_ids" {
  description = "Client ID de cada identidad de workload, para la anotacion azure.workload.identity/client-id de la ServiceAccount"
  value       = { for k, v in azurerm_user_assigned_identity.workload : k => v.client_id }
}

output "log_analytics_workspace_id" {
  description = "El ID del workspace de Log Analytics"
  value       = azurerm_log_analytics_workspace.main.id
}
