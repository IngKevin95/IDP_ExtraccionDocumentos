# Tareas de Implementación: F8 Multicloud

## T-00: Spike de emuladores
* **Descripción:** Comprobar con Testcontainers qué cubren LocalStack (S3 Object Lock, KMS Ed25519 y EncryptionContext), fake-gcs-server (retención, hold) y Azurite (immutability, legal hold), y si existe un emulador utilizable de Cloud KMS y Key Vault. Registrar el resultado en `plan.md` sección 7.
* **Criterio de hecho:** Tabla emulador x capacidad con evidencia (salida de test) y decisión por adaptador: emulador o fake de interfaz fina.
* **Validación:** Los tests del spike corren en CI y la tabla está en el plan. El código del spike se descarta o se promueve a test del adaptador.

## T-01: Contratos de prueba compartidos
* **Descripción:** Crear `ObjectStoreContract`, `ImmutableStoreContract` y `KeyServiceContract` (test-jar). Migrar S3, `InMemoryKeyService` y OpenBao para que los hereden.
* **Criterio de hecho:** Los adaptadores existentes pasan las suites sin cambiar su comportamiento.
* **Validación:** Tests de S3, InMemory y OpenBao en verde (valida AC-04, AC-05, AC-10, AC-11, AC-14).
* **Nota para T-04..T-08:** consumir los contratos como `test-jar` (`<type>test-jar</type>`, `<scope>test</scope>`); cada adaptador debe sobrescribir `providerFailure()` (AC-06, AC-13), `hardDelete` y `retainedContent` (WORM), y probar por su cuenta AC-09 (arranque fallido sin modo inmutable).

## T-02: Algoritmo de firma declarado
* **Descripción:** Agregar `SignatureAlgorithm` y `KeyService.signatureAlgorithm`. Actualizar los cuatro firmantes y los dos verificadores de `audit-service` según plan sección 4. Test de downgrade.
* **Criterio de hecho:** Un documento con `algorithm` distinto al declarado por la llave se rechaza; los firmantes escriben el algoritmo del proveedor.
* **Validación:** `AuditVerificationServiceTest` y `DossierServiceTest` con caso ES256 y caso de downgrade (valida AC-15, AC-17, AC-18).

## T-03: Autoconfiguración y selección por propiedad
* **Descripción:** Mover S3 y OpenBao a autoconfiguración condicionada; eliminar los beans duplicados de los seis `InfraConfig`; arranque fallido sin proveedor (AC-02).
* **Criterio de hecho:** Los seis servicios arrancan con `provider=s3|openbao` igual que antes y fallan sin proveedor fuera de dev-mode.
* **Validación:** Tests de contexto por servicio y test de la autoconfiguración (valida AC-01, AC-02).

## T-04: Adaptador GCS
* **Descripción:** `storage-gcs` con `GcsObjectStore` y `GcsImmutableStore`, validación al arranque del modo inmutable.
* **Criterio de hecho:** Pasa `ObjectStoreContract` e `ImmutableStoreContract` según la estrategia de T-00.
* **Validación:** Tests de contrato y de arranque fallido (valida AC-04..AC-09 para GCS).

## T-05: Adaptador Azure Blob
* **Descripción:** `storage-azure` con `AzureBlobObjectStore` y `AzureBlobImmutableStore`, validación al arranque de `versionLevelWorm`.
* **Criterio de hecho:** Pasa las suites de contrato según T-00.
* **Validación:** Tests de contrato y de arranque fallido (valida AC-04..AC-09 para Blob).

## T-06: KeyService AWS KMS
* **Descripción:** `kms-aws` con wrap/unwrap con `EncryptionContext`, Ed25519, `disableKek`.
* **Criterio de hecho:** Pasa `KeyServiceContract`; `signatureAlgorithm = ED25519`.
* **Validación:** Contrato sobre LocalStack o fake según T-00 (valida AC-10, AC-11, AC-13, AC-15).

## T-07: KeyService Cloud KMS
* **Descripción:** `kms-gcp` con AAD nativo, `EC_SIGN_ED25519`, `disableKek`.
* **Criterio de hecho:** Pasa `KeyServiceContract`; `signatureAlgorithm = ED25519`.
* **Validación:** Contrato sobre fake de interfaz fina (valida AC-10, AC-11, AC-13, AC-15).

## T-08: KeyService Azure Key Vault
* **Descripción:** `kms-azure` con AAD emulado (`DEK||SHA-256(AAD)`), firma ES256 y `disableKek` con purge protection.
* **Criterio de hecho:** Pasa `KeyServiceContract`; AAD alterado falla; `signatureAlgorithm = ES256` y `publicKeys` vacío.
* **Validación:** Contrato sobre fake de interfaz fina más test específico del sufijo (valida AC-10..AC-13, AC-16).

## T-09: Cableado Helm por destino
* **Descripción:** `_workload.tpl` mapea `global.adapters` a las propiedades; corregir `values-onprem.yaml`; ampliar `check_helm_env.py` a los cuatro destinos.
* **Criterio de hecho:** `helm template` por destino pasa el chequeo y onprem no contiene `minio` ni `thales`.
* **Validación:** `check_helm_env.py` con self-test y paso en `kind-e2e.yml` (valida AC-03, AC-21).

## T-10: OpenTofu GCP
* **Descripción:** `infra/opentofu/gcp` con red, GKE, Workload Identity, bucket con lock, Cloud KMS, Artifact Registry y roles separados llaves/contenido.
* **Criterio de hecho:** `tofu validate` pasa sin credenciales.
* **Validación:** Paso de CI `tofu init -backend=false && tofu validate` (valida AC-19, AC-20).

## T-11: OpenTofu Azure
* **Descripción:** `infra/opentofu/azure` con red, AKS, Workload Identity, contenedor con WORM por versión, Key Vault, ACR y roles separados.
* **Criterio de hecho:** `tofu validate` pasa sin credenciales.
* **Validación:** Mismo paso de CI (valida AC-19, AC-20).

## T-12: Runbook de smoke y matriz
* **Descripción:** Escribir `docs/runbooks/smoke-multicloud.md` y actualizar `matriz-controles.md` (SEC-042, SEC-055, SEC-056 con sus pruebas) y `docs/despliegue.md`.
* **Criterio de hecho:** Runbook cubre AC-22 por destino; la matriz referencia los tests reales.
* **Validación:** Revisión del auditor contra el spec y las pruebas citadas.

## Estado (2026-10-08)
T-00 a T-12 implementadas (T-09 dentro de T-03). Pendiente, fuera de la fase: ejecutar el runbook de smoke en cuentas reales (T-12), cambiar AWS a `kms: aws-kms` en `values-aws.yaml` cuando se valide en una cuenta real, y decidir la migración de nombres de llave de OpenBao (SEC-057).
