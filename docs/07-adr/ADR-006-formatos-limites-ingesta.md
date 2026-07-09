# ADR-006: Formatos y límites de ingesta de documentos

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

HU-003 (subir documento vía API) dejaba ambiguo el alcance de formatos soportados en el MVP. Alternativas preguntadas: solo PDF (más simple, expandir después), multi-formato desde ya (PDF + imágenes + DOCX), o detección automática de formato sin lista cerrada. El usuario eligió explícitamente la segunda, no la recomendada.

## Decisión

La capa de Ingesta (EP-001) soporta multi-formato desde el MVP: PDF, imágenes (JPG/PNG), DOCX. La validación fail-fast (HU-004) rechaza cualquier formato fuera de esta lista con error explícito — no hay detección automática abierta a cualquier tipo de archivo.

## Consecuencias

- La capa de Procesamiento LLM (EP-002) debe normalizar la entrada de estos 3 formatos a una representación común antes de extracción (ej. render a imagen/texto), no asumir PDF como único caso.
- Añadir un formato nuevo en el futuro es extender la lista cerrada de HU-004, no un rediseño de la capa de Ingesta.
- Límites de tamaño/páginas por documento quedan fuera de este ADR — se definen como parámetro de configuración de dominio, no como decisión arquitectónica fija.
