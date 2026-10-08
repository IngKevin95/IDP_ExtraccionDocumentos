# GCP Infrastructure para Plataforma IDP

Este módulo despliega la infraestructura base para ejecutar la plataforma IDP en Google Cloud Platform. Replica el alcance del módulo `../aws` con recursos nativos del provider `hashicorp/google` (sin módulos de registro, para facilitar la auditoría). Especificación: `docs/specs/plataforma/multicloud` (AC-19, AC-20).

## Equivalencia de recursos (AWS a GCP)

| AWS | GCP |
|---|---|
| VPC, subnets, NAT Gateway | VPC propia (`auto_create_subnetworks = false`), subred regional con rangos secundarios, Cloud Router + Cloud NAT, Private Google Access |
| EKS + Pod Identity | GKE regional privado + Workload Identity |
| KMS (cluster, unseal, S3, ECR) | Cloud KMS: un key ring con llaves separadas |
| S3 + Object Lock | GCS con acceso uniforme, public access prevention y object retention |
| ECR (tags inmutables) | Artifact Registry Docker con `immutable_tags` y CMEK |

## Recursos creados

1. **Red privada**: VPC, subred de nodos con rangos secundarios `pods` y `services`, Cloud Router y Cloud NAT para el egress. Los nodos no tienen IP pública. Los discos de arranque de los nodos usan CMEK (`boot_disk_kms_key`).
2. **GKE privado**: clúster regional con nodos privados, Workload Identity (`<proyecto>.svc.id.goog`), release channel, shielded nodes, Dataplane V2 (aplica `NetworkPolicy` de Kubernetes de forma nativa), cifrado de Secrets de Kubernetes con CMEK, logging y monitoring (incluye API server), `master_authorized_networks` y node pool propio con cuenta de servicio de mínimo privilegio. Spot en ambientes no-prod; `deletion_protection` en prod.
3. **Cloud KMS**: key ring con llaves `openbao-unseal`, `gke-secrets`, `storage-cmek`, `registry-cmek`, `nodes-disk-cmek` (discos de arranque) y `app-wrap`, todas con rotación automática (`kms_rotation_period`, 90 días por defecto) y `destroy_scheduled_duration`; y `audit-signing` (Ed25519 para `GcpKmsKeyService`, AC-15; Ed25519 solo existe con protección SOFTWARE, por eso no usa `kms_protection_level`; al ser asimétrica no admite rotación automática, se rota creando una versión). Todas las llaves llevan `prevent_destroy = true`; es un literal (HCL no admite variables en `lifecycle`), así que para desmontar un entorno de pruebas hay que quitarlo a mano.
4. **Buckets GCS** (acceso uniforme, public access prevention `enforced`, versionado, CMEK):
   - `cnpg-backups`: backups de CloudNativePG con lifecycle y logging de acceso.
   - `audit-evidence`: bucket WORM con `enable_object_retention = true` y retención por defecto configurable (`evidence_retention_days`, `evidence_retention_locked`). `force_destroy` siempre es `false`.
   - `access-logs`: destino de los logs de acceso.
5. **Artifact Registry**: repositorio Docker con CMEK y tags inmutables. El escaneo de vulnerabilidades se habilita con la API `containerscanning.googleapis.com`.
6. **Cuentas de servicio por componente** con bindings de Workload Identity: `openbao`, `cnpg`, `audit`, `app` y `onboarding`, más la cuenta de los nodos. Varias ServiceAccount de Kubernetes pueden asumir la misma cuenta de GCP.

### Nombres de ServiceAccount de Kubernetes (`workload_identities`)

Confirmado en el repo: namespaces `idp-core` e `idp-platform` (`deploy/platform/namespaces.yaml`) y la SA de CNPG `idp-postgres` en `idp-platform` (`deploy/platform/postgres/cluster.yaml`). No confirmado:

