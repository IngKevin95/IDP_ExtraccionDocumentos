provider "azurerm" {
  subscription_id = var.subscription_id

  # Sin llaves de cuenta: el plano de datos de storage se opera solo con Entra ID.
  storage_use_azuread = true

  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}

provider "azapi" {
  subscription_id = var.subscription_id
}

data "azurerm_client_config" "current" {}

locals {
  prefix   = "${var.project_name}-${var.environment}"
  name_raw = lower(replace("${var.project_name}${var.environment}", "-", ""))

  # Nombres globales con limite de longitud: el sufijo y el tipo nunca se truncan.
  key_vault_names = {
    platform = "${substr(local.name_raw, 0, 24 - 3 - length(var.unique_suffix))}kp${var.unique_suffix}"
    tenant   = "${substr(local.name_raw, 0, 24 - 3 - length(var.unique_suffix))}kt${var.unique_suffix}"
  }
  storage_name    = "${substr(local.name_raw, 0, 24 - 2 - length(var.unique_suffix))}st${var.unique_suffix}"
  acr_name        = "${substr(local.name_raw, 0, 50 - 3 - length(var.unique_suffix))}acr${var.unique_suffix}"
  cluster_name    = "${local.prefix}-aks"
  key_type        = var.key_vault_sku == "premium" ? "RSA-HSM" : "RSA"
  tags            = merge(var.tags, { Project = var.project_name, Environment = var.environment, ManagedBy = "OpenTofu" })
  aks_subnet_cidr = cidrsubnet(var.vnet_cidr, 2, 0)
  pe_subnet_cidr  = cidrsubnet(var.vnet_cidr, 8, 255)
  oidc_audience   = ["api://AzureADTokenExchange"]
  # Administradores adicionales sin duplicar al deployer, que ya recibe el rol.
  extra_key_admin_ids = toset(setsubtract(var.key_admin_object_ids, [data.azurerm_client_config.current.object_id]))
}

resource "azurerm_resource_group" "main" {
  name     = "${local.prefix}-rg"
  location = var.location
  tags     = local.tags
}

# ------------------------------------------------------------------------------
# Observabilidad (Log Analytics)
# ------------------------------------------------------------------------------
resource "azurerm_log_analytics_workspace" "main" {
  name                = "${local.prefix}-law"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = var.log_retention_days
  tags                = local.tags
}

# ------------------------------------------------------------------------------
# Red privada: VNet, subredes, NAT Gateway y NSG con denegar por defecto
# ------------------------------------------------------------------------------
resource "azurerm_virtual_network" "main" {
  name                = "${local.prefix}-vnet"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  address_space       = [var.vnet_cidr]
  tags                = local.tags
}

resource "azurerm_subnet" "aks_nodes" {
  name                 = "aks-nodes"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = [local.aks_subnet_cidr]
}

resource "azurerm_subnet" "private_endpoints" {
  name                              = "private-endpoints"
  resource_group_name               = azurerm_resource_group.main.name
  virtual_network_name              = azurerm_virtual_network.main.name
  address_prefixes                  = [local.pe_subnet_cidr]
  private_endpoint_network_policies = "Enabled"
}

resource "azurerm_public_ip" "nat" {
  name                = "${local.prefix}-nat-pip"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = local.tags
}

resource "azurerm_nat_gateway" "main" {
  name                    = "${local.prefix}-nat"
  location                = azurerm_resource_group.main.location
  resource_group_name     = azurerm_resource_group.main.name
  sku_name                = "Standard"
  idle_timeout_in_minutes = 10
  tags                    = local.tags
}

resource "azurerm_nat_gateway_public_ip_association" "main" {
  nat_gateway_id       = azurerm_nat_gateway.main.id
  public_ip_address_id = azurerm_public_ip.nat.id
}

resource "azurerm_subnet_nat_gateway_association" "aks_nodes" {
  subnet_id      = azurerm_subnet.aks_nodes.id
  nat_gateway_id = azurerm_nat_gateway.main.id
}

