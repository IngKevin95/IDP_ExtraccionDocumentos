# Azure Infrastructure para Plataforma IDP

Este módulo despliega la infraestructura base para ejecutar la plataforma IDP en Microsoft Azure. Replica el alcance del módulo `../aws` usando recursos nativos de `azurerm` (sin módulos de registro, para que cada atributo sea auditable) y `azapi` solo donde `azurerm` no expone la API.

## Recursos Creados

1. **Red privada**
   - Resource group, VNet y dos subredes: nodos de AKS y endpoints privados.
   - NAT Gateway con IP pública estática para el egress; los nodos no tienen IP pública.
   - NSG por subred con denegar por defecto: el inbound se limita a la VNet (y al balanceador de Azure en nodos) y el resto se deniega de forma explícita.
   - Zonas DNS privadas (`privatelink.vaultcore.azure.net`, `privatelink.blob.core.windows.net`, `privatelink.azurecr.io`) enlazadas a la VNet.

2. **AKS privado**
   - `private_cluster_enabled`, cuentas locales deshabilitadas, RBAC con Entra ID (Azure RBAC), `run_command` deshabilitado.
   - OIDC issuer y Workload Identity habilitados.
   - Azure CNI Overlay con Cilium (network policy), salida por NAT Gateway (`userAssignedNATGateway`).
   - Discos de nodos cifrados con Disk Encryption Set y CMK de Key Vault (rotación automática).
   - Identidad del plano de control gestionada por el usuario, solo con `Network Contributor` sobre la subred de nodos y `Reader` sobre el Disk Encryption Set.
   - Azure Policy (variable `aks_azure_policy_enabled`) y Defender for Containers (variable `aks_defender_enabled`, apagado por defecto).
   - Logs del plano de control (`kube-apiserver`, `kube-audit-admin`, etc.) a Log Analytics.
   - Pool `system` reservado a componentes críticos (`only_critical_addons_enabled`) y pool `user` autoescalado para las cargas.
   - `aks_authorized_ip_ranges` se mantiene por compatibilidad, pero la API de AKS rechaza rangos autorizados en clusteres privados: una precondition del cluster exige que esté vacío. Valida además que cada rango sea CIDR con prefijo /8 o mayor.

3. **Key Vault (dos vaults)**
   - `platform` (`<proyecto><env>kp<sufijo>`): llaves de plataforma `openbao-unseal` (auto-unseal de OpenBao, sin rotación automática), `aks-disk-encryption`, `storage-cmk` y `acr-cmk` (estas tres con rotación automática).
   - `tenant` (`<proyecto><env>kt<sufijo>`): llaves de aplicación por tenant (KEK y firma). **No se crean aquí**: las aprovisiona el onboarding de tenants con la identidad `tenant-onboarding`. El adaptador `kms-azure` apunta a este vault (`idp.kms.azure.vault-url` = output `key_vault_tenant_uri`).
   - Las identidades de aplicación nunca reciben roles sobre el vault `platform`; una validación de `workload_identities` lo impide.
   - SKU `premium` por defecto (llaves `RSA-HSM`); con `key_vault_sku = "standard"` las llaves son por software.
   - Autorización por RBAC (sin access policies), soft delete y purge protection, endpoint privado por vault y auditoría (`AuditEvent`) a Log Analytics.
   - Ambos vaults mantienen el endpoint público habilitado con el firewall en `Deny`: Storage, ACR y el Disk Encryption Set leen su llave como servicios de confianza (`bypass = AzureServices`), lo que no funciona con el acceso público deshabilitado. Solo entran las IPs de `key_vault_allowed_ip_ranges` (CIDR con prefijo /8 o mayor) y el tráfico de confianza; los pods usan el endpoint privado.

