# 06-flows/ — qué vive aquí

Un archivo por épica: `EP-001-<slug>.md`, `EP-002-<slug>.md`, etc. Requiere PRD + épicas + historias + Story Map ya existentes.

- **Generación**: `/factory:flows` invoca la skill `factory-mapear-flujos-navegacion`, que produce un diagrama Mermaid (`sequenceDiagram` o `flowchart TD`) por épica, con trazabilidad arco↔HU↔AC.
- **Frontmatter obligatorio**: `id, epica, historias_cubiertas`.
- **Anotación de arcos**: cada transición del diagrama lleva un comentario `%% HU-XXX` justo antes.
- **Cobertura mínima**: camino feliz + al menos un ramal de error + al menos un edge case.
- **Regla de consistencia**: toda HU con `epica: EP-XXX` en su frontmatter debe aparecer referenciada en el flow de esa épica.
- **Actores**: restringidos a los declarados en el PRD §Stakeholders.
- **Formato**: el Mermaid debe parsear correctamente (solo `sequenceDiagram` o `flowchart TD`).

Auditoría automática vía `/factory:revisar` (agente `flows-auditor`, verifica integridad estructural y trazabilidad en ambos sentidos). Metodología completa: `METODOLOGIA.md` §5a.
