# Runbook: smoke multicloud (F8)

Estado: sin ejecutar. Los comandos de este documento no se probaron en esta fase porque no hay cuentas de nube. Su objeto es que un operador con una cuenta de prueba confirme lo que ningun emulador cubre. Antes de usar un comando, contrastar sus opciones con `--help` de la CLI instalada.

Referencias: ADR 0032, `docs/specs/plataforma/multicloud/spec.md` (AC-22), plan.md secciones 7 y 8.

## 1. Alcance y criterio de salida

El smoke cubre, por destino (AWS, GCP, Azure), lo que los tests con emulador y fakes no demuestran:

| Verificación | Por qué no lo cubre un emulador | AC |
|---|---|---|
| Retención por objeto bloquea el borrado definitivo | fake-gcs-server y Azurite no implementan retención; LocalStack sí en S3 | AC-07 |
| Legal hold mantiene el bloqueo tras vencer la retención y se levanta con `removeLegalHold` | idem | AC-08 |
| El adaptador WORM falla al arrancar si el bucket o contenedor no tiene el modo inmutable | requiere el recurso real | AC-09 |
| `wrapDek`/`unwrapDek` con AAD: un AAD distinto falla | no hay emulador de Cloud KMS; Azure usa AAD emulado (SEC-056) | AC-10, AC-12 |
| Firma y verificación con el algoritmo declarado (`ed25519` en AWS y GCP, `es256` en Azure) | LocalStack 3.8 no soporta Ed25519 | AC-15, AC-16 |
| `disableKek` bloquea de inmediato y programa la destrucción | semántica del proveedor | AC-11 |

Criterio de salida: cada fila marcada OK por destino, con la evidencia (salida del comando o captura de la consola) archivada en el ticket del cambio. Cualquier desviación se registra como hallazgo y se corrige antes de declarar el destino listo.

## 2. Requisitos previos

- Cuenta, proyecto o suscripción de pruebas, separada de producción (SEC-046), con presupuesto y alertas de costo.
- Infraestructura aplicada con el módulo OpenTofu del destino (`infra/opentofu/<destino>`), con el bucket o contenedor WORM, las llaves y las identidades que crea.
- Quien ejecuta usa una identidad de pruebas con los permisos mínimos de cada paso; no usar el administrador de llaves para leer contenido ni al revés (SEC-011).
- Retenciones cortas para poder esperar su vencimiento: 1 a 2 minutos. En AWS, el modo COMPLIANCE no se puede acortar ni quitar: usar siempre un bucket de pruebas que se pueda eliminar al vencer la retención, o el modo GOVERNANCE.
- Variables de ejemplo en esta guía: `T=smoke-t1` (tenant), `K=datos` (llave de datos), `S=firma` (llave de firma).

## 3. Verificación a nivel de proveedor

### 3.1 AWS (S3 Object Lock y KMS)

1. Bucket con Object Lock: `aws s3api create-bucket --bucket $B --object-lock-enabled-for-bucket` (más `--create-bucket-configuration` fuera de us-east-1).
2. Retención: subir `$T/smoke.txt` con `aws s3api put-object --object-lock-mode GOVERNANCE --object-lock-retain-until-date <ahora+2min>`. Intentar `aws s3api delete-object --version-id <v>`: debe responder AccessDenied. Sin `--version-id` solo agrega un delete marker y el contenido retenido sigue en la versión anterior (`list-object-versions`).
3. Legal hold: `aws s3api put-object-legal-hold --legal-hold Status=ON` sobre la versión; esperar a que venza la retención; el borrado por versión sigue fallando; `Status=OFF` y el borrado funciona.
4. Arranque (AC-09): apuntar `idp.storage.bucket` a un bucket SIN Object Lock con `idp.storage.immutable=true`: el servicio de auditoría debe fallar al arrancar con un mensaje explícito.
5. KMS simétrica: `aws kms encrypt --key-id alias/idp/$T/$K --plaintext fileb://dek.bin --encryption-context doc=d-1`; `decrypt` con `doc=d-1` devuelve la DEK; con `doc=d-2` falla (InvalidCiphertextException).
6. KMS firma: `aws kms create-key --key-spec ECC_NIST_EDWARDS25519 --key-usage SIGN_VERIFY`, alias `alias/idp/$T/$S`; `aws kms sign --message-type RAW --signing-algorithm ED25519_SHA_512` y `aws kms verify` con los mismos datos (válido) y datos alterados (inválido). Mensajes RAW de hasta 4096 bytes. Extraer la clave pública con `aws kms get-public-key` y comprobar que el SPKI DER mide 44 bytes con prefijo `302a300506032b6570032100`.
7. Destrucción: `aws kms disable-key` y `schedule-key-deletion --pending-window-in-days 7`; `decrypt` debe fallar de inmediato con KMSInvalidStateException o DisabledException.

### 3.2 GCP (Cloud Storage y Cloud KMS)