- Las SA de los servicios se renderizan como `<release>-<servicio>` (`idp-common.fullname`, sin `fullnameOverride`). Los defaults asumen el release `idp-app-prod` (`deploy/argocd/applications/prod/idp-app.yaml`), por ejemplo `idp-app-prod-audit-service`. Con otro release o `fullnameOverride`, ajusta la variable.
- La SA de OpenBao (`openbao`) es la que crea por defecto su chart; el repo solo tiene `deploy/platform/openbao/values.yaml`, sin la Application que lo instala.
- La SA `tenant-onboarding` (namespace `idp-core`) es un nombre propuesto: el job o servicio de onboarding aún no existe en `deploy/`.

## Bucket WORM y GcsImmutableStore

El adaptador `GcsImmutableStore` exige retención por objeto (AC-09) y falla al arrancar si el bucket no la tiene. Por eso `audit-evidence` declara `enable_object_retention = true`: permite fijar `retainUntilTime` y holds por objeto. Puntos de operación:

- La habilitación de object retention es una propiedad de creación del bucket; no se puede activar después en uno existente.
- La política de retención por defecto (`retention_policy`) cubre objetos sin retención propia. Con `evidence_retention_locked = true` se aplica Bucket Lock, que es **irreversible**: úsalo solo en prod con el periodo definitivo.
- Con versionado y retención, una versión no se puede borrar antes de vencer su retención.

## Separación de funciones de IAM (SEC-011 / AC-20)

Ninguna identidad combina administración de llaves con lectura de contenido de objetos. Todas las asignaciones usan `*_iam_member` (no autoritativas) a nivel de llave, key ring, bucket o repositorio, nunca a nivel de proyecto salvo roles de observabilidad de los nodos.

| Identidad | Rol | Alcance |
|---|---|---|
| `key_admin_members` (variable) | `roles/cloudkms.admin` | Key ring (gestiona ciclo de vida; no cifra/descifra ni lee objetos) |
| `evidence_reader_members` (variable) | `roles/storage.objectViewer` | Bucket `audit-evidence` |
| SA `openbao` | `roles/cloudkms.cryptoKeyEncrypterDecrypter`, `roles/cloudkms.viewer` | Solo llave `openbao-unseal` |
| SA `cnpg` | `roles/storage.objectUser` | Solo bucket `cnpg-backups` |
| SA `audit` | Rol personalizado `evidence_writer` (`storage.buckets.get`, `storage.objects.{create,get,list,update,setRetention}`; sin `delete`) | Bucket `audit-evidence` |
| SA `audit` | `roles/cloudkms.signerVerifier` | Solo llave `audit-signing` |
| SA `app` | `roles/cloudkms.cryptoKeyEncrypterDecrypter` | Solo llave `app-wrap` |
| SA `onboarding` | Rol personalizado `tenant_key_provisioner` (`cloudkms.cryptoKeys.{create,get,list,update}`, `cloudkms.cryptoKeyVersions.{create,get,list,update}`; sin uso criptográfico ni `setIamPolicy`) | Key ring (crea las llaves `idp-<hex>` por tenant) |
| SA `app` | `roles/cloudkms.cryptoKeyEncrypterDecrypter` con condición `resource.name.contains("/cryptoKeys/idp-")` | Key ring (uso de las llaves por tenant) |
| SA `audit` | `roles/cloudkms.signerVerifier` con la misma condición | Key ring (firma con las llaves por tenant) |
| SA nodos GKE | `logging.logWriter`, `monitoring.metricWriter`, `monitoring.viewer`, `stackdriver.resourceMetadata.writer` (proyecto); `artifactregistry.reader` (repositorio) | Observabilidad y pull de imágenes |
| Agente de servicio de Compute Engine | `roles/cloudkms.cryptoKeyEncrypterDecrypter` | Solo llave `nodes-disk-cmek` |
| Agente de servicio de GKE | `roles/cloudkms.cryptoKeyEncrypterDecrypter` | Solo llave `gke-secrets` |
| Agente de servicio de GCS | `roles/cloudkms.cryptoKeyEncrypterDecrypter` | Solo llave `storage-cmek` |
| Agente de servicio de Artifact Registry | `roles/cloudkms.cryptoKeyEncrypterDecrypter` | Solo llave `registry-cmek` |
| `cloud-storage-analytics@google.com` | `roles/storage.objectCreator` | Solo bucket `access-logs` (entrega de logs de acceso) |

