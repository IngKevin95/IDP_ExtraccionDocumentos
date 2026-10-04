# Tareas: Validadores

- **T-01:** Crear contrato `FieldValidator` y DTO `ValidationResult`.
- **T-02:** Implementar `RadicadoValidator` con protección de longitud máxima y Regex de 23 dígitos. (AC-01, AC-08)
- **T-03:** Implementar `NitValidator` basado en la lógica de módulo 11 de la DIAN. (AC-02)
- **T-04:** Implementar `CedulaValidator` evaluando estrictamente longitud de formato numérico sin DV. (AC-03)
- **T-05:** Implementar `MontoValidator` cruzando representaciones textuales de montos si la tipología lo dispone. (AC-04)
- **T-06:** Implementar `DateCoherenceValidator` validando fecha emisoria <= hoy. (AC-05)
- **T-07:** Implementar `TableSumValidator` para asegurar balances contables a nivel de filas. (AC-06)
- **T-08:** Implementar `JuzgadoValidator` integrando catálogo cargado en memoria y fuzzy matching simple (ej. Jaro-Winkler). (AC-07)
- **T-09:** Implementar validación en arranque que verifica la existencia de todos los validadores referenciados en YAML. (AC-09)