# El trafico entrante se limita a la VNet y al balanceador de Azure; el resto se
# deniega de forma explicita. La salida a Internet pasa por el NAT Gateway.
resource "azurerm_network_security_group" "aks_nodes" {
  name                = "${local.prefix}-aks-nodes-nsg"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags

  security_rule {
    name                       = "allow-vnet-inbound"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "VirtualNetwork"
  }

  security_rule {
    name                       = "allow-azure-lb-inbound"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "AzureLoadBalancer"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "deny-all-inbound"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_security_group" "private_endpoints" {
  name                = "${local.prefix}-pe-nsg"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags

  security_rule {
    name                       = "allow-vnet-inbound"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "deny-all-inbound"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "aks_nodes" {
  subnet_id                 = azurerm_subnet.aks_nodes.id
  network_security_group_id = azurerm_network_security_group.aks_nodes.id
}

resource "azurerm_subnet_network_security_group_association" "private_endpoints" {
  subnet_id                 = azurerm_subnet.private_endpoints.id
  network_security_group_id = azurerm_network_security_group.private_endpoints.id
}

# Zonas DNS privadas para resolver los endpoints privados desde la VNet.
locals {
  private_dns_zones = {
    vault = "privatelink.vaultcore.azure.net"
    blob  = "privatelink.blob.core.windows.net"
    acr   = "privatelink.azurecr.io"
  }
}

resource "azurerm_private_dns_zone" "zones" {
  for_each            = local.private_dns_zones
  name                = each.value
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "zones" {
  for_each              = local.private_dns_zones
  name                  = "${local.prefix}-${each.key}-link"
  resource_group_name   = azurerm_resource_group.main.name
  private_dns_zone_name = azurerm_private_dns_zone.zones[each.key].name
  virtual_network_id    = azurerm_virtual_network.main.id
  tags                  = local.tags
}

# ------------------------------------------------------------------------------
# Key Vault: dos vaults para aislar llaves de plataforma y de aplicacion.
#   platform: auto-unseal de OpenBao, discos de AKS, CMK de storage y de ACR.
#   tenant:   llaves de aplicacion por tenant (KEK y firma), creadas por el onboarding.
# Las identidades de aplicacion solo reciben roles sobre el vault tenant.
# ------------------------------------------------------------------------------
locals {
  key_vaults = toset(["platform", "tenant"])
}

# Los dos vaults conservan endpoint publico con firewall en Deny: es la unica forma
# de que Storage, ACR y el Disk Encryption Set (servicios de confianza) lean su
# llave. Solo entran las IPs de key_vault_allowed_ip_ranges y el trafico de
# confianza; los pods usan el endpoint privado.
resource "azurerm_key_vault" "vault" {
  for_each            = local.key_vaults
  name                = local.key_vault_names[each.key]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tenant_id           = data.azurerm_client_config.current.tenant_id
  sku_name            = var.key_vault_sku

  rbac_authorization_enabled = true
  purge_protection_enabled   = true
  soft_delete_retention_days = var.key_vault_soft_delete_retention_days

  public_network_access_enabled = true
  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.key_vault_allowed_ip_ranges
  }

  tags = local.tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_private_endpoint" "key_vault" {
  for_each            = local.key_vaults
  name                = "${local.prefix}-kv-${each.key}-pe"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.private_endpoints.id
  tags                = local.tags

  private_service_connection {
    name                           = "key-vault-${each.key}"
    private_connection_resource_id = azurerm_key_vault.vault[each.key].id
    subresource_names              = ["vault"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["vault"].id]
  }
}

# Quien aplica OpenTofu necesita crear llaves: administrador de llaves, sin roles de blobs.
resource "azurerm_role_assignment" "deployer_key_officer" {
  for_each             = local.key_vaults
  scope                = azurerm_key_vault.vault[each.key].id
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "key_admins" {
  for_each = {
    for pair in setproduct(local.key_vaults, local.extra_key_admin_ids) :
    "${pair[0]}|${pair[1]}" => { vault = pair[0], principal = pair[1] }
  }
  scope                = azurerm_key_vault.vault[each.value.vault].id
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = each.value.principal
}

resource "azurerm_management_lock" "key_vault" {
  for_each   = var.enable_resource_locks ? local.key_vaults : toset([])
  name       = "no-delete"
  scope      = azurerm_key_vault.vault[each.key].id
  lock_level = "CanNotDelete"
  notes      = "Vault de llaves: borrado solo tras retirar el lock de forma deliberada."
}

# Llave de auto-unseal de OpenBao. Sin rotacion automatica: una rotacion exige
# re-envolver la llave maestra de OpenBao (ver README).
resource "azurerm_key_vault_key" "openbao_unseal" {
  name         = "openbao-unseal"
  key_vault_id = azurerm_key_vault.vault["platform"].id
  key_type     = local.key_type
  key_size     = 3072
  key_opts     = ["wrapKey", "unwrapKey"]

  depends_on = [azurerm_role_assignment.deployer_key_officer["platform"], azurerm_private_endpoint.key_vault["platform"]]

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_key_vault_key" "disk_encryption" {
  name         = "aks-disk-encryption"
  key_vault_id = azurerm_key_vault.vault["platform"].id
  key_type     = local.key_type
  key_size     = 3072
  key_opts     = ["wrapKey", "unwrapKey"]

  rotation_policy {
    expire_after         = "P1Y"
    notify_before_expiry = "P30D"

    automatic {
      time_before_expiry = "P60D"
    }
  }

  depends_on = [azurerm_role_assignment.deployer_key_officer["platform"], azurerm_private_endpoint.key_vault["platform"]]

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_key_vault_key" "storage_cmk" {
  name         = "storage-cmk"
  key_vault_id = azurerm_key_vault.vault["platform"].id
  key_type     = local.key_type
  key_size     = 3072
  key_opts     = ["wrapKey", "unwrapKey"]

  rotation_policy {
    expire_after         = "P1Y"
    notify_before_expiry = "P30D"

    automatic {
      time_before_expiry = "P60D"
    }
  }

  depends_on = [azurerm_role_assignment.deployer_key_officer["platform"], azurerm_private_endpoint.key_vault["platform"]]

  lifecycle {
    prevent_destroy = true
  }
}

# Llave de CMK del registro ACR (rotacion automatica).
resource "azurerm_key_vault_key" "acr_cmk" {
  name         = "acr-cmk"
  key_vault_id = azurerm_key_vault.vault["platform"].id
  key_type     = local.key_type
  key_size     = 3072
  key_opts     = ["wrapKey", "unwrapKey"]

  rotation_policy {
    expire_after         = "P1Y"
    notify_before_expiry = "P30D"

    automatic {
      time_before_expiry = "P60D"
    }
  }

  depends_on = [azurerm_role_assignment.deployer_key_officer["platform"], azurerm_private_endpoint.key_vault["platform"]]

  lifecycle {
    prevent_destroy = true
  }
}

# Las llaves de aplicacion por tenant (KEK y firma) NO se crean aqui: las
# aprovisiona el onboarding de tenants en el vault "tenant" con la identidad
# tenant-onboarding.

locals {
  platform_key_scopes = {
    "openbao-unseal"  = azurerm_key_vault_key.openbao_unseal.resource_versionless_id
    "disk-encryption" = azurerm_key_vault_key.disk_encryption.resource_versionless_id
    "storage-cmk"     = azurerm_key_vault_key.storage_cmk.resource_versionless_id
    "acr-cmk"         = azurerm_key_vault_key.acr_cmk.resource_versionless_id
  }
}

# ------------------------------------------------------------------------------
# Cifrado de discos de AKS (Disk Encryption Set con CMK)
# ------------------------------------------------------------------------------
resource "azurerm_disk_encryption_set" "aks" {
  name                      = "${local.prefix}-des"
  location                  = azurerm_resource_group.main.location
  resource_group_name       = azurerm_resource_group.main.name
  key_vault_key_id          = azurerm_key_vault_key.disk_encryption.versionless_id
  auto_key_rotation_enabled = true
  tags                      = local.tags

  identity {
    type = "SystemAssigned"
  }
}

resource "azurerm_role_assignment" "des_key_access" {
  scope                = azurerm_key_vault_key.disk_encryption.resource_versionless_id
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_disk_encryption_set.aks.identity[0].principal_id
}

# ------------------------------------------------------------------------------
# AKS privado
# ------------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "aks_control_plane" {
  name                = "${local.prefix}-aks-cp-id"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

# Minimo para BYO VNet con NAT Gateway y para usar el Disk Encryption Set.
resource "azurerm_role_assignment" "aks_cp_subnet" {
  scope                = azurerm_subnet.aks_nodes.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks_control_plane.principal_id
}

resource "azurerm_role_assignment" "aks_cp_des" {
  scope                = azurerm_disk_encryption_set.aks.id
  role_definition_name = "Reader"
  principal_id         = azurerm_user_assigned_identity.aks_control_plane.principal_id
}

resource "azurerm_kubernetes_cluster" "main" {
  name                = local.cluster_name
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  dns_prefix          = local.cluster_name
  kubernetes_version  = var.aks_kubernetes_version
  sku_tier            = var.aks_sku_tier

  private_cluster_enabled             = true
  private_cluster_public_fqdn_enabled = false
  private_dns_zone_id                 = "System"
  oidc_issuer_enabled                 = true
  workload_identity_enabled           = true
  azure_policy_enabled                = var.aks_azure_policy_enabled
  local_account_disabled              = true
  run_command_enabled                 = false
  image_cleaner_enabled               = true
  disk_encryption_set_id              = azurerm_disk_encryption_set.aks.id
  automatic_upgrade_channel           = "patch"
  node_os_upgrade_channel             = "NodeImage"
  tags                                = local.tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks_control_plane.id]
  }

  azure_active_directory_role_based_access_control {
    azure_rbac_enabled     = true
    admin_group_object_ids = var.aks_admin_group_object_ids
  }

  default_node_pool {
    name                         = "system"
    vm_size                      = var.node_system_vm_size
    vnet_subnet_id               = azurerm_subnet.aks_nodes.id
    only_critical_addons_enabled = true
    auto_scaling_enabled         = true
    min_count                    = 2
    max_count                    = 3
    zones                        = var.node_availability_zones
    node_public_ip_enabled       = false
    host_encryption_enabled      = var.aks_host_encryption_enabled
    os_disk_type                 = "Managed"
    temporary_name_for_rotation  = "systemtmp"

    upgrade_settings {
      max_surge = "33%"
    }
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_policy      = "cilium"
    network_data_plane  = "cilium"
    outbound_type       = "userAssignedNATGateway"
    load_balancer_sku   = "standard"
    pod_cidr            = var.aks_pod_cidr
    service_cidr        = var.aks_service_cidr
    dns_service_ip      = var.aks_dns_service_ip
  }

  lifecycle {
    # La API de AKS rechaza rangos autorizados en clusteres privados.
    precondition {
      condition     = length(var.aks_authorized_ip_ranges) == 0
      error_message = "aks_authorized_ip_ranges no aplica a un cluster privado (private_cluster_enabled = true)."
    }
  }

  dynamic "api_server_access_profile" {
    for_each = length(var.aks_authorized_ip_ranges) > 0 ? [1] : []
    content {
      authorized_ip_ranges = var.aks_authorized_ip_ranges
    }
  }

  dynamic "microsoft_defender" {
    for_each = var.aks_defender_enabled ? [1] : []
    content {
      log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
    }
  }

  depends_on = [
    azurerm_role_assignment.aks_cp_subnet,
    azurerm_role_assignment.aks_cp_des,
    azurerm_role_assignment.des_key_access,
    azurerm_subnet_nat_gateway_association.aks_nodes,
    azurerm_nat_gateway_public_ip_association.main,
    azurerm_subnet_network_security_group_association.aks_nodes,
  ]
}

# Las cargas de la plataforma corren en el pool de usuario; el pool system queda
# reservado para componentes criticos del cluster.
resource "azurerm_kubernetes_cluster_node_pool" "user" {
  name                    = "user"
  kubernetes_cluster_id   = azurerm_kubernetes_cluster.main.id
  mode                    = "User"
  vm_size                 = var.node_vm_size
  vnet_subnet_id          = azurerm_subnet.aks_nodes.id
  auto_scaling_enabled    = true
  min_count               = var.node_min_count
  max_count               = var.node_max_count
  zones                   = var.node_availability_zones
  node_public_ip_enabled  = false
  host_encryption_enabled = var.aks_host_encryption_enabled
  os_disk_type            = "Managed"
  tags                    = local.tags

  upgrade_settings {
    max_surge = "33%"
  }
}

resource "azurerm_monitor_diagnostic_setting" "aks" {
  name                       = "to-log-analytics"
  target_resource_id         = azurerm_kubernetes_cluster.main.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category = "kube-apiserver"
  }
  enabled_log {
    category = "kube-audit-admin"
  }
  enabled_log {
    category = "kube-controller-manager"
  }
  enabled_log {
    category = "kube-scheduler"
  }
  enabled_log {
    category = "cloud-controller-manager"
  }
  enabled_log {
    category = "guard"
  }
}

resource "azurerm_monitor_diagnostic_setting" "key_vault" {
  for_each                   = local.key_vaults
  name                       = "to-log-analytics"
  target_resource_id         = azurerm_key_vault.vault[each.key].id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category = "AuditEvent"
  }
}