Llaves por tenant: el adaptador `kms-gcp` usa una CryptoKey por tenant (`idp-<59 hex>`) creada por el onboarding. La identidad `onboarding` crea y configura llaves pero no puede usarlas (cifrar, firmar) ni leer objetos; se usa un rol personalizado en lugar de `roles/cloudkms.admin` porque admin permite concederse acceso de uso con `setIamPolicy`. Los servicios usan las llaves a nivel de key ring con una condición IAM por nombre `idp-`; las llaves del propio módulo no empiezan por `idp-`, así que quedan fuera. La expresión de la condición no se pudo probar contra una cuenta real.

Controles del módulo:

- Un `precondition` rechaza que un mismo miembro esté en `key_admin_members` y `evidence_reader_members`.
- Los roles de lectura de objetos y los de administración de llaves nunca se otorgan al mismo principal dentro del módulo; las cuentas de servicio de los workloads reciben solo uso de una llave concreta, nunca `cloudkms.admin`.
- Limitación: IAM de GCP hereda desde el proyecto. Los roles amplios (`roles/owner`, `roles/editor`, `roles/storage.admin`) concedidos fuera de este módulo anulan la separación. Mantén a los administradores de llaves sin esos roles en el proyecto y revisa la política con `gcloud projects get-iam-policy`.

## Guardas de producción

Con `environment = "prod"`, un `precondition` exige `evidence_retention_locked = true`, `kms_rotation_period` <= 90 días y `kms_destroy_scheduled_duration` >= 30 días. El bucket `audit-evidence` lleva `prevent_destroy = true`. Los PVC de CloudNativePG no los gestiona este módulo: para cifrarlos con CMEK crea un StorageClass del CSI de PD con `disk-encryption-kms-key: <output disk_kms_key_id>` y úsalo en `storage.storageClass` del Cluster de CNPG (el agente de Compute ya tiene permiso sobre esa llave).

## Requisitos previos

- [OpenTofu](https://opentofu.org/) `1.8` o superior.
- Credenciales de GCP con permiso para crear los recursos (por ejemplo `gcloud auth application-default login` o impersonación de una cuenta de servicio de despliegue). No incluyas credenciales en el repositorio.
- Providers `hashicorp/google` y `hashicorp/google-beta` (este último solo para `google_project_service_identity`, el agente de servicio de Artifact Registry).
- El módulo habilita las APIs necesarias (`google_project_service`).
- Permisos de quien aplica (deployer), como mínimo: `roles/serviceusage.serviceUsageAdmin`, `roles/compute.networkAdmin`, `roles/container.admin`, `roles/iam.serviceAccountAdmin`, `roles/iam.roleAdmin` (rol personalizado), `roles/resourcemanager.projectIamAdmin` (roles de proyecto de los nodos), `roles/cloudkms.admin`, `roles/storage.admin`, `roles/artifactregistry.admin`. No se pudo probar un `apply` real: ajusta según los errores de permisos. Esta cuenta de despliegue administra llaves y buckets, por lo que no debe usarse para leer evidencia; usa una cuenta distinta de `evidence_reader_members`.
- No se versiona `.terraform.lock.hcl` en ningún módulo (igual que `aws`); los providers se fijan por rango en `versions.tf` y Dependabot (ecosistema terraform) los actualiza. Los `*.tfvars` están ignorados por git; solo se versiona `terraform.tfvars.example`.

## Backend

Se recomienda estado remoto en un bucket GCS con versionado y CMEK. El bloque `backend "gcs"` está comentado en `versions.tf` a modo de ejemplo.

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

Validación sin credenciales (la misma que corre en CI):

```bash
tofu init -backend=false -input=false && tofu validate
```

## Notas de operación

- Un key ring de Cloud KMS no se puede eliminar: `tofu destroy` solo lo saca del estado. Las llaves usan `destroy_scheduled_duration` para dar margen de recuperación.
- Los nombres de bucket son globales y llevan el `project_id` como sufijo de prefijo para evitar colisiones.
- Con `master_authorized_cidrs` vacío, el endpoint público del plano de control se deshabilita y solo se accede desde la VPC. El valor `0.0.0.0/0` se rechaza por validación.
