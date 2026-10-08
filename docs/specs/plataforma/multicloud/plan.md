# Plan de Diseño: F8 Multicloud

## 1. Módulos Maven nuevos (bajo `libs/`)
| Módulo | Contenido | SDK |
|---|---|---|
| `storage-gcs` | `GcsObjectStore`, `GcsImmutableStore`, autoconfiguración | `com.google.cloud:google-cloud-storage` |
| `storage-azure` | `AzureBlobObjectStore`, `AzureBlobImmutableStore`, autoconfiguración | `com.azure:azure-storage-blob`, `azure-identity` |
| `kms-aws` | `AwsKmsKeyService` | `software.amazon.awssdk:kms` (misma versión BOM que `s3`) |
| `kms-gcp` | `GcpKmsKeyService` | `com.google.cloud:google-cloud-kms` |
| `kms-azure` | `AzureKeyVaultKeyService` | `com.azure:azure-security-keyvault-keys`, `azure-identity` |

Cada módulo depende solo de `storage-port` o `kms-port`, nunca de otro adaptador. Versiones de SDK fijadas por el BOM del padre; los tres SDKs entran al SBOM (SEC-044).

## 2. Cambios en puertos
- `kms-port`: enum `SignatureAlgorithm { ED25519, ES256 }` y `signatureAlgorithm(TenantId, String keyId)` con `default ED25519`. `InMemoryKeyService` y `OpenBaoTransitKeyService` lo heredan.
- `kms-port`: `AadContext` ya canonicaliza el AAD; `AzureKeyVaultKeyService` reutiliza esa forma canónica para el sufijo `SHA-256`.
- `storage-port`: pruebas abstractas `ObjectStoreContract` e `ImmutableStoreContract` (clases de test publicadas con `test-jar`); `S3StoreIntegrationTest` pasa a heredarlas.
- `kms-port`: `KeyServiceContract` como `test-jar`; `InMemoryKeyServiceTest` y `OpenBaoTransitKeyServiceTest` lo heredan.

## 3. Selección y autoconfiguración
- Cada módulo registra `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` con una `@AutoConfiguration` condicionada por `@ConditionalOnProperty(name = "idp.storage.provider", havingValue = "gcs")` (y equivalentes).
- `s3` y `openbao` se mueven a su propia autoconfiguración en `storage-port` y `kms-port` con el mismo patrón, tomando las propiedades que hoy leen los `InfraConfig`.
- Se eliminan de los seis `InfraConfig` (`audit`, `chat`, `document`, `extraction`, `notification`, `review`) los beans `ObjectStore`, `KeyService` y `EnvelopeCrypto` duplicados; queda lo que no es de proveedor (resolvers, control-db, autorización).
- Arranque fallido si `idp.control-db.url` está definido y falta proveedor (AC-02). El fallback en memoria solo aplica con `idp.security.dev-mode=true`.
- Helm: `_workload.tpl` mapea `global.adapters.storage` y `global.adapters.kms` a `IDP_STORAGE_PROVIDER` e `IDP_KMS_PROVIDER`. `values-onprem.yaml` pasa a `storage: s3`, `kms: openbao`. Se extiende `tools/ci/check_helm_env.py` para validar los cuatro destinos.

## 4. Algoritmo de firma
- Constante `WormAnchorService.SIGNATURE_ALGORITHM` se sustituye por `keys.signatureAlgorithm(tenant, signingKeyId).name().toLowerCase()` en los firmantes con bloque de firma: `WormAnchorService` y `DossierService`. `AiExecutionRegistry` y `AccessCertificationService` no llevan campo `algorithm` y no cambian.
- Verificadores (`AuditVerificationService.verifyAnchors`, `DossierService.signatureValid`): comparan el `algorithm` del documento contra `keys.signatureAlgorithm` de la llave configurada; si difiere, resultado inválido. En el expediente, si el algoritmo es `ED25519` y hay `publicKeys`, verificación local; en otro caso `keys.verify`. Las anclas siguen verificando siempre con `keys.verify`.
- ES256 en Azure: firma `ECDSA P-256 / SHA-256`, formato `r||s` crudo (64 bytes), que es lo que devuelve la API de Key Vault.

