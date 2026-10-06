# ADR 0003: Mensajería asíncrona con Kafka, Outbox transaccional y Claim-Check

## Estado
Aprobado

## Contexto
El IDP extrae información de oficios mediante procesos que involucran latencias variables, como el renderizado de documentos y la inferencia con LLMs. Esto requiere una arquitectura guiada por eventos que garantice el procesamiento asíncrono, la entrega garantizada y el desacople entre componentes. Simultáneamente, por tratarse de información bancaria (Altamente Confidencial o Confidencial por defecto), es imperativo evitar la fuga de datos personales (PII) hacia la infraestructura de mensajería, la cual suele tener retenciones largas y controles de acceso menos granulares a nivel de campo. Adicionalmente, el diseño multi-tenant exige un modelo de despliegue que escale económicamente sin comprometer el aislamiento lógico.

## Decisión
Se adopta **Apache Kafka en modo KRaft (sin Zookeeper)** como bus de eventos central. Para asegurar la consistencia entre la base de datos y los mensajes publicados, se implementa el **Patrón Outbox Transaccional**, y para proteger la privacidad de los datos, se emplea el **Patrón Claim-Check**.

1.  **Infraestructura compartida de Kafka:** No se crearán tópicos ni credenciales por tenant. El pipeline utilizará tópicos compartidos por tipo de evento, estructurados con un número fijo de particiones y política de retención explícita. La clave de particionamiento estándar será `documentId` para asegurar ordenamiento por documento. El control de acceso a los tópicos se gestionará mediante ACLs declarativas (`KafkaUser` de Strimzi) por servicio.
2.  **Outbox distribuido por tenant:** Dado que cada tenant posee su propia base de datos, el patrón Outbox se implementa mediante una tabla `outbox_event` alojada en la base de datos de cada tenant. Un proceso relay (scheduler) en cada servicio interviene estas tablas. Para evitar colisiones en implementaciones de alta disponibilidad, el relay recorre las bases de datos de los tenants repartidas mediante un hash entre las réplicas del servicio utilizando ShedLock, y procesa los registros con `FOR UPDATE SKIP LOCKED`.
3.  **Claim-Check obligatorio (Cero PII en Kafka):** Los eventos publicados en Kafka no contendrán datos personales (PII). El payload del evento transmitirá únicamente identificadores (ej. `tenantId`, `documentId`), su estado y metadatos operativos (SEC-050). Para obtener los datos de negocio requeridos, el consumidor consultará la API del publicador (o base de datos en contexto seguro), validando nuevamente la autorización.
4.  **Auditoría y reintentos (Bloqueante):** Los eventos de auditoría se publicarán en un tópico específico `audit.events` utilizando `tenantId` como clave. El consumidor de este tópico aplicará una política de reintento estrictamente BLOQUEANTE (sin `@RetryableTopic`), para garantizar que no se rompa el orden cronológico estricto requerido para la cadena de hashes (hash-chain).

## Alternativas Consideradas

*   **Tópicos por tenant:** Descartado. Crear un conjunto de tópicos de Kafka por cada tenant generaría una explosión de particiones (partition exhaustion), elevando excesivamente el consumo de memoria y CPU en los brokers y afectando la escalabilidad del clúster a miles de tenants.
*   **Outbox con Debezium (Change Data Capture):** Descartado para la fase inicial. Aunque Debezium es altamente eficiente, el modelo de una base de datos por tenant implicaría configurar un conector CDC por cada tenant. Esto introduce un nivel de complejidad de infraestructura y operación desproporcionado comparado con el relay de tabla Outbox local, el cual resulta suficiente para el volumen inicial de oficios y es más fácil de orquestar. Se deja Debezium como opción diferida si el rendimiento lo requiere.
*   **Mensajería con RabbitMQ / ActiveMQ:** Descartado. Kafka ofrece semánticas de replay de mensajes (retención persistente), integración nativa con arquitecturas de streaming y un particionamiento ideal para escalar los consumidores LLM mediante consumer groups, capacidades que superan a las colas tradicionales para el caso de uso del orquestador del pipeline de extracción.

## Consecuencias
*   **Positivas:** La exclusión de PII del clúster de Kafka simplifica el cumplimiento de normativas de retención y Habeas Data, ya que no es necesario purgar eventos individuales. El diseño sin tópicos por tenant asegura la viabilidad técnica y financiera del escalamiento masivo.
*   **Negativas / Riesgos:** El patrón Outbox implementado sobre bases de datos distribuidas por tenant requiere una ingeniería de relay cuidadosa (manejo de transacciones, bloqueos `FOR UPDATE SKIP LOCKED`, ShedLock) para asegurar baja latencia sin saturar los recursos o provocar condiciones de carrera. El patrón Claim-Check incrementa el tráfico interno (RPC/HTTP) debido a las consultas requeridas para recuperar los datos post-evento.

## Controles de Seguridad Aplicables
*   **SEC-004:** Cifrado en tránsito TLS 1.3 estricto para las conexiones entre los microservicios y Kafka.
*   **SEC-050:** Cero PII en eventos Kafka (Claim-Check obligatorio para todas las integraciones asíncronas).
*   **SEC-020:** Aislamiento lógico. Validación estricta del `tenant_id` incluido en el payload de cada evento consumido.
*   **SEC-005:** Autenticación mTLS (o SCRAM) y autorización vía ACLs (Strimzi `KafkaUser`) por servicio en Kafka.