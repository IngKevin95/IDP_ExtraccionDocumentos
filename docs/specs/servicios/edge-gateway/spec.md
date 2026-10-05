# Especificación: edge-gateway

## Propósito
El servicio `edge-gateway` es el único punto de entrada público a la plataforma IDP Bancario. Actúa como terminador TLS, valida la firma y vigencia de los tokens JWT emitidos por el proveedor de identidad, aplica reglas de limitación de tasa (rate limiting) y tamaño de peticiones, y enruta el tráfico hacia los microservicios internos correspondientes.

## Alcance y no alcance
**En alcance:**
* Terminación de conexiones seguras (TLS).
* Validación de tokens de autenticación OIDC/JWT (solo firma y expiración, la autorización fina ocurre en cada servicio subyacente).
* Control de concurrencia y límites de tasa globales (Rate Limit) apoyado en Redis (con degradación en memoria).
* Restricciones de tamaño en las peticiones entrantes (prevención de zip-bombs e inyecciones masivas).
* Enrutamiento inverso hacia microservicios de dominio (ej. `document-service`, `chat-service`).
* Registro de peticiones y generación de logs de seguridad ante rechazos (401, 429).

**No alcance:**
* Autorización fina basada en roles o tenants locales (RBAC/ABAC detallado).
* Modificación o transformación profunda de los payloads.
* Publicación de eventos de negocio en Kafka (no tiene acceso al clúster de Kafka).
* Acceso a base de datos persistente.

## Requisitos cubiertos
* RNF-103 Cuotas y Rate Limit.
* RF-101 Estados canónicos (garantiza que peticiones malformadas o excesivas sean rechazadas tempranamente antes de iniciar el ciclo).

## Reglas
* **RN-GW-01:** Toda petición sin token JWT, con token expirado, o con firma inválida debe ser rechazada inmediatamente (HTTP 401) y auditada.
* **RN-GW-02:** Los límites de tasa se gestionan mediante Redis compartido de plataforma. Si Redis falla, el sistema debe degradar a un limitador en memoria por réplica (resiliencia) para no bloquear la operación total.
* **RN-GW-03:** Los logs de seguridad por rechazo no deben incluir información sensible del payload.

## Contrato
* **API Pública:** Expone rutas documentadas en `contracts/openapi/edge-gateway.yaml`. Las rutas son enrutadas hacia los microservicios.
* **Eventos:** No publica ni consume eventos en Kafka. Todo rechazo (401, 429) es exportado vía stdout a un colector de logs (FluentBit/Promtail) para SIEM/WORM.

## Modelo de datos
* **Base de datos:** Ninguna.
* **Caché/Estado (Redis):** Almacena únicamente contadores para rate limit (claves basadas en IP o `client_id` del JWT y ventana de tiempo). Sin datos de tenant o PII.

## Controles de seguridad
* **SEC-002 (Revalidación de tenant):** El gateway delega la revalidación fina a los servicios, pero garantiza que solo JWTs bien formados y firmados ingresen.
* **SEC-003 (Errores genéricos):** Mapeo de excepciones internas a respuestas genéricas para evitar fuga de información topológica.
* **SEC-011 (Separación de roles):** El gateway no posee llaves KEK ni credenciales de acceso a silos; solo opera con llaves públicas JWKS del IdP.
* **SEC-014 (Cifrado):** Terminación de TLS exterior e inicio de conexión (mTLS) hacia los servicios internos (Delegado parcial a la malla/cert-manager en Kubernetes).
* **SEC-023 (Entrada no confiable):** Límites estrictos configurados en tamaño de payload (ej. max `Content-Length`) antes de transferir a `document-service`.
* **SEC-030 (Rate limiting):** Restricciones de peticiones/segundo (cuotas globales) apoyado en Redis.
* **SEC-041 (Logs sanitizados):** Rechazos y auditorías en stdout sin revelar PII.

## Escenarios de aceptación

* **AC-01 (Sin JWT):**
  * **Given** una petición al `edge-gateway` sin encabezado Authorization,
  * **When** el cliente solicita cualquier ruta protegida,
  * **Then** retorna HTTP 401 y registra un evento de acceso denegado en logs.
* **AC-02 (JWT Inválido o expirado):**
  * **Given** una petición con un JWT expirado o con firma incorrecta,
  * **When** se solicita un recurso,
  * **Then** retorna HTTP 401 y registra el intento fallido.
* **AC-03 (Petición válida):**
  * **Given** una petición con un JWT válido,
  * **When** se solicita un recurso que coincide con las reglas de enrutamiento,
  * **Then** la petición es reenviada transparentemente al microservicio destino junto con los headers originales.
* **AC-04 (Límite de tasa excedido):**
  * **Given** que un cliente supera la cantidad máxima de peticiones por minuto configurada en Redis,
  * **When** realiza una nueva solicitud,
  * **Then** retorna HTTP 429 (Too Many Requests) y el evento queda registrado en los logs de seguridad.
* **AC-05 (Degradación de Rate Limit):**
  * **Given** que el clúster Redis de plataforma no está disponible,
  * **When** un cliente realiza peticiones masivas,
  * **Then** el gateway utiliza el limitador en memoria local para rechazar excesos, manteniendo la disponibilidad y previniendo caídas (circuit breaker).
* **AC-06 (Prevención de carga masiva):**
  * **Given** un JWT válido,
  * **When** se intenta subir un payload que supera el tamaño máximo configurado en la ruta de documentos,
  * **Then** el gateway interrumpe la conexión y retorna HTTP 413 (Payload Too Large).
* **AC-07 (Errores del backend):**
  * **Given** un microservicio que responde con errores técnicos,
  * **When** el gateway recibe la respuesta,
  * **Then** se asegura de no exponer stacktraces hacia el exterior (filtro de errores genéricos en caso de caídas de servicios internos, HTTP 502/503).
* **AC-08 (CORS y Cabeceras de Seguridad):**
  * **Given** cualquier petición OPTIONS o GET/POST regular,
  * **When** es procesada por el gateway,
  * **Then** se anexan cabeceras de seguridad estrictas (HSTS, X-Content-Type-Options, etc.).

## Métricas y SLO
* **Disponibilidad:** 99.99%.
* **Latencia p95:** < 15ms de recargo sobre la llamada original (excluyendo tiempo de payload y procesamiento interno).
* **Métricas clave:** `gateway.requests.total`, `gateway.rate_limit.rejected`, `gateway.jwt_validation.failed`.

## Dependencias
* **Redis:** (Opcional en tiempo de ejecución para alta disponibilidad de contadores; no bloqueante).
* **Proveedor de Identidad (Keycloak):** Para descargar llaves JWKS periódicamente y verificar firmas.