## 5. Detalle por adaptador
- **GCS:** `Storage` con Application Default Credentials (Workload Identity); objeto en `gs://<bucket>/<tenant>/<ruta>` vía `TenantBucketResolver`. WORM: `Blob.Retention` por objeto con modo `Locked` más `eventBasedHold`/`temporaryHold` para el legal hold. Verificación al arranque: `bucket.retentionPolicy`/`objectRetention` habilitado.
- **Azure Blob:** `BlobServiceClient` con `DefaultAzureCredential`; contenedor por `TenantBucketResolver`. WORM: immutability policy por versión (`setImmutabilityPolicy`) y `setLegalHold`. Verificación al arranque: `versionLevelWorm` habilitado en el contenedor.
- **AWS KMS:** `Encrypt/Decrypt` con `EncryptionContext` = AAD canónico; Ed25519 con `ED25519_SHA_512` y `MessageType RAW`; `disableKek` = `DisableKey` + `ScheduleKeyDeletion` (ventana mínima del proveedor).
- **Cloud KMS:** `encrypt/decrypt` con `additionalAuthenticatedData`; `EC_SIGN_ED25519` con `asymmetricSign`; `disableKek` = versión `DISABLED` + `destroyCryptoKeyVersion` (periodo programado).
- **Azure Key Vault:** `CryptographyClient.wrapKey/unwrapKey` con `RSA-OAEP-256` sobre `DEK||SHA-256(AAD)`; firma con llave EC P-256 (`ES256`); `disableKek` = `updateKeyProperties(enabled=false)` más `beginDeleteKey` con purge protection.
- Nombre de llave por tenant `t-<tenant>-<kekId>`, igual que OpenBao.

## 6. OpenTofu
`infra/opentofu/gcp` y `infra/opentofu/azure` replican la estructura de `aws/` (`main.tf`, `variables.tf`, `outputs.tf`, `versions.tf`, `terraform.tfvars.example`, `README.md`): red privada con NAT, clúster (GKE / AKS) con identidad de workload, bucket o contenedor inmutable, llave KMS por entorno, registro de imágenes, cuentas de servicio por servicio con roles separados llaves/contenido. Verificación: `tofu init -backend=false` y `tofu validate` en CI (sin credenciales).

## 7. Pruebas
- Contrato compartido por adaptador (AC-04..AC-16).
- Emuladores en Testcontainers donde el spike T-00 confirme cobertura: LocalStack (S3, KMS), fake-gcs-server, Azurite. Lo que un emulador no implemente (retención, legal hold, firma de KMS, `wrapKey`) se prueba con fake de la interfaz fina interna del adaptador, y la real queda en el runbook de smoke.
- Test de downgrade de algoritmo en `audit-service` (AC-17) y de los cuatro firmantes con un `KeyService` ES256 en memoria (AC-18).
- Test de render Helm por destino (AC-03, AC-21).

### Resultado del spike T-00 (2026-10-07)
Ejecutado con Testcontainers (`localstack/localstack:3.2.0`, `fsouza/fake-gcs-server`, Azurite). Los resultados marcados "no concluyente" no tienen evidencia de test que los respalde y se resuelven en la tarea del adaptador correspondiente.

| Capacidad | Emulador | Resultado | Decisión |
|---|---|---|---|
| S3 Object Lock COMPLIANCE y legal hold | LocalStack | Confirmado | Emulador real (T-01) |
| KMS Encrypt/Decrypt con `EncryptionContext` distinto falla | LocalStack | Confirmado | Emulador real (T-06) |
| KMS `DisableKey` y `ScheduleKeyDeletion` | LocalStack | Confirmado | Emulador real (T-06) |
| KMS Ed25519 `Sign`/`Verify` | LocalStack | No concluyente: el spike usó el SDK 2.25.16, el repo usa 2.55.11 | Reintentar en T-06 con el SDK del repo; si falla, fake de interfaz fina |
| GCS put/get/delete y retención de bucket | fake-gcs-server | Confirmado | Emulador para `ObjectStoreContract` (T-04) |
| GCS retención por objeto y holds | fake-gcs-server | No concluyente, sin evidencia | Fake de interfaz fina para WORM (T-04) |
| Blob put/get/delete | Azurite | Confirmado | Emulador para `ObjectStoreContract` (T-05) |
| Blob immutability por versión y legal hold | Azurite | No concluyente, sin evidencia de test | Fake de interfaz fina para WORM (T-05) |
| Cloud KMS | Ninguno | Sin imagen verificable | Fake de interfaz fina (T-07) |
| Key Vault | `nagyesta/lowkey-vault` | No probado: conflicto de dependencias en el spike | Fake de interfaz fina (T-08); reintentar lowkey-vault si cuesta poco |

