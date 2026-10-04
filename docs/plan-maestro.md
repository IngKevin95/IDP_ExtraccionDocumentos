# Plan maestro — IDP bancario

Estado: aprobado 2026-09-29. Fuente de verdad del rumbo del proyecto hasta que `docs/producto.md`, `docs/arquitectura.md` y `docs/adr/` lo detallen. Si un documento posterior contradice este plan, gana el documento posterior solo si un ADR lo justifica.

## 0. Decisiones consolidadas

| Tema | Decisión |
|---|---|
| Alcance | Producto listo para producción (no MVP), solo backend. Portafolio con posibilidad de venta a un banco. |
| Capacidades | (1) Extracción de oficios de embargo/desembargo (tipologías EC, EJ, DC, DJ). (2) Chat de preguntas y respuestas sobre un documento (RAG) con citación verificable. |
| Lenguaje | 100% Java. Java 21 LTS mínimo (evaluar 25 LTS en F3), Spring Boot, Spring AI, Maven multi-módulo. |
| Topología | Microservicios por frontera de confianza y fuerza (no por capa ni por moda). |
| Comunicación | Arquitectura por eventos sobre Kafka self-hosted (Strimzi). Outbox transaccional, claim-check (eventos sin PII: solo IDs y metadatos), consumidores idempotentes, pipeline orquestado y efectos secundarios coreografiados. Sin tópicos/credenciales por tenant. |
| Borde | Spring Cloud Gateway delgado: TLS, JWT, rate limit, límites de tamaño, ruteo. Sin lógica de negocio. |
| Datos | Silo por tenant: base de datos, bucket de documentos, bucket de auditoría (WORM), llaves (KEK datos + KEK auditoría) e índice vectorial. Base de control compartida sin contenido documental. Cómputo compartido (nivel dedicado opcional). |
| Extracción | Sin OCR. Páginas renderizadas a imagen (PDFBox) hacia LLM multimodal, few-shot, modelo por tarea. Tablas por página (single page + image mode + modelo lite, estrategia validada en DocFly). |
| Fiabilidad | Validadores determinísticos + grounding contra capa de texto nativa + evidencia obligatoria + segunda pasada en cascada. Score calibrado contra golden set. Revisión humana solo por campo dudoso, cuatro ojos en campos críticos. |
| Plataforma | Kubernetes con operadores desde el sprint 1. Testcontainers para desarrollo. Argo CD (GitOps). Compatible con OpenShift (no root, UID arbitrario). |
| Destinos | AWS, GCP, Azure y self-hosted (k3s, RKE2, OpenShift). |
| Metodología | Construcción por especificaciones. Sin Factory. |

## 1. Servicios

| Servicio | Responsabilidad | Privilegio distintivo |
|---|---|---|
| `edge-gateway` | TLS, JWT, rate limit, tamaño, ruteo | Único expuesto a internet |
| `document-service` | Carga idempotente, versiones, estados, orquestación del pipeline | Escribe bucket del tenant |
| `renderer` (sandbox) | Validación de archivo (magic bytes, límites, PDF con JS), antivirus (ClamAV), rasterizado de páginas | Sin credenciales, sin egress, FS solo lectura |
| `extraction-service` | Clasificación, campos, tablas, validadores, score calibrado, ruteo a revisión | Credenciales LLM |
| `review-service` | Cola de revisión humana por campo, cuatro ojos, correcciones | Rol revisor |
| `quality-service` | Golden set, calibración por modelo+prompt, muestreo ciego, deriva, gate CI, replay | Solo lectura, independiente del evaluado |
| `chat-service` | Chat sobre documento, pgvector, citación verificable, transcripción perezosa | Credenciales LLM + índice |
| `notification-service` | Webhooks HMAC, anti-SSRF, DLT | Única salida a internet |
| `audit-service` | Cadena de hash, WORM por tenant, expediente firmado | Único escritor de auditoría |
| `tenant-service` | Alta/baja de tenants, silos, llaves, planes, cuotas | Privilegio de plataforma, red de administración |

Flujo del oficio:

