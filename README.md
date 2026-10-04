# IDP bancario — Extracción de oficios y chat documental

Backend para entidades financieras que:

1. **Extrae** datos estructurados de oficios de embargo y desembargo (tipologías EC, EJ, DC, DJ), incluidos documentos escaneados con tablas, usando LLM multimodal con validación determinística, score calibrado y revisión humana solo de los campos dudosos.
2. **Responde preguntas** sobre cualquier documento cargado, con citación verificable a página y fragmento.

Multi-tenant con silo por cliente (base de datos, buckets, llaves), arquitectura por eventos (Kafka), 100% Java/Spring Boot, desplegable en Kubernetes sobre AWS, GCP, Azure o self-hosted.

## Estado

Fase de especificación. Ver [`docs/plan-maestro.md`](docs/plan-maestro.md) para decisiones, arquitectura, fases y estado.

## Documentación

| Ruta | Contenido |
|---|---|
| `docs/plan-maestro.md` | Rumbo, decisiones consolidadas y fases |
| `docs/producto.md` | Alcance funcional, actores, reglas de negocio |
| `docs/arquitectura.md` | Servicios, eventos, datos, flujos |
| `docs/despliegue.md` | Estrategia multi-cloud y self-hosted |
| `docs/seguridad/` | Matriz de controles y modelo de amenazas |
| `docs/adr/` | Decisiones de arquitectura |
| `docs/specs/` | Especificaciones por plataforma y servicio |
| `docs/referencia/` | Abstracción funcional del sistema de referencia DocFly |

## Cómo contribuir

Construcción por especificaciones y Gitflow estricto: ver [`CLAUDE.md`](CLAUDE.md).