# ------------------------------------------------------------------------------
# Storage: backups de CloudNativePG y evidencia de auditoria WORM
# ------------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "storage_cmk" {
  name                = "${local.prefix}-storage-cmk-id"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

# Identidad usada solo por la cuenta de storage para leer la llave: wrap/unwrap, sin datos.
resource "azurerm_role_assignment" "storage_cmk_key_access" {
  scope                = azurerm_key_vault_key.storage_cmk.resource_versionless_id
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_user_assigned_identity.storage_cmk.principal_id
}

resource "azurerm_storage_account" "main" {
  name                     = local.storage_name
  location                 = azurerm_resource_group.main.location
  resource_group_name      = azurerm_resource_group.main.name
  account_kind             = "StorageV2"
  account_tier             = "Standard"
  account_replication_type = var.storage_replication_type

  min_tls_version                   = "TLS1_2"
  https_traffic_only_enabled        = true
  allow_nested_items_to_be_public   = false
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  public_network_access_enabled     = false
  infrastructure_encryption_enabled = true
  cross_tenant_replication_enabled  = false
  tags                              = local.tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.storage_cmk.id]
  }

  customer_managed_key {
    key_vault_key_id          = azurerm_key_vault_key.storage_cmk.versionless_id
    user_assigned_identity_id = azurerm_user_assigned_identity.storage_cmk.id
  }

  # El versionado es requisito de immutable storage con versiones (WORM por version).
  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 14
    }
    container_delete_retention_policy {
      days = 14
    }
  }

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
  }

  depends_on = [azurerm_role_assignment.storage_cmk_key_access]

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_management_lock" "storage" {
  count      = var.enable_resource_locks ? 1 : 0
  name       = "no-delete"
  scope      = azurerm_storage_account.main.id
  lock_level = "CanNotDelete"
  notes      = "Cuenta con backups y evidencia WORM: borrado solo tras retirar el lock de forma deliberada."
}

