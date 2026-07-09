# CLAUDE.md — docs/05-priorizacion/

Reglas locales para sesiones de priorización. Detalle completo en `METODOLOGIA.md` §7.

## Fuera de alcance aquí

El backlog en sí vive en `docs/03-backlog/backlog.md`. Esta carpeta solo guarda las sesiones que justifican su orden.

## Nomenclatura

Una sesión por archivo: `<framework>-YYYY-MM-DD.md`. Frameworks soportados: MoSCoW, RICE, Valor-Esfuerzo, Eisenhower.

## Reglas locales clave

1. El framework se aplica completo — toda historia del backlog queda clasificada, ninguna se omite.
2. Distribución sana esperada:
   - MoSCoW: "Must" no debe superar el 60% del esfuerzo total; si lo supera, se advierte y se reabre la sesión.
   - RICE: el top 3 debe tener Score ≥ 2× el promedio general, o el ranking no aporta señal útil.
3. Toda disidencia del equipo se registra en una sección dedicada del archivo, no se descarta.
4. `backlog.md` solo se actualiza con aprobación explícita del usuario — nunca de forma automática tras una sesión.

## Automatización disponible

- Skill `factory-priorizar-backlog` (opcional, se usa después del pipeline base).
- Agente revisor: `priorizacion-auditor`.
