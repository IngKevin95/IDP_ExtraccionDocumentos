# IDP - Extracción de Documentos — Product Requirements Document

**Estado**: draft · **v0.1** · **Sponsor**: Kevin Orduz (product owner + dev) · **Actualizado**: 2026-07-07

## Resumen y objetivos

**Problema**: procesar documentos (PDF, escaneados) para extraer datos estructurados hoy exige, por cada dominio/cliente, construir de nuevo el mismo pipeline: validación de entrada, extracción vía LLM, clasificación de tipo de documento, extracción de tablas y exposición del resultado. DocFly (referencia, ver `docs/00-referencia-docfly/`) resolvió esto para un solo dominio (embargos/desembargos judiciales colombianos) con 11 microservicios acoplados a ese dominio y sin una capa de abstracción reutilizable.

**Por qué ahora**: existe ya un pipeline de referencia validado en producción (DocFly) con decisiones técnicas probadas (p. ej. estrategia de extracción de tablas, modelo por tarea en vez de un LLM único, validación fail-fast). El costo de partir de cero desaparece si se abstrae ese conocimiento en una plataforma IDP genérica, config-driven por dominio, en vez de reproducir el acoplamiento al caso legal colombiano.

Objetivos medibles (3-5):
1. Pipeline de extracción IDP funcional end-to-end (ingesta → procesamiento LLM → consulta) para al menos un dominio configurado, sin lógica de negocio hardcodeada en el core.
2. Tipología de documento y reglas de extracción resueltas por configuración (YAML/JSON), no por código nuevo, para incorporar un segundo dominio sin tocar el core.
3. Precisión de extracción medible por dominio configurado (ver KPIs) sobre un set de documentos de prueba.
4. Costo por documento procesado y latencia p95 instrumentados desde el primer release (baseline, no meta cerrada aún).
5. Embargos/desembargos judiciales colombianos operando como primer caso de uso piloto sobre el core genérico, reusando reglas de DocFly como config, no como código.

**Fuera de alcance en esta iteración**:
- UI de captura/anotación manual de documentos (la plataforma es API-first).
- Motor de reglas de negocio complejo multi-cliente (Capa 4 de DocFly: Airflow por-cliente, panel admin multi-proyecto) — se aborda en una fase posterior, no en el MVP.
- Soporte multi-tenant productivo con aislamiento fuerte por cliente — el MVP puede asumir un tenant/config a la vez; multi-tenancy real es decisión de ADR pendiente.
- Migrar o reemplazar cualquier servicio DocFly en producción — DocFly es solo inspiración, no hay integración con esos repos.

## Stakeholders

| Rol | Persona / equipo | Responsabilidad |
|---|---|---|
| Sponsor | Kevin Orduz | Define alcance, prioridad, acepta entregables |
| Product Owner | Kevin Orduz | Traduce necesidades del dominio piloto (embargos) a requisitos de plataforma |
| Equipo técnico | Kevin Orduz + agentes de código (Claude Code) | Construcción vía `@factory/factory-spec-build` sobre este PRD |
| Usuario primario | Integrador/cliente que consume la API IDP | Sube documentos, configura tipología, consume resultados estructurados |
| Usuario secundario | Operador de dominio (ej. analista de embargos) | Consulta resultados de extracción vía API/cliente downstream |
| Soporte / operaciones | Kevin Orduz | Monitoreo, incidentes, evolución de config por dominio |

<!-- TODO: si en el futuro hay clientes externos reales (no solo el piloto embargos), revisar esta tabla — hoy asume un solo operador end-to-end -->

## Historias de usuario (resumen)

> Detalle con AC en G/W/T vive en `docs/04-historias/`. Aquí solo perfil + necesidad.