resource "azurerm_private_endpoint" "storage_blob" {
  name                = "${local.prefix}-blob-pe"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.private_endpoints.id
  tags                = local.tags

  private_service_connection {
    name                           = "storage-blob"
    private_connection_resource_id = azurerm_storage_account.main.id
    subresource_names              = ["blob"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["blob"].id]
  }
}

resource "azurerm_storage_container" "cnpg_backups" {
  name                  = "cnpg-backups"
  storage_account_id    = azurerm_storage_account.main.id
  container_access_type = "private"
}

resource "azurerm_storage_management_policy" "cnpg_backups" {
  storage_account_id = azurerm_storage_account.main.id

  rule {
    name    = "cnpg-backups-retention"
    enabled = true

    filters {
      blob_types   = ["blockBlob"]
      prefix_match = ["${azurerm_storage_container.cnpg_backups.name}/"]
    }

    actions {
      base_blob {
        delete_after_days_since_modification_greater_than = var.cnpg_backup_retention_days
      }
      version {
        delete_after_days_since_creation = var.cnpg_backup_retention_days
      }
    }
  }
}

# Rol de escritura de evidencia sin delete: leer, listar, escribir y agregar. El
# borrado (blobs y versiones) queda fuera; ademas el WORM por version lo impide
# mientras dure la retencion. Fijar retencion o legal hold por version se hace con
# Set Blob Immutability Policy, que usa el permiso de escritura.
resource "azurerm_role_definition" "audit_worm_writer" {
  name        = "${local.prefix}-audit-worm-writer"
  scope       = azurerm_storage_account.main.id
  description = "Lectura, listado y escritura de blobs sin permiso de borrado (evidencia WORM)."

  permissions {
    data_actions = [
      "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read",
      "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write",
      "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/add/action",
    ]
  }

  assignable_scopes = [azurerm_storage_account.main.id]
}

