# Especificación de Tipologías de Documentos

## 1. Propósito
Definir el modelo y mecanismo de configuración dinámica de tipologías documentales (EC, EJ, DC, DJ) para el motor de extracción. Permite a negocio definir qué campos se extraen, sus tipos, criticidad y reglas de validación sin recompilar el código.

## 2. Alcance y no alcance
**Alcance:**
- Motor de validación de esquemas YAML de tipología.
- Cacheo y versionado de definiciones de tipología.
- Representación de oficios de embargo (EC, EJ) y desembargo (DC, DJ).

**No alcance:**
- Ejecución de los validadores (esto corresponde al módulo de validadores).
- Orquestación del LLM.

## 3. Requisitos cubiertos
- **RF-201**: Clasificación de tipología (EC, EJ, DC, DJ).
- **RF-102**: Ingesta idempotente y validación fail-fast (Los esquemas de extracción se configuran en YAML con validación fail-fast).

## 4. Reglas
- Toda tipología debe estar respaldada por un archivo YAML que cumpla el JSON Schema interno (`contracts/tipologias/tipologia.schema.json`).
- Un documento solo puede ser procesado si su clasificación inicial coincide con una tipología activa.
- Los campos marcados como `critico: true` requieren regla de cuatro ojos si van a revisión (HITL).

## 5. Contrato
- **Eventos Publicados:** Ninguno directamente (es dominio puro).
- **Eventos Consumidos:** Ninguno.
- **Archivos de Contrato:** 
  - `contracts/tipologias/embargos.v1.yaml`
  - `contracts/tipologias/tipologia.schema.json`

## 6. Modelo de datos
Base de datos: **Control** (las tipologías son globales del sistema, no por tenant).
- Tabla `typology_definition`:
  - `id` (UUID, PK)
  - `code` (VARCHAR, ej: 'EC')
  - `version` (INT)
  - `schema_yaml` (TEXT)
  - `active` (BOOLEAN)
  - Índices: UNIQUE(`code`, `version`).

## 7. Controles de seguridad
- **SEC-023 (Validación de entrada):** El YAML es validado estrictamente al inicio; si está mal formado, el sistema falla rápido (fail-fast) para evitar inyecciones de definición anómala.
- **SEC-036 (Inmutabilidad de versión):** Las versiones de tipologías no se pueden modificar una vez activas, solo se pueden crear nuevas versiones para mantener la trazabilidad de la extracción.

## 8. Escenarios de aceptación

- **AC-01 (Carga exitosa):** Given un YAML de tipología válido (`contracts/tipologias/embargos.v1.yaml`), When el sistema inicia, Then carga el esquema en caché y lo marca como activo para extraer oficios.
- **AC-02 (Fallo de esquema):** Given un YAML con sintaxis incorrecta o campos faltantes obligatorios, When el sistema lo lee contra `contracts/tipologias/tipologia.schema.json`, Then levanta una excepción `InvalidTypologyException` y el arranque falla.
- **AC-03 (Campos críticos):** Given una tipología con campo 'radicado' marcado como `critico: true`, When se consulta su esquema, Then el motor sabe que requiere cuatro ojos en caso de HITL.
- **AC-04 (Dependencia de validador):** Given un campo con `validator: nit_modulo_11`, When se valida la tipología, Then verifica que el validador exista en el registro del sistema (si no, falla el arranque).
- **AC-05 (Versionado):** Given una actualización de tipología 'EC' de v1 a v2, When se procesa un nuevo documento, Then usa la versión v2 activa sin afectar documentos pasados ya procesados con v1.
- **AC-06 (Listas y tablas):** Given la tipología exige extraer una tabla `demandados`, When se modela en YAML, Then se soporta un array de objetos con sus respectivos subcampos.
- **AC-07 (Tipos de datos):** Given un campo definido como `type: date`, When se expone la definición, Then el sistema sabe que el valor resultante debe seguir el formato ISO-8601.
- **AC-08 (Desactivación):** Given una tipología que se marca inactiva, When llega un documento de ese tipo, Then el sistema rechaza la extracción indicando "Tipología no soportada".

## 9. Métricas y SLO
- Tiempo de parseo y carga del YAML en memoria: < 50ms (al arranque).

## 10. Dependencias
- Módulo `validadores` (catálogo de validadores determinísticos).