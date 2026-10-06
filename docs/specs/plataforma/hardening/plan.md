# Plan de Implementación F7: Hardening

## Módulos Afectados
- `libs/security`: Implementación del rol Break-glass y auditoría inmutable.
- `libs/events`: Migración de `processed_event` V1 a V2 y segregación de tópicos en `EventTopology`.
- `services/tenant-service`: Legal hold atómico, índice hash ciego para mitigación de oráculo de usuarios, y recertificación de accesos.
- `deploy/gatling`: Configuración de scripts de carga.
- `.github/workflows`: Añadir escaneo ZAP y pruebas de caos en testcontainers.

## Decisiones Arquitectónicas (ADRs a redactar)
- ADR-0030: Patrón Break-glass y certificación de accesos.
- ADR-0031: Estrategia de particionamiento y ACLs por productor en Kafka (Tópicos segregados).

## Fases del Plan
1. **Contratos e Infra:** ADRs, topología V2 y ACLs en Kafka.
2. **Backend (tenant y security):** Oráculo, Legal Hold atómico y Break-glass.
3. **Calidad y Seguridad Activa:** Scripts Gatling y ZAP.