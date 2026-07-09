# ADR-003: Diseño cloud-agnostic (Kubernetes + storage S3-compatible)

**Estado**: Aceptado
**Fecha**: 2026-07-07

## Contexto

Instrucción explícita del usuario: la plataforma "no debe estar amarrada a GCP". El proyecto es de portafolio y debe demostrar arquitectura escalable, segura y robusta — la portabilidad de infraestructura es parte de esa demostración, no solo una preferencia técnica.

## Decisión

Despliegue sobre Kubernetes (vs. servicios administrados propietarios como Cloud Run/Cloud Functions) y almacenamiento de objetos vía API S3-compatible (vs. Cloud Storage nativo). GCP queda como **un** despliegue posible entre varios (GCP, AWS, on-prem, cualquier proveedor con K8s + S3-compatible), nunca como dependencia de diseño.

## Consecuencias

- Cada capa lógica (ADR-001) se empaqueta como contenedor desplegable en cualquier clúster K8s — sin funciones serverless propietarias en la ruta crítica.
- El acceso a objetos (documentos originales, resultados) pasa por una interfaz S3-compatible, nunca por el SDK nativo de un proveedor.
- Costo: se renuncia a algunas ventajas de autoescalado "cero a N" de las funciones serverless propietarias — aceptado como trade-off de portabilidad.
- Vertex AI/Gemini (o cualquier proveedor LLM) se consume como servicio externo vía API, no como integración nativa de plataforma — mismo principio aplicado a la capa de IA.
