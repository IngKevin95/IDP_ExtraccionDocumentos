# 0024. Retención, legal hold, habeas data y offboarding

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El sistema maneja PII bajo leyes de protección de datos (Habeas Data, Derecho al Olvido), mientras que paralelamente está obligado normativamente a preservar los oficios procesados durante retenciones a largo plazo. El término de contratos de los clientes exige remoción segura sin dejar fragmentos huérfanos. Las destrucciones deben respetar los bloqueos inmutables ("legal hold").

## Decisión
Las operaciones adoptan un enfoque basado en crypto-shredding, purgas físicas y estados superpuestos:
1. Legal Hold Absoluto: Este estado anula inmediatamente rutinas de eliminación. Cualquier intento de crypto-shredding o purga sobre objetos o tenants bajo legal hold será denegado. Genera los eventos `legalhold.aplicado` y `legalhold.liberado`.
2. Habeas Data Exhaustivo y Eventos: Las purgas de datos implican la eliminación física y concurrente del blob de almacenamiento, las páginas procesadas, los chunks vectoriales en el índice de pgvector y todas las cachés locales aplicables. Su finalización exitosa emite un evento `documento.purgado`.
3. Crypto-shredding centralizado: Ante una baja (que activa de inmediato `tenant.baja_iniciada`), la KEK de datos del tenant se deshabilita instantáneamente invalidando la caché de la Data Encryption Key (DEK). Posteriormente se ejecuta su destrucción irreversible acorde a los tiempos del proveedor KMS u OpenBao (`deletion_allowed`). 
4. Excepción de Auditoría: La KEK de auditoría WORM mantiene un ciclo de vida separado y sobrevive a las solicitudes comerciales de offboarding, manteniéndose viva hasta la expiración de la retención WORM y mientras exista un estado de "legal hold" activo (no perennemente).

## Alternativas consideradas
- Scripts manuales de base de datos DBA: Riesgoso, carece de registro inmutable nativo.
- Truncate/Delete cascades sencillos sin criptografía: Lentos y con difícil garantía matemática de borrado a bajo nivel. El crypto-shredding garantiza que los datos son irrecuperables de inmediato al inutilizar la DEK.
- Eliminar logs de auditoría ante solicitudes de Habeas Data: Totalmente bloqueado dado el mandato regulatorio del banco.

## Consecuencias
- Positivas: Resolución formal a los conflictos de retención cruzada. Apagado criptográfico seguro altamente valorado en auditorías institucionales.
- Negativas o costos: Diseñar rutinas coreográficas que armonicen S3, base de datos y OpenBao de manera transaccional.

## Controles relacionados
SEC-016, SEC-017, SEC-021, SEC-022, SEC-047