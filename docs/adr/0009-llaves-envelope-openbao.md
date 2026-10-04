# ADR 0009: Gestión Criptográfica y Crypto-Shredding con OpenBao (Llaves Envelope)

## Estado
Aprobado

## Contexto
El sistema financiero exige garantías robustas de privacidad y la capacidad de borrar información sensible sin posibilidad de recuperación forense (Habeas Data, derecho al olvido). Eliminar registros de bases de datos o borrar objetos de almacenamiento de forma lógica o mediante comandos estándar es insuficiente debido a la existencia de backups de largo plazo y políticas de persistencia. Se requiere una estrategia encriptación que permita la destrucción garantizada y soporte múltiples proveedores (agnosticismo cloud).

## Decisión
Se implementa una estrategia de **Cifrado de Sobre (Envelope Encryption)** gestionada mediante **OpenBao** (fork open-source de HashiCorp Vault), garantizando ciclos de vida de cifrado aislados por tenant, y una estrategia estricta de Crypto-Shredding.

1.  **Modelo Envelope (DEK y KEK):** Todo archivo, documento renderizado y vector será cifrado en origen usando una Llave de Encriptación de Datos (DEK) generada en memoria (simétrica AES-256-GCM). La DEK jamás se almacena en texto plano; en su lugar, se cifra utilizando una Llave de Encriptación de Llaves (KEK) administrada exclusivamente por OpenBao. El material cifrado guarda junto a sí la "DEK encriptada".
2.  **Aislamiento KEK por Tenant:** OpenBao gestionará motores de llaves (Transit Engine) con políticas estrictas que proveen al menos una KEK única e independiente por cada tenant.
3.  **Procedimiento de Crypto-Shredding (Purga Habeas Data):** Cuando un tenant exige la purga total o finaliza su ciclo de vida, la eliminación consta de dos pasos obligatorios:
    *   **Inhabilitación Inmediata:** Deshabilitar la KEK del tenant en OpenBao e invalidar cualquier caché de DEK en los servicios, provocando inaccesibilidad instantánea a todos los datos cifrados con ella.
    *   **Destrucción Física Irreversible:** Se activa la destrucción permanente del material criptográfico en OpenBao (`deletion_allowed` activo para esa KEK). Al destruir la KEK, todos los backups fríos, bases de datos o buckets encriptados con DEKs protegidas por dicha llave se vuelven criptográficamente irrecuperables de forma instantánea. Al iniciar este proceso se emite el evento `tenant.baja_iniciada` (el evento `documento.purgado` se usa únicamente para documentos individuales).
4.  **Separación de Llaves de Firma y Auditoría:** La firma digital de auditoría y documentos utilizará SIEMPRE una llave asimétrica dedicada y separada (ej. ed25519 o RSA), NUNCA la KEK simétrica. Además, las llaves utilizadas para firmar y proteger registros y logs WORM de auditoría mantienen un ciclo de vida independiente, sobreviviendo bajo restricciones de "Legal Hold", y no son afectadas por los procesos de crypto-shredding comerciales, asegurando la inmutabilidad de la cadena de evidencia.
5.  **Recuperación ante Desastres (DR) de Llaves:** Las KEK bajo ninguna circunstancia son exportables de OpenBao. Su supervivencia en DR se logra a través de replicación nativa de OpenBao o usando llaves multi-región. Destruir la KEK en el nodo primario implica destrucción en las réplicas.

## Alternativas Consideradas

*   **Delegar al Cifrado en Reposo Transparente del Disco (SSE-S3 / EBS Encryption):** Descartado. Si bien se utilizará, el cifrado del disco en reposo protege contra el robo físico de discos, pero no proporciona capacidades de crypto-shredding por tenant (si el disco se comparte). No garantiza que una eliminación lógica en la base de datos erradique los datos de los backups incrementales gestionados por el proveedor.
*   **Gestión de Llaves de AWS KMS (o similares) directo:** Descartado como solución única. Acoplarse fuertemente al KMS nativo de una nube impide el despliegue on-premise y complica las migraciones híbridas. OpenBao provee un API unificada (puerto `KeyService`), aunque subyacentemente OpenBao puede usar KMS de nube para su propio auto-unseal.

## Consecuencias
*   **Positivas:** Permite el despliegue agnóstico (OpenBao es nube agnóstico). Otorga a los auditores y oficiales de cumplimiento seguridad matemática demostrable de la eliminación de datos, satisfaciendo normativas financieras severas de Habeas Data sin tener que barrer y reescribir exabytes de backups históricos.
*   **Negativas / Riesgos:** OpenBao se convierte en el componente más crítico del sistema (Cero Trust Hub). Su caída (o sellado accidental por pérdida de quorum) inmoviliza toda la plataforma, impidiendo lectura y escritura de oficios. Demanda esquemas HA y políticas de respaldo extremadamente sólidas solo para OpenBao.

## Controles de Seguridad Aplicables
*   **SEC-028:** Borrado Seguro (Crypto-Shredding). Implementado y garantizado a través de la eliminación física de la KEK del tenant en OpenBao.
*   **SEC-015:** Cifrado Envelope. Estricta separación entre llaves de datos (DEK) en memoria y llaves maestras (KEK) no exportables gestionadas por el HS/KMS.
*   **SEC-014:** Separación de funciones (SoD). Los administradores de la plataforma no poseen roles de acceso para solicitar desencriptaciones en el Transit Engine de OpenBao; solo los servicios autorizados en tiempo de ejecución.