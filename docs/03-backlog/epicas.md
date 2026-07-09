# Épicas — IDP - Extracción de Documentos

> Fuente: `docs/01-prd/idp-extraccion-documentos.md` (único PRD vigente).

## EP-001 — Ingesta y validación de documentos

**Resumen**: recepción de documentos vía API, validación estructural fail-fast y encolado asíncrono del trabajo de procesamiento.

**Por qué existe**: es el punto de entrada del pipeline; sin ingesta validada no hay nada que procesar, y validar temprano evita gastar cómputo LLM en documentos inválidos (patrón ya probado en DocFly).

**Objetivos del PRD que atiende**: #1 (pipeline funcional end-to-end).

**Capabilities incluidas**: Ingesta (recepción, validación fail-fast, resolución de config, encolado).

**Cómo se mide su éxito**: un documento inválido se rechaza antes de encolar; un documento válido queda encolado con su config de dominio resuelta.

## EP-002 — Procesamiento LLM configurable

**Resumen**: extracción de campos vía LLM, clasificación de tipo de documento y extracción de tablas, todo resuelto por configuración de dominio en vez de código hardcodeado.

**Por qué existe**: es el núcleo de inteligencia de la plataforma; generalizarlo (config-driven) es lo que distingue este proyecto de DocFly (que lo tenía hardcodeado a embargos).

**Objetivos del PRD que atiende**: #1 (pipeline funcional end-to-end), #2 (tipología config-driven).

**Capabilities incluidas**: Procesamiento (extracción, clasificación, tablas).

**Cómo se mide su éxito**: dado un documento válido y una config de dominio, el resultado estructurado persiste completo (campos + tipo + tablas si aplica).

## EP-003 — Consulta de resultados

**Resumen**: expone el estado y resultado de un documento procesado al consumidor, vía publish/poll y push/webhook.

**Por qué existe**: cierra el pipeline exponiendo el valor generado; sin consulta, el procesamiento no es utilizable por el integrador.

**Objetivos del PRD que atiende**: #1 (pipeline funcional end-to-end).

**Capabilities incluidas**: Consulta (estado, resultado).

**Cómo se mide su éxito**: el consumidor obtiene estado (pendiente/completo/error) y, si está completo, el resultado estructurado, vía API.

## EP-004 — Configuración de dominio (tipologías pluggable)

**Resumen**: mecanismo para definir tipologías de documento, campos a extraer y reglas de extracción por configuración (YAML/JSON), sin requerir cambio de código.

**Por qué existe**: es la decisión de alcance central del proyecto — generalizar de "solo embargos" a "IDP genérico" exige que el dominio sea un dato de configuración, no una decisión de arquitectura.

**Objetivos del PRD que atiende**: #2 (incorporar un dominio nuevo sin tocar el core).

**Capabilities incluidas**: Configuración de dominio.

**Cómo se mide su éxito**: se agrega una tipología nueva (distinta a embargos) editando solo config, sin PR de código en el core.

## EP-005 — Observabilidad de KPIs

**Resumen**: instrumentación de precisión de extracción, costo por documento y latencia, por documento procesado y agregable por dominio.

**Por qué existe**: el PRD fija los 3 KPIs como norte del proyecto; sin medirlos desde el primer release no hay forma de saber si el pipeline funciona bien ni de compararlo entre dominios.

**Objetivos del PRD que atiende**: #3 (precisión medible por dominio), #4 (costo y latencia instrumentados).

**Capabilities incluidas**: Observabilidad de KPIs.

**Cómo se mide su éxito**: cada documento procesado tiene costo y latencia registrados; los documentos con ground truth de prueba tienen precisión calculada.

## EP-006 — Piloto vertical: embargos y desembargos

**Resumen**: primera configuración de dominio real (tipologías EC/EJ/DC/DJ, heredadas conceptualmente de `dav-emb-v2-api-extraccion-entidades`) corriendo end-to-end sobre el core genérico.

**Por qué existe**: valida en la práctica que el diseño config-driven (EP-004) es suficiente para un dominio real, no solo teórico — es la prueba de que "generalizar a IDP genérico" no sacrificó viabilidad del caso de uso original.

**Objetivos del PRD que atiende**: #5 (embargos como primer caso de uso piloto).

**Capabilities incluidas**: ninguna nueva — consume EP-001 a EP-005 con una config real.

**Cómo se mide su éxito**: un documento de embargo/desembargo real (o sintético equivalente) se procesa end-to-end usando solo config, sin código específico de dominio en el core.

## Matriz Épica × Objetivo-PRD

| Épica | Obj. #1 (pipeline e2e) | Obj. #2 (config-driven) | Obj. #3 (precisión medible) | Obj. #4 (costo/latencia) | Obj. #5 (piloto embargos) |
|---|---|---|---|---|---|
| EP-001 Ingesta | ✅ | | | | |
| EP-002 Procesamiento LLM | ✅ | ✅ | | | |
| EP-003 Consulta | ✅ | | | | |
| EP-004 Config de dominio | | ✅ | | | |
| EP-005 Observabilidad KPIs | | | ✅ | ✅ | |
| EP-006 Piloto embargos | | | | | ✅ |

## Verificación cruzada

- **Objetivos sin épica que los cubra**: ninguno — los 5 objetivos del PRD tienen al menos una épica.
- **Épicas sin objetivo claro**: ninguna — las 6 épicas atan a al menos un objetivo del PRD.
