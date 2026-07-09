# Prototipo UI/UX - cierre de brechas operativas

Estado: actualizado el 2026-07-08.

Este documento registra que se agrego al prototipo para acercarlo a una consola operativa de producto, manteniendo visibles los codigos internos HU/ADR/EP porque ayudan a ubicar cada accion dentro del requerimiento.

## Codigo agregado

| Archivo | Proposito |
|---|---|
| `prototipo/app.js` | Capa de comportamiento del prototipo: datos simulados, busqueda, filtros, roles, permisos, upload, modales, auditoria, webhooks y KPIs. |
| `prototipo/styles.css` | Estilos nuevos para responsive, filtros, visor documental, estados vacios, upload, toasts y controles de sesion. |
| `prototipo/*.html` | Cada pantalla carga `app.js` al final del body. |

## Brechas solventadas en el prototipo

| Brecha detectada | Solucion de prototipo | Codigo |
|---|---|---|
| Falta de comportamiento real en busqueda | Consulta filtra documentos por texto, estado, tipologia y confianza minima. | `enhanceConsulta()` en `prototipo/app.js` |
| Falta de exportacion operativa | Boton de exportar CSV simulado con feedback. | `enhanceConsulta()` |
| Upload solo visual | Dropzone acepta click, drag and drop, valida extension, valida limite de 20MB y muestra progreso simulado. | `enhanceIngesta()`, `handleFile()`, `simulateUpload()` |
| Falta de estados de feedback | Toasts, modales runtime y estados vacios. | `showToast()`, `showModal()`, `.empty-state`, `.toast` |
| Falta de responsive | Sidebar se convierte en navegacion horizontal, grids bajan a 1 o 2 columnas, tablas hacen scroll horizontal. | media queries en `prototipo/styles.css` |
| Tabs puramente decorativas | Las tabs actualizan estado activo al hacer click. | `enhanceTabs()` |
| Roles no aplicados visualmente | Selector de rol y tenant en la topbar; acciones se bloquean segun rol simulado. | `enhanceShell()`, `applyPermissions()` |
| Falta visor del documento fuente | Detalle de documento agrega visor mock con texto fuente y resaltados de evidencia. | `enhanceDocumento()` |
| Falta revision humana | Detalle de documento agrega panel de revision, auditoria, aprobar y reprocesar. | `enhanceDocumento()` |
| Tipologias sin validacion interactiva | Editor YAML valida presencia minima de `tipologia`, `campos` y `tipo`. | `enhanceTipologias()` |
| Webhooks sin operacion | Acciones para probar endpoint, rotar secreto y reintentar entregas fallidas. | `enhanceWebhooks()` |
| KPIs sin filtros | Filtros simulados por ventana, tenant y tipologia. | `enhanceKpis()` |
| Sesion/autenticacion no representada | Boton de sesion expirada con modal explicativo. | `enhanceShell()` |

## Lo que aun falta para producto real

Estas capacidades ya estan representadas en la UI, pero requieren backend, persistencia y seguridad reales para salir a producto.

| Pendiente productivo | Contrato/codigo esperado |
|---|---|
| Autenticacion OIDC/SSO | Middleware que valide JWT, tenant y scopes por request. |
| Multi-tenant fuerte | Filtro obligatorio por `tenant_id` en cada query, storage bucket por tenant o prefijo aislado, pruebas anti-fuga. |
| Ingesta real | Endpoint `POST /documentos` con multipart upload, validacion de archivo, persistencia y publicacion a cola. |
| Consulta real | Endpoint `GET /documentos` con filtros, paginacion, ordenamiento y autorizacion. |
| Detalle documental real | Endpoint `GET /documentos/{id}` y URL firmada para visor PDF/imagen. |
| Reprocesamiento | Endpoint `POST /documentos/{id}/reprocesos` con version de tipologia/modelo y trazabilidad. |
| Correccion manual | Endpoint `PATCH /documentos/{id}/campos` con diff, usuario, razon y version. |
| Auditoria | Tabla/event stream append-only para acciones, cambios, webhooks y errores. |
| Tipologias productivas | CRUD versionado, validacion schema completa, preview, publish, rollback y bloqueo por permisos. |
| Webhooks productivos | Firma HMAC, secreto versionado, historial de entregas, DLQ y reintento manual. |
| KPIs reales | Agregaciones desde eventos de procesamiento: latencia, costo, precision y error rate. |

## Contratos sugeridos

### POST /documentos

```http
POST /documentos
Authorization: Bearer <jwt>
Content-Type: multipart/form-data

file=<binary>
tenant_id=banco_aurora
tipologia_esperada=embargo_judicial
```

Respuesta:

```json
{
  "id": "embargo_2026-4473",
  "estado": "en_cola",
  "tenant_id": "banco_aurora",
  "config_version": "embargo_judicial:v4"
}
```

### GET /documentos

```http
GET /documentos?estado=completo&tipologia=embargo_judicial&confidence_min=0.85&page=1&page_size=25
Authorization: Bearer <jwt>
```

Respuesta:

```json
{
  "items": [
    {
      "id": "embargo_2026-4471",
      "archivo": "embargo_2026-4471.pdf",
      "tenant_id": "banco_aurora",
      "tipologia": "embargo_judicial",
      "confianza": 0.98,
      "estado": "completo",
      "latencia_ms": 3100
    }
  ],
  "page": 1,
  "page_size": 25,
  "total": 1284
}
```

### PATCH /documentos/{id}/campos

```json
{
  "reason": "Correccion manual por revision operativa",
  "fields": {
    "monto_embargado": "$ 42.500.000 COP",
    "fecha_radicado": null
  }
}
```

### POST /documentos/{id}/reprocesos

```json
{
  "typology_version": "embargo_judicial:v4",
  "model_profile": "gemini-2.5-flash-lite",
  "reason": "Timeout LLM en intento anterior"
}
```

### Webhook documento.completado

```json
{
  "event_id": "evt_01J...",
  "event_type": "documento.completado",
  "document_id": "embargo_2026-4471",
  "tenant_id": "banco_aurora",
  "status": "completo",
  "result_url": "https://idp.example/api/documentos/embargo_2026-4471",
  "occurred_at": "2026-07-08T19:41:00Z"
}
```

Headers recomendados:

```http
X-IDP-Event-Id: evt_01J...
X-IDP-Signature-Version: v1
X-IDP-Signature: hmac-sha256=<hex>
```

## Criterio para decir "producto listo"

- La UI consume APIs reales y no datos simulados.
- Cada accion critica queda auditada.
- Cada request aplica tenant y permisos.
- Los documentos fuente se ven desde URL firmada o stream autorizado.
- Webhooks tienen firma, retry, DLQ e historial visible.
- KPIs se calculan desde eventos reales, no desde valores estaticos.
- Hay pruebas de permisos, aislamiento multi-tenant, upload invalido, reproceso y error de webhook.
