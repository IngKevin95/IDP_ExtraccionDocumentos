# 0001. Ecosistema base: Java y Spring Boot

Estado: Aceptada
Fecha: 2026-09-29

## Contexto

La plataforma de Procesamiento Inteligente de Documentos (IDP) bancario tiene como responsabilidad central la extracción de información crítica y altamente estructurada desde oficios judiciales de embargo y desembargo (tipologías EC, EJ, DC, DJ), así como proveer capacidades de chat documental (RAG) sobre estos. Los oficios judiciales manejan datos extremadamente sensibles (PII, montos financieros, números de cuentas bancarias y radicados legales). Por ende, el sistema exige un lenguaje de programación y un marco de trabajo que garanticen estricto tipado estático, madurez en el ecosistema de integración empresarial, soporte robusto para concurrencia y un historial comprobado en la construcción de sistemas de misión crítica dentro del sector financiero. Adicionalmente, el equipo de desarrollo cuenta con amplia experiencia en ecosistemas empresariales basados en la máquina virtual de Java (JVM).

## Decisión

Se decide adoptar **Java 21 LTS** como lenguaje de programación único para todo el código backend del proyecto, utilizando **Spring Boot (versión estable vigente, se fija en F3)** como framework principal. 

Esta decisión establece los siguientes lineamientos estrictos:
1. Todo el código será 100% Java. No se permite la introducción de componentes satélite en lenguajes como Python o Node.js para tareas de IA; en su lugar se utilizará `Spring AI` para interactuar con los modelos de lenguaje.
2. El proyecto se estructurará como un proyecto Maven multi-módulo dividiendo claramente las librerías compartidas (`libs/`) y los servicios desplegables (`services/`).
3. Se evaluará la migración a Java 25 LTS durante la Fase 3, siempre y cuando la estabilidad de las librerías base (Spring, Testcontainers, pgvector-java) esté plenamente garantizada.

## Alternativas consideradas

- **Python (con FastAPI o Django):** Fue considerado debido a su dominio en el ecosistema de IA y Machine Learning (LLMs, RAG). *Por qué se descarta:* Su tipado dinámico (a pesar de type hints) y modelo de concurrencia nativo (GIL) no ofrecen las mismas garantías de robustez y predictibilidad en tiempo de compilación para la orquestación transaccional compleja requerida por el core bancario. La comunicación con LLMs en nuestro caso es mediante APIs (vLLM, Bedrock, Vertex), no requiere ejecutar tensores localmente.
- **Go (Golang):** Ofrece un tipado estático fuerte, binarios pequeños y un excelente modelo de concurrencia, ideal para arquitecturas cloud-native. *Por qué se descarta:* El ecosistema de librerías empresariales (especialmente conectores a sistemas legacy bancarios o abstracciones maduras de seguridad como Spring Security) es menos rico que el de Java. Además, la curva de adopción y estandarización del equipo generaría retrasos inaceptables para la Fase 1.
- **Node.js (TypeScript):** Ampliamente usado para servicios I/O bound. *Por qué se descarta:* El modelo de hilo único no es óptimo para la CPU intensiva ocasional (por ejemplo, firmas criptográficas de hash-chains de auditoría). Además, el ecosistema de dependencias (npm) presenta una superficie de ataque a la cadena de suministro estadísticamente más alta.

## Consecuencias

### Positivas
- **Robustez transaccional:** Spring Boot y el ecosistema JVM proveen abstracciones maduras y probadas para gestión de transacciones (sin JTA, una BD por transacción con outbox local), acceso a datos y mensajería.
- **Consolidación tecnológica:** Mantener un solo stack tecnológico reduce la carga cognitiva, unifica el pipeline de CI/CD (SAST CodeQL o Semgrep más SpotBugs; SCA e imágenes con Trivy y Dependabot; SBOM CycloneDX; cosign; Kyverno con verifyImages sin OPA) y facilita la movilidad de desarrolladores entre los distintos microservicios (desde el `edge-gateway` hasta el `chat-service`).
- **Integración de IA estandarizada:** El uso de Spring AI permite abstraer los proveedores de LLMs, aislando la lógica de negocio de las implementaciones específicas (AWS Bedrock, GCP Vertex, etc.).

### Negativas o Costos
- **Consumo de recursos:** Los contenedores basados en la JVM (incluso con optimizaciones como CDS o capas de Spring Boot) tienen una huella de memoria (RAM) significativamente mayor que binarios compilados nativamente (Go/Rust), lo que impactará los costos operativos en el despliegue Kubernetes.
- **Tiempos de arranque:** El "cold start" de Spring Boot es superior, lo que invalida arquitecturas puras de serverless (escala a cero). Esto obliga a mantener instancias calientes constantemente.

## Controles relacionados

- **SEC-044:** Seguridad en cadena de suministro (SAST, SCA centralizados en el ecosistema Maven).
- **SEC-046:** Separación de ambientes (facilitado por los perfiles de Spring Boot).
