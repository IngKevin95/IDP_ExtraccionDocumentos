# Especificación de Validadores Determinísticos

## 1. Propósito
Proveer un conjunto de reglas determinísticas (heurísticas, regex, chequeo de sumas, llamadas a catálogos) que evalúan los datos extraídos por el LLM, para incrementar el nivel de fiabilidad y servir como señal (feature) para el modelo de calibración final.

## 2. Alcance y no alcance
**Alcance:**
- Catálogo de validadores puros (Radicado, NIT, Cédula, Monto, Fechas, Sumas).
- Interfaz común `FieldValidator`.
- Estructura de respuesta de validación (Pasa, Falla, No Aplica).

**No alcance:**
- Ejecución de las peticiones al LLM.
- Lógica de auto-aprobación del documento (esto ocurre en el servicio de extracción).

## 3. Requisitos cubiertos
- **RF-204**: Validadores determinísticos (reglas estrictas).
- **RF-301**: Corrección acotada (El fallo envía el campo a HITL).

## 4. Reglas
- Todos los validadores deben ser sin estado (stateless) o utilizar un caché inmutable.
- Si un validador falla, debe retornar la causa estructurada, pero no detener el flujo; el resultado aporta al `score` final del campo.
- Un campo puede tener múltiples validadores.
- Todo validador referenciado en la tipología YAML debe existir en el registro.

## 5. Contrato
- Sin eventos (dominio puro, embebido en `extraction-service`).

## 6. Modelo de datos
- Sin tablas persistentes propias (son clases en tiempo de ejecución que cruzan contra datos estáticos o expresiones regulares).

### Catálogo de Validadores Mapeados
| ID en YAML | Clase Java | Regla |
|---|---|---|
| `radicado_23_digitos` | `RadicadoValidator` | Cumple longitud exacta de 23 dígitos y formato específico. |
| `juzgado_catalogo` | `JuzgadoValidator` | Fuzzy matching (>0.9) contra catálogo en memoria de ramas judiciales. |
| `monto_numeros_letras` | `MontoValidator` | Concordancia exacta entre el valor numérico y el valor extraído en letras. |
| `fechas_coherentes` | `DateCoherenceValidator` | Fecha de emisión no puede ser posterior al día de la recepción (hoy). |
| `tipo_doc_valido` | `TipoIdentificacionValidator` | Verifica que sea uno de los tipos soportados: CC, CE, NIT. |
| `cedula_formato` | `CedulaValidator` | Valida longitud (6-10 dígitos) y números sin DV. |
| `nit_modulo_11` | `NitValidator` | Aplica algoritmo de módulo 11 exacto al dígito de verificación. |
| `cedula_nit_condicional`| `IdentificacionCondicionalValidator` | Dependiendo del tipo de documento extraído, rutea a `CedulaValidator` o `NitValidator`. |
| `tipologia_valida` | `TipologiaValidaValidator` | Validar contra las 4 tipologías: EC, EJ, DC, DJ. |

## 7. Controles de seguridad
- **SEC-023 (Validación de entrada):** Prevenir inyecciones ReDoS limitando la longitud máxima de las cadenas procesadas con expresiones regulares en validadores de cédulas, NIT o radicados.

## 8. Escenarios de aceptación

- **AC-01 (Radicado 23 dígitos):** Given un string de 23 dígitos extraído, When pasa por `RadicadoValidator`, Then retorna éxito si cumple estructura AAAA... y longitud exacta.
- **AC-02 (NIT Módulo 11):** Given un NIT extraído con dígito de verificación, When se calcula módulo 11, Then retorna éxito si el dígito coincide, o falla si no coincide.
- **AC-03 (Cédula):** Given un número de cédula (sin dígito de verificación, solo longitud 6 a 10 y números), When se valida, Then retorna éxito si el formato es correcto.
- **AC-04 (Monto letras/números):** Given un monto numérico y su representación en texto en el mismo documento, When se cruzan, Then retorna éxito si coinciden semánticamente.
- **AC-05 (Fechas coherentes):** Given la fecha del oficio y la fecha de recepción, When se validan, Then la fecha del oficio no puede ser posterior al día de la recepción del banco.
- **AC-06 (Suma de tablas):** Given una tabla extraída con columnas de valor por demandado y un campo total general, When se corre `TableSumValidator`, Then valida que la suma de valores coincida con el total.
- **AC-07 (Juzgado en Catálogo):** Given un texto "Juzgado 05 Civil Municipal", When se valida contra el catálogo base de ramas judiciales, Then retorna éxito (fuzzy matching por encima de 0.9).
- **AC-08 (Protección ReDoS):** Given una cadena anómala de 50000 caracteres, When se envía al validador de radicado, Then es rechazada en O(1) por superar el límite estricto de longitud antes de aplicar Regex.
- **AC-09 (Dependencia estricta de validadores):** Given un archivo de tipología `embargos.v1.yaml` que referencia el validador `validador_ficticio`, When el sistema inicializa, Then arroja una excepción indicando validador no encontrado y detiene el arranque.

## 9. Métricas y SLO
- Latencia de validación por campo: < 5ms.

## 10. Dependencias
- Catálogo de juzgados y dependencias de la rama judicial.