- Integrador necesita subir un documento y recibir datos estructurados extraídos → automatiza captura de información sin construir su propio pipeline de IA.
- Integrador necesita definir la tipología de documento de su dominio por configuración (YAML) → incorpora un dominio nuevo sin depender de un release de código.
- Operador de dominio necesita consultar el estado y resultado de un documento ya procesado → integra el resultado en su flujo de trabajo sin acoplarse al tiempo de procesamiento (patrón publish/poll).
- Operador de plataforma necesita ver métricas de precisión, costo y latencia por dominio → decide si una configuración de extracción está lista para producción.

## Arquitectura de alto nivel

Generalización de las 4 capas funcionales identificadas en DocFly (`docs/00-referencia-docfly/capa-1..4`), separando lo genérico (core) de lo específico de dominio (config):

```
[Ingesta] ──→ [Procesamiento LLM] ──→ [Consulta]
   │                  │                   │
   └── config tipología (YAML) ───────────┘
                       │
              [Orquestación + Admin]  (fase posterior, no-MVP)
```

- **Ingesta**: recepción de documento, validación estructural fail-fast, resolución de tenant/config, encolado asíncrono (heredado de Capa 1 DocFly).
- **Procesamiento LLM**: extracción vía LLM configurable por dominio, clasificación de tipo de documento, extracción de tablas cuando aplica (heredado de Capa 2 DocFly, con modelo-por-tarea como decisión ya validada empíricamente en la referencia).
- **Consulta**: expone el resultado ya procesado, patrón publish/poll (heredado de Capa 3 DocFly).
- **Orquestación + Admin**: batch jobs específicos y panel de administración multi-tenant — no-MVP, DocFly ya muestra que esta capa introduce inconsistencias (motor de persistencia distinto, multi-tenancy resuelta dos veces) que no se quieren heredar sin decidirlas primero (ver ADRs pendientes).

<!-- TODO: decisión de topología (monolito modular vs. microservicios) es candidato a ADR, no resuelta aquí -->

## Funcionalidades por capability

**Ingesta**
- Recepción de documento (PDF) vía API.
- Validación estructural en capas (fail-fast antes de encolar cómputo costoso).
- Resolución de configuración de dominio/tipología activa.
- Encolado asíncrono del trabajo de procesamiento.

**Procesamiento**
- Extracción de campos vía LLM, con prompt/tipología resuelto por config, no hardcodeado.
- Clasificación de tipo de documento (config-driven, reemplaza el modelo ML fijo de DocFly por una capa configurable; conservar la opción de modelo ML específico como optimización posterior).
- Extracción de tablas estructuradas cuando el documento las contiene (heredar estrategia validada de DocFly: modelo lite + single-page + force image mode, como punto de partida, no como dogma).

**Consulta**
- Endpoint de estado de procesamiento (pendiente/completo/error).
- Endpoint de resultado estructurado una vez completo.

**Configuración de dominio**
- Definición de tipologías, campos a extraer y reglas de extracción por archivo de configuración (YAML/JSON), sin requerir cambio de código para un dominio nuevo.

**Observabilidad de KPIs**
- Registro de precisión, costo y latencia por documento procesado, agregable por dominio/config.

## UX y diseño

| Aspecto | Definición |
|---|---|
| Principios UX | Plataforma API-first; no hay UI de usuario final en el MVP. Documentación de API es la superficie de UX principal. |
| Accesibilidad | No aplica en el MVP (sin UI). Si se construye panel admin en fase posterior, aplicar WCAG AA. |
| Marca | No aplica en el MVP — sin identidad visual definida aún. |
| Referencias | Panel admin de DocFly (`docfly-paginas-fronted`) como referencia de funcionalidad, no de diseño, para cuando exista fase de administración. |

## Requisitos técnicos

