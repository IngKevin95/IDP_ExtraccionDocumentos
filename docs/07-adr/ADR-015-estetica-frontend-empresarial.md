# ADR-015: Estética de frontend empresarial (sin genericidad)

**Estado**: Aceptado
**Fecha**: 2026-07-08

## Contexto

El frontend es la parte más visible del proyecto para un revisor de portafolio. El usuario pidió explícitamente evitar un look genérico: nada de emojis, nada de iconografía stock, estética que transmita "empresarial, innovador, serio y seguro".

## Decisión

- Tipografía corporativa (Inter/Geist/IBM Plex Sans).
- Paleta oscura/neutra (slate/navy/charcoal) + un único acento de marca.
- Iconografía: Lucide o Radix Icons, trazo fino, monocromático — nunca emoji ni ilustración genérica.
- Referencia de densidad/jerarquía: Linear, Stripe Dashboard, Vercel.
- Micro-interacciones (Framer Motion) sutiles (150-250ms), nunca llamativas.
- Componente distintivo: visualización animada del pipeline de documentos (React Flow) mostrando el estado en tiempo real por las capas EP-001 a EP-003.

## Consecuencias

- Cada componente nuevo del frontend se valida contra estas reglas antes de aceptarse — un ícono genérico o un color saturado se rechaza en revisión.
- Mayor cuidado de diseño por componente vs. usar una librería de UI "as-is" sin ajuste — aceptado porque el objetivo es impacto visual en portafolio, no velocidad de entrega.
