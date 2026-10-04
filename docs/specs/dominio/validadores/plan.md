# Plan de Implementación: Validadores

## Módulo Maven
- `libs/domain-validators`

## Paquetes
- `com.banco.idp.domain.validators`
- `com.banco.idp.domain.validators.impl`

## Clases principales
- `FieldValidator<T>`: Interfaz base.
- `ValidationResult`: Record que almacena `isValid`, `reason`.
- `ValidatorFactory`: Retorna instancias por nombre desde el YAML.
- Implementaciones (Radicado, NIT, Cédula, Fecha, Monto, Tabla).

## Migraciones Flyway
- N/A

## Estrategia de tests
- Tests unitarios parametrizados exhaustivos con Junit 5 (`@ParameterizedTest`) para bordes de Regex, Módulo 11 y sumas.
