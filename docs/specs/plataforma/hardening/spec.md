# Fase F7: Hardening y Resiliencia (Especificación)

## Contexto
El sistema cuenta con el core de extracción, RAG, y validaciones HITL. La fase F7 consolida la postura de seguridad (hardening), auditoría continua y prepara la infraestructura para desastres (DR) y alta carga antes de pasar al despliegue multicloud (F8).

## Requisitos y Escenarios

### 1. Control de Acceso y Break-glass (SEC-012, SEC-013)
- **Given** un incidente crítico de plataforma, **When** el administrador de seguridad usa el rol `BREAK_GLASS`, **Then** obtiene acceso ininterrumpido sin MFA temporal, pero **And** se dispara una alerta de severidad crítica inmutable y se graba toda interacción.
- **Given** los usuarios del tenant, **When** termina el ciclo de certificación trimestral, **Then** los accesos no recertificados se revocan automáticamente.

### 2. Legal Hold Atómico y Bajas (Migración V2)
- **Given** una orden de preservación (legal hold), **When** se aplica sobre un tenant, **Then** cualquier intento de borrado o shredding de KEK queda bloqueado atómicamente a nivel base de datos y aplicación.
- **Given** el modelo de eventos `processed_event`, **When** se actualiza, **Then** se emplea la versión V2 segregada y no modificable en lugar de sobreescribir la V1.

### 3. Segregación de Tópicos
- **Given** los eventos del sistema, **When** un servicio emite un mensaje, **Then** se publica en un tópico específico de su productor (ej. `review.events`, `extraction.events`) en lugar de un tópico general, para asegurar menor superficie de ataque y control estricto de ACLs en Kafka.

### 4. Oráculo Residual de Deduplicación
- **Given** un registro de usuario, **When** se verifica la duplicidad, **Then** se emplea un índice único por hash ciego del PII, en lugar de consultar tablas que permitan enumeración (mitigación de oráculo).

### 5. Pruebas y Tolerancia
- **ZAP Pentest:** Integración de escaneo dinámico DAST en pipeline.
- **Gatling:** Medición de RTO/RPO bajo carga de 100 oficios/segundo.
- **Caos:** Inyección de fallos en DB y Kafka verificando la recuperación del outbox transaccional.