# 04-historias/ — qué vive aquí

Una historia de usuario por archivo, nombrada `HU-001-<slug>.md`, `HU-002-<slug>.md`, etc.

- **Generación**: `/factory:historia` produce el formato "Como/Quiero/Para"; `/factory:ac` añade los criterios de aceptación en Given/When/Then.
- **Frontmatter obligatorio**: `id, titulo, epica, prioridad, complejidad, estado`.
- **AC**: 3 a 5 escenarios G/W/T cubriendo camino feliz, error y borde.
- **Gate de salida**: no se marca `estado: lista` sin pasar `/factory:invest` primero.

Metodología completa: `METODOLOGIA.md` §3 (historia) y §4 (AC).