```
cliente → gateway → document (bucket tenant) → renderer (PNG por página)
  → [cmd] extraction: clasificar → campos → tablas por página → score calibrado
      ├─ auto-aprobado ───────────┐
      └─ dudoso → review (HITL) ──┤
  → evt extraccion.aprobada → notification · audit · quality (muestreo) · chat (indexación)
```

## 2. Fiabilidad de extracción

Señales por campo (de barata a cara): validadores determinísticos (radicado 23 dígitos, NIT con dígito de verificación, cédula por formato/longitud —no tiene dígito de verificación—, monto números = letras, fechas coherentes, suma de tabla = total, juzgado en catálogo); grounding contra capa de texto nativa cuando exista; evidencia obligatoria (página + texto literal); autoconsistencia en cascada solo sobre campos dudosos (otro prompt u otra familia de modelo); logprobs si el proveedor los expone.

Ruteo: `score ≥ τ_auto` auto-aprueba; `τ_revisar ≤ score < τ_auto` segunda pasada; menor o validador duro fallido → revisión humana del campo. `τ_auto` se fija por precisión objetivo sobre golden set calibrado (isotónica/Platt); la tasa de revisión humana es consecuencia medida, no promesa.

Métricas: precisión/recall por campo y tipología (CI + nocturno), tasa STP, tasa HITL, error real de lo auto-aprobado por muestreo ciego (1-2%), tasa de corrección humana, ECE, deriva, costo por oficio, latencia p95.

Chat: fidelidad (cita existe en transcripción + juez LLM en ambiguos), cifras exactas contra campos validados, tasa de "información insuficiente", feedback del usuario.

Golden set: generador de oficios sintéticos (`tools/synthetic-oficios`) con tablas, sellos, ruido, rotación e identificaciones ficticias válidas en formato; cada oficio trae su JSON de verdad. Nunca PII real en el repo.

## 3. Despliegue multi-destino

Núcleo portable, adaptadores por proveedor detrás de puertos Java.

| Dependencia | Puerto | Self-hosted | AWS | GCP | Azure |
|---|---|---|---|---|---|
| Kubernetes | — | k3s / RKE2 / OpenShift | EKS | GKE | AKS |
| Kafka | Spring Kafka | Strimzi | Strimzi | Strimzi | Strimzi |
| Postgres + pgvector | JDBC | CloudNativePG | RDS/Aurora o CNPG | Cloud SQL o CNPG | Azure PG Flexible o CNPG |
| Objetos | `ObjectStore` | Ceph RGW / SeaweedFS (S3) | S3 | GCS nativo | Blob nativo (sin API S3) |
| WORM | `ImmutableStore` | S3 Object Lock compliance | S3 Object Lock | Bucket Lock | Immutable storage bloqueado |
| Llaves por tenant | `KeyService` (envelope) | OpenBao Transit | AWS KMS | Cloud KMS | Key Vault / Managed HSM |
| Secretos dinámicos | — | OpenBao | OpenBao + auto-unseal KMS | idem | idem |
| Identidad de workload | — | ServiceAccount + OpenBao | EKS Pod Identity | Workload Identity | Workload Identity |
| LLM | `LlmProvider` (Spring AI) | vLLM (GPU) | Bedrock | Vertex AI | Azure OpenAI / AI Foundry |
| WAF | — | Coraza en ingress | AWS WAF | Cloud Armor | Front Door / App Gateway |
| Registro | — | Harbor | ECR | Artifact Registry | ACR |
| Observabilidad | OpenTelemetry | kube-prometheus-stack + Loki | o CloudWatch | o Cloud Monitoring | o Azure Monitor |

Notas: OpenBao (MPL) sobre Vault (BSL) y OpenTofu sobre Terraform por licencia. Verificar estado de licencia/distribución de MinIO antes de usarlo. Cada destino pasa su propio gate de golden set: la calibración es por par modelo+prompt, los umbrales no se copian entre nubes.

Artefactos: `infra/opentofu/{aws,gcp,azure}`, `deploy/helm` (umbrella + values por destino), `deploy/argocd`. Verificación: tests de contrato por adaptador con emuladores (LocalStack, Azurite, fake-gcs-server), install completo en kind en CI, smoke en nube real bajo demanda.

