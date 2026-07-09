# ADR-002: Persistencia dual-adapter multi-motor con aislamiento por tenant

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

Instrucción explícita del usuario: *"La información debe estar separada por tenant totalmente independiente, y manejemos Postgres o Mongo, alguna DB que nos ayude a trabajar con volúmenes altos de datos pero no esté amarrada a GCP... debe tener la flexibilidad para manejar múltiples motores de BD"*.

Dos requisitos simultáneos: (1) aislamiento total multi-tenant, (2) no acoplarse a un único motor de base de datos.

## Decisión

Repository pattern con adaptador dual Postgres/MongoDB:
- Una interfaz de repositorio por agregado (documentos, configs de dominio, resultados, KPIs), sin fugas de detalles de motor hacia la capa de dominio.
- Dos implementaciones concretas (`PostgresRepository`, `MongoRepository`) intercambiables por configuración de despliegue, no por código.
- Aislamiento por tenant a nivel de esquema/base de datos lógica (Postgres: schema por tenant o row-level security; Mongo: base de datos por tenant) — nunca mezclado en la misma colección/tabla sin discriminador validado en cada capa de acceso.

## Consecuencias

- El dominio (capas de Ingesta, Procesamiento, Consulta) no conoce SQL ni sintaxis de Mongo — solo la interfaz de repositorio.
- Cambiar de motor (o soportar ambos en paralelo para distintos clientes) es una decisión de despliegue, no un refactor de negocio.
- Costo: mantener dos implementaciones de cada repositorio y sus tests de contrato compartido (mismo comportamiento observable en ambos adaptadores).
- El aislamiento por tenant se valida en cada adaptador de forma independiente — un bug de aislamiento en un motor no implica que el otro también lo tenga, pero tampoco se detecta gratis: requiere suite de tests de aislamiento corrida contra ambos.