1. Bucket con retención por objeto: `gcloud storage buckets create gs://$B --enable-per-object-retention` (más ubicación y acceso uniforme).
2. Retención: subir el objeto y fijar `--retain-until` con modo bloqueado (`gcloud storage objects update --retention-mode=Locked --retain-until=<ahora+2min>`). El borrado antes del vencimiento debe fallar.
3. Legal hold: `gcloud storage objects update --temporary-hold`; vencida la retención, el borrado sigue fallando; `--no-temporary-hold` y el borrado funciona. La cuenta de servicio de auditoría solo tiene `storage.objects.update` sin `delete` (módulo OpenTofu).
4. Arranque (AC-09): con un bucket sin retención por objeto, `GcsImmutableStore` debe fallar al arrancar.
5. KMS simétrica: la CryptoKey se llama `idp-<59 hex>` (`KeyNames.hashed`); `gcloud kms encrypt --additional-authenticated-data-file` con un AAD y `decrypt` con otro: debe fallar con INVALID_ARGUMENT.
6. KMS firma: CryptoKey con propósito `asymmetric-signing` y algoritmo `ec-sign-ed25519`; `gcloud kms asymmetric-sign` y verificación local con la clave pública (`gcloud kms keys versions get-public-key`). Tras crear una nueva versión (rotación), una firma anterior debe seguir verificando con la clave pública de su versión (SEC-042, firmas con prefijo `vault:v<N>:`).
7. Destrucción: deshabilitar y `gcloud kms keys versions destroy`; el descifrado falla con FAILED_PRECONDITION.

### 3.3 Azure (Blob Storage y Key Vault)

1. Contenedor con WORM por versión: `az storage container-rm create --enable-vlw true` (el módulo OpenTofu lo crea con `azapi`).
2. Retención por versión: `az storage blob immutability-policy set --policy-mode Unlocked --expiry-time <ahora+2min>` sobre el blob. Intentar borrar la versión: debe fallar. Un `delete` sin versión solo crea una versión nueva en un contenedor versionado.
3. Legal hold: `az storage blob set-legal-hold --legal-hold true`; vencida la retención, el borrado de la versión sigue fallando; `false` y el borrado funciona.
4. Arranque (AC-09): con un contenedor sin WORM por versión, `AzureBlobImmutableStore` debe fallar al arrancar.
5. Roles: con la identidad de auditoría (rol personalizado `audit-worm-writer`), confirmar que puede escribir, fijar la política de inmutabilidad y el legal hold, y que no puede borrar. Si falta una acción de datos para fijar la política, añadirla al rol sin incluir el borrado.
6. Key Vault, llave de datos: llave RSA de 3072 bits; `wrapKey`/`unwrapKey` con RSA-OAEP-256 (CLI: `az keyvault key encrypt/decrypt`) sobre `DEK || SHA-256(AAD)`. Key Vault no tiene AAD: la comparación del sufijo la hace el adaptador (SEC-056), por lo que este paso solo confirma el viaje de ida y vuelta del material.
7. Key Vault, firma: llave EC P-256; `az keyvault key sign --algorithm ES256 --digest <sha256>` devuelve 64 bytes `r||s`; `verify` válido e inválido. No existe Ed25519 en Key Vault.
8. Destrucción: deshabilitar y eliminar la llave (soft-delete con purge protection); `unwrapKey` debe fallar.

## 4. Verificación a nivel de adaptador

Con los puntos de 3.x en verde, repetir con los servicios reales para confirmar el cableado de punta a punta:

1. Desplegar con `values-<destino>.yaml` y verificar que los pods arrancan (`IDP_STORAGE_PROVIDER` e `IDP_KMS_PROVIDER` coherentes con `global.adapters`; un proveedor ausente o desconocido impide el arranque, AC-02).
2. Cargar un oficio sintético (`tools/synthetic-oficios`) y comprobar que el artefacto cifrado aparece en el almacenamiento del tenant y se descifra al leerlo.
3. Forzar un anclaje WORM en `audit-service` y verificar el expediente firmado: el campo `algorithm` debe ser `ed25519` (AWS, GCP) o `es256` (Azure) y la verificación debe pasar. Alterar el campo `algorithm` del documento almacenado debe invalidarlo sin llamar al proveedor (SEC-055).
4. Rotar la llave de firma y comprobar que los expedientes anteriores siguen verificando (GCP: etiqueta de versión; AWS y Azure: una llave por identificador, sin rotación de la misma llave).
5. `crypto-shredding` de un tenant de pruebas: deshabilitar su KEK y confirmar que sus artefactos dejan de descifrarse en todas las réplicas (el bloqueo en proceso es inmediato; otras réplicas lo ven cuando el proveedor responde llave deshabilitada, con consistencia eventual de segundos).

## 5. Limpieza

- Eliminar los objetos y contenedores de prueba cuando venza su retención y no haya legal hold; en COMPLIANCE no se pueden eliminar antes.
- Programar la destrucción de las llaves de prueba (AWS 7 días como mínimo; GCP según la ventana de la llave; Azure con purge protection hay que esperar el periodo de retención del borrado lógico).
- Destruir el entorno de pruebas con OpenTofu solo tras retirar las protecciones de borrado (`prevent_destroy`, locks) según el README del módulo.
- Registrar el resultado: fecha, destino, versión del chart y de las imágenes, y los comandos con su salida.

## 6. Qué no cubre este runbook

- Selección y calibración del proveedor de LLM por nube (fuera de F8).
- Carga y caos por destino (F7 se midió sobre el destino de referencia).
- Idempotencia de `tofu plan` en cada nube (AC-08): requiere credenciales y se ejecuta en el pipeline de infraestructura.