## 4. Estructura del repo

```
pom.xml                    parent + BOM
libs/                      tenant-context, events, security, storage-port, kms-port, llm-port, observability
services/                  los servicios de la sección 1
contracts/openapi/         un contrato por servicio expuesto
contracts/events/          JSON Schema versionados + tests de contrato
deploy/                    helm/, platform/, argocd/, envs/{local,aws,gcp,azure,onprem}
infra/opentofu/            aws/, gcp/, azure/
tools/synthetic-oficios/   generador de oficios sintéticos
docs/                      producto.md, arquitectura.md, despliegue.md, seguridad/, adr/, specs/, runbooks/, referencia/
```

## 5. Documentación por especificaciones

Regla: sin spec aprobada no hay código; cada escenario de aceptación es un test.

- `docs/specs/plataforma/<tema>/spec.md` y `docs/specs/servicios/<servicio>/{spec,plan,tasks}.md`.
- `spec.md`: propósito, alcance y no-alcance, reglas, contrato (OpenAPI/eventos), modelo de datos, controles de seguridad aplicables (IDs de `docs/seguridad/matriz-controles.md`), escenarios Given/When/Then, métricas y SLO.
- `plan.md`: diseño Java (paquetes, clases, tablas, configuración). `tasks.md`: tareas atómicas con su test.

ADRs a producir en F1 (numeración desde 0001): Java/Spring Boot; microservicios por frontera de confianza; Kafka + outbox + claim-check; orquestación del pipeline; gateway delgado; silo por tenant; Postgres + pgvector; puertos de almacenamiento y WORM; llaves (envelope, OpenBao, KMS); identidad (Keycloak, federación, workload identity); extracción multimodal sin OCR; motor de fiabilidad y calibración; revisión humana por campo; gobierno de modelo; chat con pgvector; auditoría hash-chain + WORM; webhooks seguros; renderer sandbox + antivirus; Kubernetes con operadores; estrategia multi-cloud; observabilidad; cadena de suministro; rate limiting y cuotas; retención, legal hold, habeas data y offboarding; DR por tenant; break-glass y certificación de accesos.

## 6. Matriz de controles de seguridad (base para F1)

Aislamiento y acceso: silo por tenant; revalidar tenant contra `ROLE_ASSIGNMENT` por request (JWT solo prueba identidad); autorización por documento y clasificación; errores que no revelan existencia; prueba de fuga entre tenants en CI; sesiones de chat aisladas; revocación de sesión y tokens cortos; MFA roles internos; cuatro ojos en aprobación de documentos y en corrección de campos críticos de oficios; break-glass (justificación, TTL, MFA, aprobador distinto, visible al tenant); certificación periódica de accesos; separación admin de llaves vs contenido; rol de auditoría no revocable.

Cifrado y datos: TLS en todos los tramos y mTLS interno; KEK por tenant (datos y auditoría separadas); crypto-shredding bloqueado por legal hold; purga verificable de documento; clasificación en 4 niveles (oficios Confidencial por defecto); detección de PII (cédula, NIT, cuentas, tarjetas) antes de indexar; residencia de datos; retención mínima no reducible; habeas data.

Entrada no confiable: magic bytes, límites de tamaño/páginas, anti zip-bomb, rechazo de PDF con JavaScript/adjuntos; antivirus; XXE deshabilitado; anti-SSRF en webhooks (HTTPS, allowlist por tenant, sin IPs privadas/metadata, DNS pinning); HMAC con rotación y anti-replay; idempotencia de carga (evita embargo aplicado dos veces).

IA: grounding y citación; cifras exactas; prompt injection directa e indirecta registrada como evento de seguridad; revisión humana; versión de modelo/prompt fijada y registrada; gate de riesgo de modelo en CI con rollback; solo proveedores homologados sin entrenamiento con datos del banco.

Auditoría y operación: toda interacción registrada (incluso bloqueadas y desde caché); inmutabilidad frente a administradores (hash-chain + WORM compliance, escritor único); expediente firmado; logs de seguridad a WORM y SIEM; logs sin contenido; rate limit y anti-abuso; rotación y credenciales dinámicas; cadena de suministro (SAST, SCA, SBOM, cosign, Kyverno); separación de ambientes; notificación de incidentes; RTO/RPO medidos con simulacro; offboarding en dos fases con legal hold.