- **Stack**: sin decidir formalmente — DocFly usa Python 3.10-3.13 + GCP (Cloud Functions Gen2/Cloud Run, Firestore, Pub/Sub, Vertex AI/Gemini) como stack de referencia probado; candidato por defecto salvo que el ADR de stack decida otra cosa.
- **Integraciones**: proveedor LLM (Vertex AI/Gemini en la referencia, candidato por defecto) para extracción y clasificación.
- **Performance**: p95 de latencia de procesamiento — meta a definir tras baseline (KPI #3, sin número fijado aún).
- **Seguridad**: sin credenciales ni datos de cliente reproducidos en ningún artefacto de este proyecto (ya aplicado en `docs/00-referencia-docfly/`). Gestión de secretos explícita — candidato a ADR, no resuelta aquí.
- **Disponibilidad**: sin SLO contractual en esta iteración (proyecto sin usuarios de producción todavía). Trigger explícito para definirlo: al cerrar el piloto embargos (Fase 7 del Anexo), con el baseline de latencia p95 ya medido, el Product Owner (Kevin Orduz) fija el primer SLO interno antes de la fase de Admin/Orquestación.
- **Observabilidad**: instrumentación mínima de los 3 KPIs (precisión, costo, latencia) desde el primer release del pipeline de procesamiento.

<!-- TODO: gestión de secretos, elección de almacenamiento (Firestore vs. PostgreSQL vs. híbrido) y topología de servicios son candidatos a ADR — no se asumen aquí -->

## Plan de entrega

| Fase | Duración | Hitos | Depende de |
|---|---|---|---|
| Discovery | — (sin fecha fija) | 4 `.md` de abstracción DocFly + este PRD | — |
| MVP — Ingesta + Procesamiento | ~70 min agente (Fases 1-4 del Anexo) | Documento sube → se extrae vía config de dominio → resultado en almacenamiento | Discovery |
| MVP — Consulta + KPIs | ~30 min agente (Fases 5-6 del Anexo) | Endpoint de estado/resultado disponible (publish/poll) + 3 KPIs instrumentados | Ingesta + Procesamiento |
| Piloto — dominio embargos | ~20 min agente (Fase 7 del Anexo) | Config de tipologías EC/EJ/DC/DJ (heredadas de `dav-emb-v2-api-extraccion-entidades`) corriendo sobre el core genérico | MVP completo |
| Iteración — Admin/Orquestación | — (sin estimar, depende de decisión de ADR) | Evaluar si se necesita panel admin / orquestación batch, y bajo qué ADR de multi-tenancy | Piloto validado |

Sin horizonte de calendario fijado (proyecto sin deadline de negocio); las fases son secuencia lógica, no cronograma. Los estimados de duración son de esfuerzo de agente de código (ver Anexo), no de calendario humano.

## Definición de "hecho" (producto)

- [ ] Un documento sube por API, se valida, se encola y se procesa sin intervención manual.
- [ ] El resultado de extracción se obtiene vía endpoint de consulta (publish/poll) para al menos un dominio configurado.
- [ ] Un segundo dominio se puede agregar cambiando solo configuración (YAML/JSON), sin tocar código del core.
- [ ] Los 3 KPIs (precisión, costo, latencia) se miden automáticamente por documento procesado.
- [ ] Ningún secreto, credencial ni dato de cliente real vive en el repositorio o en la documentación.

## KPIs

| Métrica | Método de cálculo | Baseline | Meta | Cadencia de medición | Responsable de fijar meta |
|---|---|---|---|---|---|
| Precisión de extracción (campos correctos / campos totales, vs. revisión manual) | Comparar salida del LLM contra ground truth anotado manualmente sobre un set de prueba (HU-011) | Por definir tras primer set de prueba (Fase 6/7 del Anexo) | Por definir tras baseline | Por corrida de test / por release | Kevin Orduz (PO), tras piloto embargos |
| Costo por documento procesado (USD, cómputo LLM incluido) | Sumar costo de tokens/llamadas LLM por documento (HU-012) | Por definir tras primer release | Por definir tras baseline | Continua (por documento) | Kevin Orduz (PO), tras piloto embargos |
| Latencia p95 (upload → resultado disponible) | Percentil 95 del tiempo total desde POST de ingesta hasta resultado consultable (HU-013) | Por definir tras primer release | Por definir tras baseline | Continua (por documento) | Kevin Orduz (PO), tras piloto embargos |

<!-- TODO: valores numéricos de baseline y meta requieren un primer set de documentos de prueba real — no se inventan aquí; el método de cálculo y el responsable ya quedan fijados -->

## Referencias

- Investigación de dominio: `docs/00-referencia-docfly/capa-1-ingesta.md`, `capa-2-procesamiento-llm.md`, `capa-3-consulta.md`, `capa-4-orquestacion-admin.md` — abstracción funcional de los 11 microservicios DocFly usados como inspiración (no como dependencia ni como código a reutilizar).
- Mockups / wireframes: no existen — plataforma API-first en esta iteración.
- Benchmarks: estrategia de extracción de tablas ya validada empíricamente en DocFly (`gemini-2.5-flash-lite` + `single_page` + `force image mode`), citada en `capa-2-procesamiento-llm.md` como punto de partida.
- Docs técnicas adicionales: `docs/adr/` (pendiente de crear) documentará las decisiones arquitectónicas de fondo listadas como TODO en este PRD.

## Anexo: fases para agentes de código

### Fase 1 — Ingesta: recepción y validación
- Dependencias: ninguna
- Resultado verificable: endpoint HTTP recibe un PDF, corre validación estructural (integridad, tamaño, páginas) y rechaza documentos inválidos con error claro antes de encolar.
- Alcance: no resuelve tenant ni config de dominio todavía; no publica a cola.
- Estimado: 15 min

### Fase 2 — Ingesta: resolución de config + encolado
- Dependencias: Fase 1
- Resultado verificable: dado un documento válido, el sistema carga la config de dominio activa (YAML) y publica un mensaje de trabajo (cola/tópico) con la referencia al documento + config resuelta.
- Alcance: no ejecuta extracción; solo prepara y encola el trabajo.
- Estimado: 15 min

### Fase 3 — Procesamiento: extracción LLM config-driven
- Dependencias: Fase 2
- Resultado verificable: un worker consume el mensaje encolado, invoca el LLM con el prompt derivado de la config de dominio, y persiste el resultado estructurado.
- Alcance: no incluye clasificación ni extracción de tablas todavía.
- Estimado: 20 min

### Fase 4 — Procesamiento: clasificación y tablas
- Dependencias: Fase 3
- Resultado verificable: el resultado persistido incluye tipo de documento clasificado y, si el documento contiene tablas, una extracción estructurada de esas tablas.
- Alcance: no modifica el contrato de la Fase 3, lo extiende.
- Estimado: 20 min

### Fase 5 — Consulta: estado y resultado
- Dependencias: Fase 3 (mínimo); Fase 4 idealmente completa
- Resultado verificable: endpoint de consulta responde estado (pendiente/completo/error) y, si está completo, el resultado estructurado — patrón publish/poll.
- Alcance: no incluye push/webhook; solo polling.
- Estimado: 15 min

### Fase 6 — Observabilidad de KPIs
- Dependencias: Fase 5
- Resultado verificable: cada documento procesado registra precisión (si hay ground truth de prueba), costo estimado y latencia total, consultables agregados.
- Alcance: no incluye dashboard visual, solo datos consultables.
- Estimado: 15 min

### Fase 7 — Piloto: config de dominio embargos
- Dependencias: Fase 6
- Resultado verificable: una config YAML para tipologías EC/EJ/DC/DJ (basada en las reglas de `dav-emb-v2-api-extraccion-entidades`, sin copiar prompts/datos originales) corre extremo a extremo sobre el core genérico.
- Alcance: no modifica el core; valida que la config-driven design sea suficiente para un dominio real.
- Estimado: 20 min