4. **Storage**
   - Cuenta con acceso público deshabilitado (`public_network_access_enabled = false`, sin blobs anónimos), TLS 1.2 mínimo, shared key deshabilitado (solo Entra ID), CMK de Key Vault, cifrado de infraestructura, versionado de blobs y endpoint privado.
   - Contenedor `cnpg-backups` para CloudNativePG, con lifecycle (`cnpg_backup_retention_days`) sobre blobs y versiones.
   - Contenedor `audit-worm` con `immutableStorageWithVersioning.enabled = true` (WORM por versión) y política por defecto opcional vía `audit_worm_default_retention_days`. En `prod` el valor debe ser mayor que 0 (precondition).
   - `prevent_destroy` fijo en Key Vault, llaves, cuenta de storage y `audit-worm`, más locks `CanNotDelete` en vaults y cuenta (`enable_resource_locks`, activo por defecto). HCL no admite variables en `prevent_destroy`: destruir un entorno de pruebas exige retirar esos bloques `lifecycle` y poner `enable_resource_locks = false`.

5. **ACR**
   - Cifrado con CMK (`acr-cmk` del vault `platform`, identidad gestionada propia). Log Analytics no usa CMK: exige un cluster dedicado de Log Analytics (costo y alta de capacidad), por lo que queda como mejora pendiente.
   - SKU Premium (requisito de endpoint privado), admin deshabilitado, sin pull anónimo, acceso público deshabilitado, endpoint privado.
   - Retención de manifiestos sin tag y cuarentena opcionales. ACR no ofrece tags inmutables a nivel de registro; la inmutabilidad de tags se gestiona por repositorio (`az acr repository update --write-enabled false`) o por el flujo de firma (ADR 0022).
   - Los nodos reciben solo `AcrPull` (identidad del kubelet).

6. **Identidades de workload**
   - Una identidad gestionada por componente (`workload_identities`) con federated identity credential contra el issuer OIDC de AKS y la ServiceAccount indicada. Los `client_id` salen en el output `workload_identity_client_ids` para anotar la ServiceAccount con `azure.workload.identity/client-id`.
   - Nombres de ServiceAccount: el chart `idp` renderiza `<release>-<servicio>` (o solo `<servicio>` si el release ya lo contiene o hay `fullnameOverride`). Los defaults (`idp-audit-service`, `idp-tenant-service`) asumen release `idp`; `idp-postgres` es el nombre del Cluster de CNPG; `openbao` asume el release `openbao` del chart oficial. El nombre del release de Argo CD y el de la ServiceAccount de OpenBao no se pudieron confirmar en el repositorio: confírmalos y ajusta `workload_identities` antes del primer apply.

## Contenedor WORM y `AzureBlobImmutableStore` (AC-09)

El adaptador `AzureBlobImmutableStore` exige WORM por versión en el contenedor y falla al arrancar si falta. `azurerm_storage_container` no expone `immutableStorageWithVersioning`, por lo que el contenedor se declara con `azapi_resource` (`Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01`), con el provider `azure/azapi` fijado a `~> 2.4`. Reglas relevantes:

- El WORM por versión se habilita por contenedor, no a nivel de cuenta: así los backups de CloudNativePG no quedan inmutables. No se puede deshabilitar ni se pueden borrar contenedores con blobs.
- La política por defecto (`audit_worm_default_retention_days > 0`) se crea `Unlocked`. Bloquearla es irreversible y es una decisión operativa manual (`az storage container immutability-policy lock`). Con valor `0` no hay política por defecto y el adaptador fija la retención por versión.
- Las versiones ya escritas conservan su retención aunque se modifique la política por defecto.

## Separación de funciones IAM (SEC-011 / AC-20)

Ninguna identidad combina administración de llaves con datos de blobs. Los roles de datos de blobs solo existen sobre el alcance de un contenedor; los de llaves, sobre el vault o una llave concreta.

