# Capa 4 — Orquestación + Admin

> Abstracción funcional de referencia (DocFly). Insumo para el PRD to-be de "IDP - Extracción de Documentos".
> **Nota**: esta capa no está cubierta por `ARCHITECTURE_ANALYSIS.md`/`WORKSPACE_MAP.md` del repo DocFly (esos documentos solo describen los 8 servicios "core"). Investigada directamente vía README de cada repo.

## Repos de referencia

| Repo | Tipo | Rol |
|---|---|---|
| `dav-emb-v2-data-pipelines-airflow` | Airflow (webserver 8080) | Orquestación de DAGs por cliente (Banco Aurora) |
| `docfly-paginas-backend` | Cloud Run, FastAPI (8088) | Backend del panel de administración |
| `docfly-paginas-fronted` | React SPA (5173) | Frontend del panel de administración |

## Propósito funcional

Capa transversal de orquestación batch y administración: coordina flujos de trabajo programados/específicos por cliente (Airflow) y expone un panel de administración multi-tenant para monitoreo de consumo, usuarios, roles y reportes.

## 4a. Orquestación (`dav-emb-v2-data-pipelines-airflow`)

- README describe el proyecto como "Embargos y Desembargos MVP2" para Banco Aurora — nombre interno distinto al del directorio; documentación posiblemente desactualizada respecto al código actual (el directorio contiene `openspec/`, `dags/`, `BD/`, indicios de evolución posterior al README).
- DAG principal `file_processor_dag.py`: extracción de documentos → tipificación → gestión de relaciones jerárquicas entre documentos → notificaciones por email.
- DAG secundario `process_excel_dag_v2.py`: procesamiento específico de Excel.
- Incluye API Flask propia (`routes/`) para CRUD de documentos, además de los DAGs.
- Base de datos propia PostgreSQL (`resources/db/Estructura.sql`), separada de Firestore usado en Capas 1-3 — **mezcla de motores de persistencia** (PostgreSQL aquí vs. Firestore en el resto del sistema).
- Stack: Python 3.8+, Apache Airflow 2.x, PostgreSQL 12+, Chrome/Chromedriver (scraping/renderizado), LibreOffice (conversión de documentos).

## 4b. Panel de administración (`docfly-paginas-backend` + `docfly-paginas-fronted`)

- **Backend**: FastAPI, Python 3.12. Lee de **múltiples proyectos GCP Firestore simultáneamente** (1 panel + N clientes) — patrón distinto a la multi-tenancy por colección `subscriptions` usada en Capas 1-3; aquí la separación es a nivel de proyecto GCP completo, no de documento.
- **Cache**: Redis 7, invalidado en tiempo real vía `FirestoreListenerService` (listener de cambios Firestore).
- **Auth**: Firebase Auth (JWT), permisos granulares (10 módulos × 24 acciones, roles superadmin/admin/viewer + custom).
- **Frontend**: React 18 + TypeScript + Vite + Ant Design 5, `@tanstack/react-query` v5 para estado de servidor.
- Funcionalidad: dashboard de consumo global, gestión de clientes, visualización de `billing_ledger`, transacciones, suscripciones, reportes (CSV/XLSX), gestión de usuarios/roles, notificaciones, auditoría, configuración de alertas.
- Endpoints clave: `/clients`, `/clients/{id}/consumption`, `/clients/{id}/transactions`, `/consumption/global-summary`, `/admin/audit-log`, `/admin/alert-config`.

## Interfaces expuestas

- Airflow: UI web (8080) + API Flask propia para CRUD de documentos.
- `docfly-paginas-backend`: API REST FastAPI (8088), documentada en `API_CONTRACT.md` del frontend.
- `docfly-paginas-fronted`: SPA consumidora del backend.

## Multi-tenancy

Dos mecanismos distintos coexistiendo en la misma capa:
- Airflow: por cliente vía DAGs/configuración dedicados (un pipeline "hecho a la medida" de Banco Aurora, no genérico).
- Panel admin: por **proyecto GCP Firestore separado por cliente**, panel central federa consultas.

## Notas de arquitectura (candidatas a ADR)

- **Mezcla de motores de persistencia** (PostgreSQL en Airflow vs. Firestore en el resto): candidato a ADR — ¿el to-be usa un solo almacén o acepta poliglota por capa?
- **Orquestación por-cliente vs. genérica**: el Airflow de referencia está fuertemente acoplado a Banco Aurora (nombres de DAG, lógica de negocio específica). Candidato a ADR: ¿el to-be diseña orquestación genérica multi-tenant desde el inicio, o reproduce el patrón "un pipeline por cliente"?
- **Multi-tenancy a nivel de proyecto GCP** (panel admin) vs. **multi-tenancy a nivel de documento/colección** (Capas 1-3): dos estrategias distintas para el mismo problema dentro del mismo sistema de referencia. Candidato a ADR de fondo: elegir una estrategia única de multi-tenancy para el to-be.
- Uso de Chrome/Chromedriver + LibreOffice como dependencias de sistema en Airflow (no solo librerías Python) — implica infraestructura más pesada que el resto de servicios (Cloud Functions ligeras).

## Notas de seguridad

- `resources/variables/variables.json` (Airflow) y credenciales Firebase del frontend (`VITE_FIREBASE_API_KEY`, etc.) son configuración sensible por entorno — no reproducidas aquí, solo su ubicación. El to-be debe definir gestión de secretos unificada (mismo candidato a ADR que en Capa 1).
- Panel admin maneja datos de facturación (`billing_ledger`) y auditoría de clientes reales — alta sensibilidad, requiere control de acceso robusto (ya presente vía permisos granulares) y debe mantenerse como requisito no negociable en el to-be.
