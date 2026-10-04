# 0025. DR por tenant

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
Los oficios judiciales imponen límites estrictos de tiempo para su aplicación. Ante una caída catastrófica regional de la nube (RTO), el banco se expone a sanciones y riesgos legales. Sin embargo, replicar la arquitectura de manera Activo-Activo sincrónica eleva exponencialmente la complejidad y el costo.

## Decisión
Implementamos un diseño asimétrico de recuperación de desastres (DR) pasivo, aislado a nivel de inquilino (tenant).
1. Replicación Pasiva por Silo (Cold Standby) y RPO: Los blobs en S3 se replican a una región geográfica externa. El RPO se garantiza mediante el archivado continuo de WAL con Barman Cloud desde CloudNativePG (CNPG) hacia el almacenamiento objeto replicado.
2. Re-aprovisionamiento mediante IaC y Gestión de Llaves: Ante siniestros irreparables, se despliegan entornos vacíos orquestados mediante GitOps. Está terminantemente prohibido exportar la KEK; la resiliencia de llaves se maneja exclusivamente a través de llaves multi-región del KMS subyacente o mediante replicación nativa de DR de OpenBao.
3. RTO / RPO Paramétricos: Simulacros obligatorios validarán los tiempos objetivos pactados durante la fase F7 de integración de la plataforma, priorizando la restauración íntegra de inquilinos críticos.
4. Idempotencia y Segregación de Eventos: Al restaurar colas caídas, el sistema aplicará rigurosamente idempotencia (SEC-029) previniendo una doble extracción perjudicial si los mensajes reanudados ya se encuentran confirmados en los destinos de bases de datos.

## Alternativas consideradas
- Diseño Activo-Activo Multi-región Global: Duplica la infraestructura y genera cuellos de botella en la replicación síncrona. Los tiempos normativos de resolución admiten latencias de recuperación asimétricas que permiten ahorrar masivamente en OPEX evitando este escenario.
- Recuperación de backups monolíticos gigantezcos: Bloquea las políticas operativas al afectar simultáneamente a inquilinos ilesos. Se opta por el DR granular a nivel silo por tenant.
- Ausencia de garantías core de DR: No es comercialmente viable en transacciones de la magnitud del embargo judicial.

## Consecuencias
- Positivas: Balance óptimo entre la contención presupuestal en infraestructura de clústeres y el cumplimiento diligente de los SLAs críticos frente a auditorías (ISO 27001/SOC 2 Type II). 
- Negativas o costos: Exige ejecutar ejercicios de simulación de caos e impone la creación y validación continua de manuales procedimentales técnicos.

## Controles relacionados
SEC-001, SEC-029