# Fase F8: Multicloud (Especificación)

## Contexto
La plataforma corre hoy con almacenamiento S3-compatible y llaves en OpenBao Transit, con módulo OpenTofu solo para AWS. F8 agrega adaptadores nativos para GCP y Azure y completa el despliegue por destino. Decisiones: ADR 0020 y ADR 0032.

## Alcance
- Dentro: `ObjectStore`/`ImmutableStore` para GCS y Azure Blob; `KeyService` para AWS KMS, Cloud KMS y Azure Key Vault; selección por propiedad y autoconfiguración; algoritmo de firma declarado; values por nube; módulos OpenTofu `gcp/` y `azure/`.
- Fuera: `LlmProvider` por nube (la calibración es por par modelo+prompt y exige golden set por nube), smoke en nube real (queda como runbook sin ejecutar), WAF y observabilidad nativa de cada nube.

## Requisitos y Escenarios

### 1. Selección de adaptador (SEC-020)
- **AC-01** Given `idp.storage.provider=gcs`, When arranca cualquier servicio que usa `ObjectStore`, Then el bean es `GcsObjectStore` y no se construye ningún cliente S3.
- **AC-02** Given `idp.kms.provider` ausente o con valor desconocido, When arranca un servicio con `idp.control-db.url` definido, Then el arranque falla con mensaje explícito (nunca cae a `InMemoryKeyService`).
- **AC-03** Given `values-gcp.yaml`, `values-azure.yaml`, `values-aws.yaml` y `values-onprem.yaml`, When se renderiza el chart, Then cada Deployment recibe `IDP_STORAGE_PROVIDER` e `IDP_KMS_PROVIDER` coherentes con `global.adapters`, y `values-onprem.yaml` no contiene `minio` ni `thales`.

### 2. ObjectStore en GCS y Azure Blob (SEC-020)
- **AC-04** Given un adaptador de almacenamiento (S3, GCS o Blob), When se ejecuta la suite `ObjectStoreContract`, Then put/get/delete funcionan y un tenant no puede leer ni borrar la ruta de otro tenant.
- **AC-05** Given un `TenantId` o ruta con `..`, separadores o caracteres fuera de la lista permitida, When se llama al adaptador, Then lanza `StorageException` sin tocar el proveedor.
- **AC-06** Given un fallo del proveedor (timeout, 5xx, permiso denegado), When se invoca get/put/delete, Then se propaga `StorageException` sin incluir rutas con PII ni credenciales en el mensaje.

### 3. ImmutableStore (WORM)
- **AC-07** Given `putWithRetention` con una retención, When se intenta `delete` o sobrescribir antes del vencimiento, Then el adaptador reporta fallo y el objeto sigue legible.
- **AC-08** Given `applyLegalHold`, When vence la retención, Then el objeto sigue sin poder borrarse hasta `removeLegalHold`.
- **AC-09** Given un bucket o contenedor sin modo inmutable habilitado, When arranca el adaptador WORM, Then el arranque falla de forma explícita.

### 4. KeyService en AWS KMS, Cloud KMS y Key Vault (SEC-011, SEC-015, SEC-016)
- **AC-10** Given cualquier `KeyService`, When se ejecuta `KeyServiceContract`, Then `wrapDek`/`unwrapDek` hacen ida y vuelta, un AAD distinto en `unwrapDek` falla, y un `kekId` de otro tenant falla.
- **AC-11** Given `disableKek`, When se llama `unwrapDek` con esa KEK, Then lanza `KeyDisabledException` de inmediato, y la destrucción queda programada en la ventana del proveedor.
- **AC-12** Given Azure Key Vault, When se envuelve una DEK con AAD, Then el material enviado a `wrapKey` es `DEK || SHA-256(AAD canónico)` y un AAD alterado falla en la comparación en tiempo constante tras `unwrapKey`.
- **AC-13** Given cualquier fallo del proveedor KMS, When se invoca una operación, Then se reporta `KeyServiceUnavailableException`.
- **AC-14** Given un `tenantId` o `keyId` fuera de `[A-Za-z0-9_-]{1,64}`, When se invoca cualquier operación, Then `IllegalArgumentException` sin llamar al proveedor.

### 5. Algoritmo de firma declarado (SEC-042, SEC-055)
- **AC-15** Given AWS KMS o Cloud KMS, When se firma, Then `signatureAlgorithm` devuelve `ED25519` y `publicKeys` permite verificar localmente.
- **AC-16** Given Azure Key Vault, When se firma, Then `signatureAlgorithm` devuelve `ES256`, `publicKeys` devuelve vacío y `verify` valida con el proveedor.
- **AC-17** Given un expediente o ancla con `algorithm` distinto al que declara el `KeyService` para esa llave, When `audit-service` verifica, Then el resultado es inválido sin intentar verificar con el algoritmo recibido.
- **AC-18** Given los documentos firmados que llevan bloque de firma (anclas WORM y expediente de `audit-service`), When firman con un `KeyService` ES256, Then escriben `algorithm=es256` y la verificación posterior pasa. `AiExecutionRegistry` y `AccessCertificationService` guardan llave y firma en columnas sin campo `algorithm` y verifican con `KeyService.verify`; no se agrega campo.

### 6. Despliegue por destino
- **AC-19** Given `infra/opentofu/gcp` y `infra/opentofu/azure`, When se ejecuta `tofu init -backend=false` y `tofu validate`, Then ambos pasan, y cada módulo declara: red privada, clúster con identidad de workload, bucket o contenedor con modo inmutable y llave KMS por entorno.
- **AC-20** Given un destino con cuenta de servicio por servicio, When se revisan los roles, Then ningún rol combina administración de llaves con lectura de contenido de objetos (SEC-011).
- **AC-21** Given el workflow `kind-e2e`, When se instala el chart con cada `values-<destino>.yaml` renderizado (`helm template`), Then `tools/ci/check_helm_env.py` pasa para los cuatro destinos.
- **AC-22** Given el runbook `docs/runbooks/smoke-multicloud.md`, When un operador lo sigue en una cuenta real, Then cubre por destino: put/get, retención y legal hold, wrap/unwrap con AAD, firma y verificación, y limpieza.

## Controles
SEC-011, SEC-015, SEC-016, SEC-020, SEC-042, SEC-044, SEC-055, SEC-056 (ver `docs/seguridad/matriz-controles.md`).

## Métricas
Reutiliza `idp.storage.*` e `idp.kms.*` existentes con la etiqueta `provider`.

## Criterio de salida de la fase
Suites de contrato en verde para S3, GCS, Blob y los cuatro `KeyService`; `helm template` y `check_helm_env` en verde para los cuatro destinos; `tofu validate` en verde para `aws`, `gcp` y `azure`; runbook de smoke publicado.
