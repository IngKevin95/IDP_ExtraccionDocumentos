# 0002. Topología de microservicios por frontera de confianza

Estado: Aceptada
Fecha: 2026-09-29

## Contexto

El IDP bancario procesa oficios de embargo, los cuales contienen PII y datos financieros críticos. El procesamiento incluye la validación de archivos crudos subidos por el usuario, extracción de imágenes (rasterizado), invocación a LLMs y el almacenamiento inmutable en un expediente de auditoría. Si el diseño adoptara una arquitectura monolítica clásica o microservicios divididos por "entidad de negocio" o por "capa técnica" (ej. capa de controladores vs capa de base de datos), un compromiso en el componente que procesa archivos PDF crudos comprometería inmediatamente las credenciales de base de datos, el bucket de auditoría WORM y las llaves KMS del tenant. Es imperativo contener el radio de explosión (blast radius) asumiendo que componentes que interactúan con entradas no confiables eventualmente serán vulnerados.

## Decisión

Se adopta una **arquitectura de microservicios segregados exclusivamente por fronteras de confianza y privilegio de acceso**, no por dominio de negocio tradicional.

Los servicios se estructuran estrictamente según su privilegio distintivo y exposición:
1. `edge-gateway`: Único servicio expuesto a internet. No posee lógica de negocio ni credenciales de base de datos.
2. `document-service`: Único autorizado para escribir en el bucket transaccional del tenant.
3. `renderer`: Sandbox volátil. Llamada HTTP síncrona desde `document-service` (mTLS). Devuelve PNG por página y la capa de texto nativa del PDF si existe. Valida archivos y rasteriza usando PDFBox y ClamAV (clamd como sidecar, firmas vía CronJob `freshclam`). **No usa Kafka, no posee credenciales de ningún tipo, su NetworkPolicy permite ingress solo desde `document-service` y deniega egress.**
4. `extraction-service` y `chat-service`: Únicos con credenciales para consumir la API del proveedor de LLM.
5. `audit-service`: Único con permisos de escritura (append-only) en el bucket WORM de auditoría y acceso a la KEK de auditoría.
6. `tenant-service`: Aislado en la red de administración, es el único que interactúa con la gestión de llaves maestras y aprovisionamiento de infraestructura lógica (silos).

## Alternativas consideradas

- **Monolito modular:** Desplegar todos los componentes en una sola aplicación Java (JVM) separada por paquetes lógicos. *Por qué se descarta:* Aunque simplifica el despliegue y evita la latencia de red, cualquier vulnerabilidad (ej. un CVE en la librería de procesamiento PDF) otorga ejecución remota de código (RCE) con acceso directo a la memoria y credenciales de todos los subsistemas (incluyendo la conexión a la KMS de auditoría).
- **Microservicios tradicionales por dominio (Bounded Contexts de DDD purista):** Separar por "Embargos", "Clientes", "Pagos". *Por qué se descarta:* En un IDP E2E, el flujo del documento cruza todos los dominios. Separarlos así obliga a que el servicio de "Embargos" maneje desde el PDF crudo hasta el llamado al LLM y la escritura en base de datos, violando el principio de menor privilegio.

## Consecuencias

### Positivas
- **Contención de amenazas (Blast Radius Mínimo):** Si un atacante logra un RCE en el `renderer` mediante un PDF malicioso (zip-bomb o exploit de rasterización), se encontrará en un contenedor sin tokens de servicio en red, sin salida a internet y sin credenciales de nube.
- **Escalabilidad asimétrica:** El rasterizado de PDFs (`renderer`) consume intensivamente CPU y memoria, mientras que el orquestador (`document-service`) está atado a I/O. Esta topología permite escalar el cómputo del renderer de forma independiente.
- **Auditoría inquebrantable:** Al aislar el `audit-service`, un compromiso total en el `extraction-service` no permite a un atacante reescribir ni alterar el historial de auditoría previamente firmado, ya que el servicio vulnerado carece de los roles IAM para modificar el bucket WORM.

### Negativas o Costos
- **Sobrecarga operativa:** Requiere administrar despliegues separados, políticas de red (NetworkPolicies) estrictas en Kubernetes para forzar los caminos de red permitidos (ej. solo `document-service` puede llamar al `renderer`) y observabilidad distribuida.
- **Latencia de IPC (Inter-Process Communication):** El flujo de un documento requerirá múltiples saltos de red internos y serialización/deserialización, lo que aumenta la latencia base (aunque mitigado por el procesamiento asíncrono basado en eventos).

## Controles relacionados

- **SEC-011:** Separación de roles: administradores de llaves KMS no acceden al contenido.
- **SEC-025:** Análisis en sandbox sin salida a internet ni credenciales (`renderer`).
- **SEC-045:** Aplicación de políticas de admisión con Kyverno (aislamiento estricto por pod).
