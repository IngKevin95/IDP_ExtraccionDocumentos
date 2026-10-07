# 0032. Adaptadores nativos por nube y algoritmo de firma declarado

Estado: Aceptada
Fecha: 2026-10-07

## Contexto
El ADR 0020 fija el núcleo portable con adaptadores por proveedor. Hoy solo existen `S3ObjectStore`/`S3ImmutableStore` (AWS y self-hosted) y `OpenBaoTransitKeyService`. Los `values-{aws,gcp,azure,onprem}.yaml` declaran `global.adapters.*`, pero ninguna plantilla ni `application.yml` lo lee, y `values-onprem.yaml` nombra `minio` y `thales`, que contradicen el ADR 0020. Seis servicios repiten el cableado de `ObjectStore` y `KeyService` en su `InfraConfig`.

Brechas de contrato entre proveedores (verificadas contra documentación oficial):
- AWS KMS y Cloud KMS firman Ed25519. Azure Key Vault y Managed HSM solo ofrecen EC P-256/P-384/P-521 y ES256K, no Ed25519.
- AWS KMS y Cloud KMS aceptan AAD (`EncryptionContext`, `additionalAuthenticatedData`). `wrapKey` de Key Vault no lo expone.
- `audit-service` fija `ed25519` como constante (`WormAnchorService.SIGNATURE_ALGORITHM`) y rechaza cualquier otro valor en el expediente y en las anclas.

## Decisión
1. **Un módulo por familia de proveedor**, cada uno con su autoconfiguración Spring: `libs/storage-gcs`, `libs/storage-azure`, `libs/kms-aws`, `libs/kms-gcp`, `libs/kms-azure`. Todos viajan en la misma imagen; la selección es por `idp.storage.provider` (`s3|gcs|azure-blob`) y `idp.kms.provider` (`openbao|aws-kms|gcp-kms|azure-keyvault`). Un solo digest firmado se promueve entre entornos (ADR 0022). `global.adapters.*` de los values se cablea a esas propiedades.
2. **La autoconfiguración reemplaza el cableado duplicado** de los seis `InfraConfig`.
3. **Algoritmo de firma declarado por la llave.** `KeyService` gana `SignatureAlgorithm signatureAlgorithm(TenantId, String keyId)` (`ED25519` por defecto, `ES256` en Azure). Los firmantes escriben ese valor en el campo `algorithm` del documento firmado. Los verificadores comparan `algorithm` contra el que declara el proveedor para esa llave y rechazan cualquier discrepancia (anti-downgrade); nunca eligen el algoritmo a partir del dato recibido. `publicKeys` (verificación local) sigue siendo solo Ed25519; para ES256 devuelve vacío y se verifica con `KeyService.verify`.
4. **AAD emulado en Azure.** `wrapKey` de `DEK || SHA-256(AAD canónico)` y, tras `unwrapKey`, comparación en tiempo constante del sufijo. Un AAD distinto falla igual que en los proveedores con AAD nativo.
5. **WORM por proveedor:** GCS con Bucket Lock más retención por objeto y `eventBasedHold`/`temporaryHold` para legal hold; Azure con immutable storage a nivel de versión (retención por blob y legal hold). Si el contenedor o bucket no tiene el modo inmutable habilitado, el adaptador falla al arrancar, no al primer uso.
6. **Verificación por contrato.** Suites abstractas `ObjectStoreContract`, `ImmutableStoreContract` y `KeyServiceContract`, heredadas por cada adaptador. Emuladores donde cubren el comportamiento; la semántica WORM y KMS que un emulador no implementa se prueba contra una interfaz fina interna con fake, y la real queda en un runbook de smoke bajo demanda.

## Alternativas consideradas
- **Todo dentro de `storage-port` y `kms-port`:** simple, pero mezcla tres SDKs en los puertos y acopla los tests.
- **Imagen por nube (perfiles Maven):** reduce superficie de CVE, pero rompe el digest único promovido entre entornos.
- **Azure firma con Ed25519 local envuelto por Key Vault:** cambia menos el puerto, pero la firma deja de ocurrir dentro del HSM.
- **Capa genérica tipo jclouds:** descartada en el ADR 0020.

## Consecuencias
- Positivas: una imagen para todos los destinos; el verificador no se puede degradar por datos hostiles; la duplicación de wiring desaparece.
- Negativas: la imagen carga los SDKs de las tres nubes (más superficie de CVE, cubierta por Trivy y SCA del ADR 0022); la verificación pública offline de firmas ES256 depende del proveedor hasta que se agregue un verificador local.
- Riesgo abierto: soporte real de WORM y KMS en emuladores sin confirmar; el spike T-00 lo decide antes de fijar la estrategia de pruebas.

## Controles relacionados
SEC-011, SEC-015, SEC-016, SEC-020, SEC-042, SEC-044, SEC-055, SEC-056
