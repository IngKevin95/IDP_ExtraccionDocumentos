# 0016. Auditoría hash-chain y WORM

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El IDP opera en el dominio crítico de embargos judiciales. El banco debe probar sin lugar a dudas quién operó sobre el oficio, en qué instante y qué aprobaciones se registraron. Necesitamos un registro incontrovertible que soporte repudio, donde ni siquiera un administrador con máximo nivel de acceso pueda alterar la historia sin que sea criptográficamente evidente.

## Decisión
Se implementa un `audit-service` como único escritor hacia un medio de almacenamiento inmutable mediante el uso de "Hash-chains" combinadas con almacenamiento de tipo WORM.
1. Estructura de Hash-chain y Consumo Kafka: Cada registro calcula un hash SHA-256 de su contenido y del registro anterior. El consumo desde Kafka se realiza en el tópico `auditoria.eventos` con clave `tenantId` mediante un consumidor con reintento BLOQUEANTE y alerta (sin `@RetryableTopic`, para no romper el orden estricto de la hash-chain).
2. Cero PII en Eventos (SEC-050): Se garantiza cero PII en los eventos Kafka implementando el patrón outbox y claim-check.
3. Contratos WORM y Anclaje: Los bloques se envían a un `ImmutableStore` con periodo de retención obligatorio. Se emplea una llave asimétrica dedicada (OpenBao Transit ed25519 o KMS asimétrico) y separada de las KEK para firmar el expediente. El ancla de cabeza de cadena por lote se almacena en el WORM.
4. Ciclo de Vida de Llaves de Auditoría: La KEK de auditoría tiene un ciclo de vida separado del resto; sobrevive hasta que expire la retención WORM y nunca se destruye bajo un escenario de legal hold.

## Alternativas consideradas
- Tablas auditables tradicionales: Un administrador podría alterar la base de datos.
- Blockchain privado / Hyperledger Fabric: Descartado por su inmensa sobrecarga operativa. La hash-chain combinada con WORM es suficiente.
- SIEM externo como única fuente: Limita la emisión de expedientes firmados autónomos.

## Consecuencias
- Positivas: Reducción del riesgo de manipulación. Fuerte postura defensiva para cumplimiento normativo.
- Negativas o costos: Cuello de botella de serialización mitigado con procesamiento asíncrono ordenado.

## Controles relacionados
SEC-039, SEC-040, SEC-041, SEC-042, SEC-050