# azurerm no expone immutableStorageWithVersioning a nivel de contenedor
# (azurerm_storage_container solo trae has_immutability_policy como atributo de
# lectura), por eso el contenedor WORM se declara con azapi sobre la API ARM.
# El adaptador AzureBlobImmutableStore falla al arrancar si esto no esta
# habilitado (AC-09). No se activa a nivel de cuenta para no volver inmutables
# los backups de CloudNativePG.
resource "azapi_resource" "audit_worm" {
  type      = "Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01"
  name      = "audit-worm"
  parent_id = "${azurerm_storage_account.main.id}/blobServices/default"

  body = {
    properties = {
      publicAccess = "None"
      immutableStorageWithVersioning = {
        enabled = true
      }
    }
  }

  lifecycle {
    prevent_destroy = true

    # En prod la evidencia de auditoria no puede quedar sin retencion por defecto.
    precondition {
      condition     = var.environment != "prod" || var.audit_worm_default_retention_days > 0
      error_message = "En prod audit_worm_default_retention_days debe ser mayor que 0."
    }
  }
}

# Politica por defecto para versiones nuevas del contenedor (nace Unlocked).
resource "azapi_resource" "audit_worm_default_policy" {
  count = var.audit_worm_default_retention_days > 0 ? 1 : 0

  type      = "Microsoft.Storage/storageAccounts/blobServices/containers/immutabilityPolicies@2023-05-01"
  name      = "default"
  parent_id = azapi_resource.audit_worm.id

  body = {
    properties = {
      immutabilityPeriodSinceCreationInDays = var.audit_worm_default_retention_days
      allowProtectedAppendWrites            = false
    }
  }
}