Normativa a mapear (validar con cumplimiento): Ley 1581/2012, Decreto 1377/2013, Ley 1266/2008, Circular Básica Jurídica SFC (CE 007/2018 ciberseguridad, CE 005/2019 nube).

## 7. Fases

| Fase | Rama | Entregable | Sale cuando |
|---|---|---|---|
| F0 | `chore/limpieza-repo` | Limpieza, este plan, README y CLAUDE.md nuevos | Repo limpio, tag `archive/pre-refactor-2026-09` |
| F1 | `docs/especificacion-base` | producto.md, arquitectura.md, despliegue.md, modelo de amenazas, matriz de controles, ADRs | Auditado |
| F2 | `docs/specs-servicios` | Specs de plataforma y servicios, OpenAPI, esquemas de eventos, YAML de tipologías, política de fiabilidad | Coherencia auditada |
| F3 | `feature/plataforma-base` | Esqueleto Maven, libs, CI, Helm, operadores, Argo CD, OpenTofu (una nube) | Install en verde en kind |
| F4 | `feature/oficio-e2e` | tenant, document, renderer, extraction, audit con oficio sintético E2E | E2E + prueba de fuga + auditoría verificada |
| F5 | `feature/fiabilidad-hitl` | Generador sintético, golden set, quality, review, notification | Precisión, calibración y STP medidos |
| F6 | `feature/chat-rag` | chat-service con citación y métricas | Fidelidad ≥ meta |
| F7 | `feature/hardening` | Break-glass, certificación, legal hold, DR, carga (Gatling), pentest (ZAP), caos | RTO/RPO medidos, sin hallazgos altos |
| F8 | `feature/multicloud` | Adaptadores GCS/Blob/KMS, values por nube, smoke 3 nubes | Smoke verde por destino |

Cada fase: rama desde `develop`, PR a `develop`, merge `--no-ff`. Ramas grandes pueden partirse en varias ramas/PR de la misma fase.

## 8. Ejecución con OpenCode (modelos Antigravity)

Orquestador: sesión de Claude Code (Sonnet preferente). Construye y audita OpenCode (`opencode run --model google/<modelo> --agent build|plan`). Opus real solo para desempate de auditorías de seguridad.

| Rol | Constructor (OpenCode `--agent build`) | Auditor | Respaldo |
|---|---|---|---|
| Arquitecto y specs (F1, F2) | `antigravity-gemini-3.1-pro` | Subagente Claude Sonnet (solo lectura) | `antigravity-gemini-3-flash` |
| Security (matriz, tenancy, llaves, auditoría, webhooks, F7) | `antigravity-gemini-3.1-pro` | Doble: subagente Claude Sonnet + `antigravity-gemini-3-flash` (`--agent plan`) | Opus solo si discrepan |
| DevOps (F3, F8) | `antigravity-gemini-3.1-pro` | Subagente Claude Sonnet + pase Stack | `antigravity-gemini-3-flash` |
| Backend (F4-F6) | `antigravity-gemini-3.1-pro` | Subagente Claude Sonnet + pase Stack | Claude Sonnet |
| Mecánico (fixtures, boilerplate de tests) | `antigravity-gemini-3-flash` | Auditor de la fase | — |

Nota 2026-10-04: los modelos Claude vía Antigravity (`antigravity-claude-sonnet-4-6`, `antigravity-claude-opus-4-6-thinking`) dejaron de estar disponibles; se reemplazan por Gemini como constructor y Claude Sonnet como auditor.

Ciclo por tarea: prompt autocontenido desde `tasks.md` → constructor en worktree → verificación local (build/tests) → auditor en sesión separada con veredicto `VEREDICTO: APROBADO|CAMBIOS` + hallazgos archivo:línea → hasta 3 vueltas reanudando la sesión del constructor → commit y PR por el orquestador. OpenCode nunca hace commit ni push. Commits sin co-autoría de IA. Logs en `.oc-logs/` (ignorado). `sessionID` de constructor y auditor en cada PR.
