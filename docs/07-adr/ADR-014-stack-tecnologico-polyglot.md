# ADR-014: Stack tecnológico polyglot por microservicio

**Estado**: Aceptado
**Fecha**: 2026-07-08

## Contexto

Ningún artefacto previo (PRD, ADR-001 a ADR-013) fija lenguaje/framework — solo restringen patrones (K8s, S3-compatible, LLM vía API externa, ADR-003). El proyecto es de portafolio, sin demo pública (solo local, Docker Compose/K8s local). El usuario pidió explícitamente proponer "el mejor lenguaje para cada microservicio" en vez de un stack único, para demostrar rango técnico.

## Decisión

Stack asignado por capa lógica (ADR-001):

- **Ingesta (EP-001)** y **Procesamiento LLM (EP-002)**: Python 3.12 + FastAPI. Razón: SDKs de LLM y librerías de validación PDF/tablas son Python-first.
- **Consulta (EP-003)**, incluye webhook (HU-016): Go. Razón: servicio de solo lectura/alta concurrencia, sin dependencias ML.
- **BFF para frontend**: Java 21 + Spring Boot 3. Orquesta Consulta + KPIs, expone API única al frontend.
- **Config de dominio (EP-004)**: librería compartida Python + Pydantic, no microservicio propio.
- **Frontend**: Next.js 14 (App Router) + TypeScript + Tailwind + shadcn/ui + Framer Motion + React Flow.
- **Persistencia híbrida** (consistente con ADR-002 dual-adapter): Postgres (datos relacionales — config, tenants) + MongoDB (documentos de resultado de extracción, estructura variable por tipología). MongoDB elegido sobre alternativas (DynamoDB-local, Cassandra, Firestore-emulator) por ser dockerizable con homólogo cloud (Atlas) sin atarse a un proveedor (respeta ADR-003).

## Consecuencias

- Cada servicio se empaqueta como contenedor independiente (compatible con ADR-003), sin acoplar el proyecto a un único runtime.
- El polyglot exige mantener contratos de API explícitos entre servicios (OpenAPI) ya que no hay tipos compartidos entre lenguajes.
- Mayor superficie de aprendizaje/mantenimiento que un stack único — aceptado como trade-off deliberado: el objetivo es mostrar dominio técnico amplio en un portafolio, no minimizar costo operativo de un equipo real.
