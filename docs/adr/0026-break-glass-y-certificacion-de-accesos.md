# 0026. Break-glass y certificación de accesos

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
En sistemas transaccionales críticos, el soporte técnico a veces necesita evadir las restricciones de solo-lectura para intervenir emergencias. Otorgar permisos administrativos persistentes genera acumulación de privilegios ("privilege creep"). Además, es obligatoria la revisión periódica de accesos bajo las circulares bancarias.

## Decisión
Se establece un protocolo institucional temporal ("Break-Glass") y un proceso de revisión iterativa de identidades:
1. Privilegios Efímeros y Roles Segregados: Se usan credenciales dinámicas de expiración rápida emitidas por el IdP/OpenBao. Existe una separación inquebrantable entre el rol que administra llaves maestras y el acceso al contenido documental. El rol de auditoría es estático y no revocable bajo operaciones normales.
2. Aprobación Cuatro Ojos Estricta: La activación del break-glass exige ineludiblemente la aprobación de un usuario de guardia distinto del solicitante. 
3. Trazabilidad Transparente: Durante la sesión, toda actividad reporta eventos clave de plataforma obligatorios: `breakglass.otorgado` y `breakglass.expirado`. La auditoría queda inmutable en la cadena WORM.
4. Certificación de Accesos (SEC-012): Trimestralmente, el `tenant-service` generará automáticamente un reporte exportable con evidencia firmada de las membresías actuales, exigiendo la revalidación proactiva y explícita por parte de los supervisores o ejecutando revocaciones automáticas.

## Alternativas consideradas
- Roles DBAs estáticos en personal confiable: Vulnerable frente al robo de credenciales y compromisos directos prolongados.
- Scripts y ejecución local a ciegas: Ralentiza los tiempos de recuperación al necesitar PRs urgentes en medio de caídas de servicio.
- Auditorías anuales pasivas de Active Directory: Insuficientes frente a normativas que exigen limpieza y reportes regulares formalmente atestados (SEC-012).

## Consecuencias
- Positivas: Cumplimiento riguroso de la Superfinanciera, erradicando el privilege creep y agilizando las respuestas críticas auditables.
- Negativas o costos: Complejidad técnica en los IdPs. Requiere la articulación de personal en esquemas de guardia cruzada obligatoria (cuatro ojos operativos).

## Controles relacionados
SEC-010, SEC-012, SEC-013