| Identidad | Rol | Alcance |
|---|---|---|
| Quien aplica OpenTofu (deployer) | Key Vault Crypto Officer | Vaults `platform` y `tenant` |
| `key_admin_object_ids` (sin duplicar al deployer) | Key Vault Crypto Officer | Vaults `platform` y `tenant` |
| `tenant-onboarding` | Key Vault Crypto Officer | Vault `tenant` |
| `openbao` | Key Vault Crypto Service Encryption User | Llave `openbao-unseal` (vault `platform`) |
| `cnpg` | Storage Blob Data Contributor | Contenedor `cnpg-backups` |
| `audit-service` | Rol custom `<proyecto>-<env>-audit-worm-writer` (read, write, add; sin delete) | Contenedor `audit-worm` |
| `audit-service` | Key Vault Crypto User | Vault `tenant` |
| Identidad `storage-cmk` | Key Vault Crypto Service Encryption User | Llave `storage-cmk` |
| Identidad `acr-cmk` | Key Vault Crypto Service Encryption User | Llave `acr-cmk` |
| Disk Encryption Set | Key Vault Crypto Service Encryption User | Llave `aks-disk-encryption` |
| Identidad del plano de control AKS | Network Contributor | Subred de nodos |
| Identidad del plano de control AKS | Reader | Disk Encryption Set |
| Kubelet de AKS | AcrPull | ACR |

Controles en el código:

- Una validación de `workload_identities` rechaza `Key Vault Crypto Officer` combinado con cualquier `blob_containers`.
- Los roles de blob se limitan por validación: `cnpg_backups` admite `Reader` o `Contributor`; `audit_worm` admite `Reader` o el rol custom `AuditWormWriter`, sin permisos de borrado (ni de blobs ni de versiones). `Storage Blob Data Contributor` incluye delete y por eso no se concede sobre la evidencia; el WORM por versión además bloquea el borrado durante la retención. No hay roles `Owner`, `Key Vault Administrator` ni roles de blobs a nivel de cuenta o suscripción.
- Las identidades de aplicación no tienen ningún rol sobre las llaves de plataforma, salvo `openbao` sobre `openbao-unseal`. `audit-service` no puede usar `openbao-unseal` ni `storage-cmk`.
- No se confirmó contra Azure que el rol custom cubra todas las operaciones del adaptador (lectura de versiones, fijar retención o legal hold por versión). Si `setImmutabilityPolicy` o `setLegalHold` exigen otra `dataAction`, agrégala a `azurerm_role_definition.audit_worm_writer` sin incluir delete.
- `Crypto User` (uso de llaves: firmar, envolver) para `audit-service` es acceso de uso, no de administración, equivalente a `kms:Encrypt/Decrypt` en AWS. Crypto Officer sí permite gestionar llaves, por eso nunca coexiste con blobs.
- Fuera del alcance del módulo: asegurar que quien aplica OpenTofu y `key_admin_object_ids` no tengan `Storage Blob Data *` heredado de la suscripción o del resource group; revísalo en la revisión de políticas IAM.

## Rotación de la llave de OpenBao

`openbao-unseal` no rota de forma automática: el sello `azurekeyvault` envuelve la llave maestra con la versión vigente. Para rotarla crea una versión nueva y ejecuta el procedimiento de rekey/re-wrap de OpenBao antes de deshabilitar la versión anterior.

## Requisitos previos

- Instalar [OpenTofu](https://opentofu.org/) (versión `1.8` o superior).
- Autenticarse con Entra ID (`az login` o variables `ARM_*` de un service principal / OIDC).
- El principal que aplica necesita `Contributor` y `User Access Administrator` (o `Role Based Access Control Administrator`) sobre la suscripción o el resource group.
- El runner debe estar en `key_vault_allowed_ip_ranges` o dentro de la VNet: crear las llaves usa el plano de datos del Key Vault. Los roles RBAC pueden tardar unos minutos en propagarse; si la primera creación de llaves falla con 403, repite `apply`.
- Con `aks_host_encryption_enabled = true`, registrar la feature `Microsoft.Compute/EncryptionAtHost`.
- Administrar el cluster privado requiere conectividad a la VNet (VPN, ExpressRoute o jumpbox); Argo CD debe correr dentro del cluster o de una red con acceso.

## Backend

El estado de infraestructura se recomienda almacenar de forma remota (backend `azurerm` sobre una cuenta de storage con cifrado, versionado y `use_azuread_auth = true`). El bloque está comentado en `versions.tf` a modo de ejemplo.

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

Validación sin credenciales (CI): `tofu init -backend=false && tofu validate`.