Los adaptadores sin cobertura de emulador para WORM o KMS se prueban con un fake de la interfaz fina interna; la semántica real queda en el runbook de smoke (T-12).

## 8. Riesgos
- Limitación abierta de S3 (`S3ImmutableStore`): tras un `delete`, que solo agrega un delete marker, `applyLegalHold` y `removeLegalHold` apuntan a esa versión y fallan. Corregirlo exige listar versiones (`s3:ListBucketVersions`), que ampliaría los privilegios del rol de `audit-service` (SEC-011). Las anclas WORM no se borran en la práctica; GCS y Blob no tienen la limitación. El contrato WORM no depende de ese caso: usa `hardDelete` y `retainedContent`.
- Superficie de CVE por tres SDKs adicionales: Trivy y Dependabot ya cubren; revisar tamaño de imagen.
- Emuladores sin soporte WORM o KMS: mitigado con fake de interfaz fina y runbook.
- Netty 4.2 (Boot) frente a 4.1 que espera `azure-core-http-netty`: el SDK de Azure avisa del desajuste en el log; vigilar en CI y en la imagen final.
- AC-07 literal no aplica a Blob con versionado: `delete` o sobrescribir crea una version nueva y la retenida sigue intacta; `ImmutableStoreContract` verifica con `hardDelete`. Semantica real sin probar contra Azure (runbook T-12).
- RSA-OAEP-256 limita el tamaño de la carga envuelta: DEK de 32 bytes más hash de 32 bytes es 64, cabe con llave RSA de 3072 bits.
- Key Vault (T-08), formato del blob envuelto: `0x01 | longitud de version (1 byte) | version ASCII [A-Za-z0-9]{1,64} | texto cifrado RSA-OAEP-256`. La version es la de la llave que hizo `wrapKey` (`WrapResult.getKeyId()`); `unwrapKey` se hace contra esa version, de modo que un blob viejo sigue desenvolviendo tras rotar la KEK. El cliente criptografico de la version vigente se renueva cada 5 minutos; los clientes por version se conservan. Un blob con formato o version invalidos falla igual que un AAD distinto.
- Key Vault, errores de descifrado: AAD distinto, blob manipulado/truncado, otra llave y texto rechazado por el proveedor (400) producen un unico `IllegalArgumentException("No se pudo descifrar")`, sin filtrar la causa. `KeyServiceUnavailableException` queda para 5xx, 429, red y 401/403 de configuracion, sin encadenar la causa del SDK (puede traer ids de tenant o cliente).
- Key Vault, multi-replica y crypto-shredding: la marca de `disableKek` en proceso solo acelera la respuesta local; entre replicas la garantia la da el proveedor (403/409 por llave deshabilitada o eliminada se traducen a `KeyDisabledException`). `disableKek` es idempotente (un 404 o 409 tras un borrado ya aplicado converge a exito) y espera el borrado (maximo 30 s) o reporta su fallo.
- Key Vault, aislamiento: con un vault compartido el aislamiento entre tenants es logico (nombre de llave hasheado), no por identidad, porque la identidad del servicio puede operar todas las llaves del vault. Recomendado un vault dedicado a llaves de tenant (modulo OpenTofu `azure`, T-11) con RBAC de solo wrap/unwrap/sign/verify para el servicio.
- Dependencias Azure: `kms-azure` y `storage-azure` traen cada uno su version de `azure-core`, `azure-json` y `azure-core-http-netty`; Maven resuelve una sola y podria romper a uno de los dos. Fijar esas tres una vez en el `pom.xml` padre cuando ambos modulos esten integrados. Ademas `azure-core-http-netty` 1.16 espera Netty 4.1 y Spring Boot gestiona Netty 4.2 (el SDK lo avisa al arrancar); si aparece `NoSuchMethodError`, excluirlo y usar `azure-core-http-jdk-httpclient`.
- Key Vault, emulador: `idp.kms.azure.emulator=true` exige `idp.security.dev-mode=true` y rechaza vaults de dominios de Azure; sin el flag solo se usa `DefaultAzureCredential`.
- Rotación de la llave de firma de auditoría (Cloud KMS, AWS KMS): nunca deshabilitar ni destruir versiones de firma de la llave de auditoría salvo shredding total; las firmas anteriores solo verifican mientras su versión siga habilitada (SEC-042). Las firmas llevan la versión (`vault:v<N>:`) y `publicKeys` devuelve todas las versiones habilitadas. La llave de auditoría sobrevive al shredding de datos (SEC-017).