resource "azurerm_monitor_diagnostic_setting" "storage_blob" {
  name                       = "to-log-analytics"
  target_resource_id         = "${azurerm_storage_account.main.id}/blobServices/default"
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category = "StorageRead"
  }
  enabled_log {
    category = "StorageWrite"
  }
  enabled_log {
    category = "StorageDelete"
  }
}

# ------------------------------------------------------------------------------
# Azure Container Registry
# ------------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "acr_cmk" {
  name                = "${local.prefix}-acr-cmk-id"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_role_assignment" "acr_cmk_key_access" {
  scope                = azurerm_key_vault_key.acr_cmk.resource_versionless_id
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_user_assigned_identity.acr_cmk.principal_id
}

resource "azurerm_container_registry" "main" {
  name                = local.acr_name
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  # Premium es requisito de endpoint privado, cuarentena y retencion.
  sku                           = "Premium"
  admin_enabled                 = false
  anonymous_pull_enabled        = false
  public_network_access_enabled = false
  network_rule_bypass_option    = "AzureServices"
  zone_redundancy_enabled       = var.acr_zone_redundancy_enabled
  quarantine_policy_enabled     = var.acr_quarantine_enabled
  retention_policy_in_days      = var.acr_untagged_retention_days > 0 ? var.acr_untagged_retention_days : null
  tags                          = local.tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.acr_cmk.id]
  }

  encryption {
    key_vault_key_id   = azurerm_key_vault_key.acr_cmk.versionless_id
    identity_client_id = azurerm_user_assigned_identity.acr_cmk.client_id
  }

  depends_on = [azurerm_role_assignment.acr_cmk_key_access]
}

