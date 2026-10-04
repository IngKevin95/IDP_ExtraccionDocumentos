# Tareas: Tipologías

- **T-01:** Definir modelo de dominio Java (`Typology`, `TypologyField`, `TypologyTable`). (AC-06, AC-07)
- **T-02:** Implementar el parser YAML `TypologyParser` usando Jackson con soporte de validación estructural contra `tipologia.schema.json`. (AC-01, AC-02)
- **T-03:** Crear migración Flyway para tabla `typology_definition` en la BD compartida de control.
- **T-04:** Implementar registro/caché `TypologyRegistry` al arranque de Spring. (AC-05, AC-08)
- **T-05:** Validar atributos críticos y enlace de validadores al cargar el esquema verificando su existencia. (AC-03, AC-04)