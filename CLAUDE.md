# CLAUDE.md

## Proyecto

IDP bancario: backend 100% Java (Spring Boot) para extracción de oficios de embargo/desembargo y chat de preguntas y respuestas sobre documentos, multi-tenant con silo por tenant, arquitectura por eventos (Kafka), desplegable en Kubernetes sobre AWS, GCP, Azure o self-hosted. Rumbo y decisiones: `docs/plan-maestro.md`.

## Construcción por especificaciones (regla dura)

1. Sin spec aprobada no hay código. Specs en `docs/specs/plataforma/<tema>/` y `docs/specs/servicios/<servicio>/` con `spec.md`, `plan.md`, `tasks.md`.
2. Cada escenario de aceptación (Given/When/Then) de un `spec.md` se implementa como test (unitario o de integración con Testcontainers).
3. Toda decisión arquitectónica nueva o que contradiga una existente requiere ADR en `docs/adr/NNNN-slug.md` (contexto, decisión, alternativas, consecuencias, controles de seguridad relacionados).
4. Todo control de seguridad vive en `docs/seguridad/matriz-controles.md` con ID; las specs referencian esos IDs.
5. Contratos: OpenAPI en `contracts/openapi/`, eventos en `contracts/events/` (JSON Schema versionado). El código se ajusta al contrato, no al revés.
6. Nunca datos personales reales en el repo. Golden set solo con oficios sintéticos (`tools/synthetic-oficios`).

## Convenciones Java

- Java 21 LTS mínimo, Spring Boot, Maven multi-módulo (`libs/`, `services/`).
- Usar lo que el framework ya resuelve (Spring Security, Spring Kafka, Spring AI, Actuator, Flyway) antes de escribir infraestructura propia.
- Puertos Java para dependencias de proveedor (`ObjectStore`, `ImmutableStore`, `KeyService`, `LlmProvider`); adaptadores por proveedor.
- Eventos con outbox transaccional y claim-check (sin PII en Kafka); consumidores idempotentes.
- Imágenes sin root y compatibles con UID arbitrario (OpenShift).

## Git workflow (Gitflow estricto)

- Ramas: `main` (producción), `develop` (integración). Cero commits directos a ambas; todo entra por PR.
- Naming: `feature/<slug>`, `bugfix/<slug>`, `docs/<slug>`, `chore/<slug>`, `release/vX.Y.Z`, `hotfix/vX.Y.Z-<slug>`.
- Merge solo con merge commit (`--no-ff`); prohibido squash/rebase.
- Conventional Commits: `<tipo>(<scope>): <asunto>` en imperativo, máx. 50 caracteres. Tipos: feat, fix, refactor, test, docs, chore.
- Autoría: solo la cuenta humana. Sin co-autoría ni créditos de IA en commits, PRs o código.

## Ejecución con agentes

Constructores y auditores corren en OpenCode con modelos Antigravity según la sección 8 del plan maestro. OpenCode no hace commit ni push; el orquestador verifica (build/tests), consigue el veredicto del auditor y abre el PR. Logs en `.oc-logs/`.