resource "azurerm_private_endpoint" "acr" {
  name                = "${local.prefix}-acr-pe"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.private_endpoints.id
  tags                = local.tags

  private_service_connection {
    name                           = "registry"
    private_connection_resource_id = azurerm_container_registry.main.id
    subresource_names              = ["registry"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["acr"].id]
  }
}

# Los nodos solo descargan imagenes: AcrPull a la identidad del kubelet.
resource "azurerm_role_assignment" "kubelet_acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}

# ------------------------------------------------------------------------------
# Identidades de workload (federadas con el issuer OIDC de AKS) y roles minimos
# ------------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "workload" {
  for_each            = var.workload_identities
  name                = "${local.prefix}-${each.key}-id"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "workload" {
  for_each  = var.workload_identities
  name      = "${each.key}-k8s"
  parent_id = azurerm_user_assigned_identity.workload[each.key].id
  audience  = local.oidc_audience
  issuer    = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject   = "system:serviceaccount:${each.value.namespace}:${each.value.service_account}"
}

locals {
  blob_container_scopes = {
    cnpg_backups = azurerm_storage_container.cnpg_backups.resource_manager_id
    audit_worm   = azapi_resource.audit_worm.id
  }

  # Crypto User y Crypto Officer solo se asignan sobre el vault tenant; el rol
  # Crypto Service Encryption User solo sobre llaves de plataforma concretas
  # (las validaciones de workload_identities fuerzan esta separacion).
  workload_key_assignments = merge([
    for name, w in var.workload_identities : (
      length(w.platform_keys) > 0 ? {
        for key_name in w.platform_keys :
        "${name}|${key_name}" => {
          identity = name
          role     = w.key_role
          scope    = local.platform_key_scopes[key_name]
        }
        } : {
        "${name}|tenant-vault" = {
          identity = name
          role     = w.key_role
          scope    = azurerm_key_vault.vault["tenant"].id
        }
      }
    ) if w.key_role != null
  ]...)

  workload_blob_assignments = merge([
    for name, w in var.workload_identities : {
      for container, role in w.blob_containers :
      "${name}|${container}" => {
        identity = name
        role     = role == "AuditWormWriter" ? azurerm_role_definition.audit_worm_writer.name : role
        scope    = local.blob_container_scopes[container]
      }
    }
  ]...)
}

resource "azurerm_role_assignment" "workload_keys" {
  for_each             = local.workload_key_assignments
  scope                = each.value.scope
  role_definition_name = each.value.role
  principal_id         = azurerm_user_assigned_identity.workload[each.value.identity].principal_id
}

resource "azurerm_role_assignment" "workload_blobs" {
  for_each             = local.workload_blob_assignments
  scope                = each.value.scope
  role_definition_name = each.value.role
  principal_id         = azurerm_user_assigned_identity.workload[each.value.identity].principal_id
}
