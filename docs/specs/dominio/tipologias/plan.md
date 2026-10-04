# Plan de Implementación: Tipologías

## Módulo Maven
- `libs/domain-typology`

## Paquetes
- `com.banco.idp.domain.typology`
- `com.banco.idp.domain.typology.model`
- `com.banco.idp.domain.typology.repository`

## Clases principales
- `Typology`: Entidad base en memoria.
- `TypologyField`: Representación de campo.
- `TypologyParser`: Lee e hidrata YAML usando Jackson y JsonSchema.
- `TypologyRegistry`: Singleton en memoria (Caché).

## Migraciones Flyway
- `V1_0_0__create_typology_table.sql`: Tabla de tipologías (base control).

## Estrategia de tests
- Unitarios para parseo de YAML (`TypologyParserTest`).
- Testcontainers (PostgreSQL) para `TypologyRepository`.
