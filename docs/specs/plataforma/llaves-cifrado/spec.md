# Especificación de Plataforma: Llaves y Cifrado

## Propósito
Proteger la confidencialidad de los datos en reposo a nivel de aplicación, gestionando el ciclo de vida de las llaves de cifrado mediante una arquitectura de cifrado de sobre (Envelope Encryption).

## Alcance y no alcance
Alcance: Envelope encryption, generación de Data Encryption Keys (DEK), proteccion con Key Encryption Keys (KEK) para datos y auditoría, llaves de firma, crypto-shredding, y cache segura de DEKs.
No alcance: Cifrado a nivel de disco de la base de datos (delegado al proveedor cloud TDE).

## Requisitos cubiertos
RF-603, RNF-101.

## Reglas
1. Toda informacion sensible o PII debe cifrarse en la capa de aplicación antes de guardarse en disco.
2. Cada tenant debe tener al menos una KEK dedicada.
3. El borrado de un tenant debe incluir la deshabilitación inmediata e invalidación de caché de la DEK, seguida de la destrucción irreversible de la KEK de datos al final de la ventana del proveedor (crypto-shredding). La KEK de auditoría mantiene su propio ciclo de vida y el borrado se bloquea si existe un "legal hold" (SEC-016, SEC-017).
4. Las DEKs deben ser almacenadas cifradas junto a los datos que protegen.

## Contrato
Interfaces internas: `KeyService` (generateDek, unwrapDek), `CryptoService` (encrypt, decrypt).

## Modelo de datos
Base de datos de control: Tabla `tenant_keys` (tenant_id, kek_id, provider_arn, status).
Registros operativos: Columnas `encrypted_data` y `wrapped_dek` en las tablas que almacenan PII.

## Controles de seguridad
SEC-015 Envelope encryption para minimizar la exposicion de las llaves maestras.
SEC-016 Crypto-shredding para asegurar la imposibilidad tecnica de recuperar datos tras borrado.

## Escenarios de aceptacion

### AC-01 Generacion de DEK y cifrado
Given un nuevo documento sensible
When el sistema procede a guardarlo
Then genera una DEK aleatoria, cifra el documento con la DEK, y envuelve (cifra) la DEK con la KEK del tenant.

### AC-02 Descifrado con cache de DEK
Given un documento cifrado previamente y su wrapped DEK
When el sistema necesita leer el documento
Then desenvuelve la DEK consultando el KMS, la guarda temporalmente en memoria (cache segura) y descifra el documento.

### AC-03 Expiración de cache de llaves
Given una DEK almacenada en la cache en memoria
When transcurre el tiempo de vida (ej. 15 minutos)
Then la llave es desalojada y sobrescrita en memoria, forzando una nueva llamada al KMS en el siguiente acceso.

### AC-04 Crypto-Shredding efectivo y Legal Hold (SEC-016, SEC-017)
Given una orden de borrado físico del tenant T1 sin "legal hold" activo
When se invoca el proceso de destrucción
Then el sistema deshabilita inmediatamente la KEK de datos, invalida la caché de DEKs correspondientes, y programa la destrucción irreversible al final de la ventana del KMS, manteniendo intacta la KEK de auditoría.

### AC-05 Rotación de KEK transparente
Given que la politica de seguridad exige rotacion anual
When el KMS rota la KEK principal del tenant
Then los nuevos datos usan la nueva version de la llave, mientras los antiguos siguen pudiendo descifrarse con las versiones anteriores.

### AC-06 Falla de conexión al KMS
Given un intento de envolver o desenvolver una llave
When el proveedor KMS no está disponible
Then el sistema aborta la operacion, registra el incidente de seguridad y devuelve error 503 sin comprometer texto plano.

### AC-07 Protección de llave de firma
Given un requerimiento para firmar digitalmente una exportacion
When el sistema procesa el archivo
Then utiliza una KEK dedicada de firma, distinta a la KEK de cifrado de datos.

### AC-08 Prevención de fuga de memoria de llaves
Given el uso de variables para descifrar datos en RAM
When el metodo de descifrado finaliza
Then el sistema sobreescribe activamente los arreglos de bytes de las llaves planas (zeroing) en memoria.

## Métricas y SLO
SLO: Latencia de llamadas a KMS inferior a 50ms (p95).
Métricas: Uso de cache de DEKs (hits/misses), cantidad de llamadas al KMS por minuto.

## Dependencias
Proveedor KMS (AWS KMS, Azure KeyVault o Hashicorp Vault).
Librería criptográfica compatible con AEAD (ej. AES-GCM).
