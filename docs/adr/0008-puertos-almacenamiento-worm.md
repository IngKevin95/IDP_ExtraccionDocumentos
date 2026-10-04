# ADR 0008: Abstracción de Almacenamiento mediante Puertos y Cumplimiento WORM

## Estado
Aprobado

## Contexto
El procesamiento de oficios requiere almacenar archivos físicos (PDFs originales, imágenes renderizadas) temporalmente y a largo plazo. Dado que la plataforma debe desplegarse tanto en entornos Cloud gestionados (AWS, GCP, Azure) como on-premise (auto-alojado), es necesario evitar el acoplamiento directo a las librerías propietarias de un proveedor específico de la nube. Además, para fines de auditoría y valor probatorio en litigios bancarios, ciertos elementos de almacenamiento, como los eventos de trazabilidad y cabezas de hash-chains, deben cumplir requisitos estrictos WORM (Write Once Read Many).

## Decisión
Se implementa una arquitectura basada en **Puertos y Adaptadores (Arquitectura Hexagonal)** para todas las dependencias de infraestructura, específicamente para el almacenamiento y firmas inmutables.

1.  **Puertos de Dominio Java:** Se definirán interfaces estrictas en la capa de núcleo (`libs/`), tales como `ObjectStore` (para manejo estándar de archivos) e `ImmutableStore` (para escrituras garantizadas de auditoría y hashes). El código de negocio dependerá únicamente de estas interfaces abstractas.
2.  **Adaptadores por Entorno (S3 Compatible):** Se desarrollarán implementaciones específicas por nube. Para despliegues on-premise, se construirá un adaptador `s3-compatible-adapter` diseñado para conectarse a infraestructuras como **Ceph RGW o SeaweedFS** (se prohíbe explícitamente el uso de MinIO por requerimientos del plan maestro).
3.  **Almacenamiento WORM:** El adaptador implementado para el puerto `ImmutableStore` se integrará directamente con las políticas de retención inmutables del proveedor subyacente (ej. AWS S3 Object Lock, GCP Bucket Retention Policies).
4.  **Aislamiento de Buckets:** Al igual que en la base de datos (ADR 0006), se mantendrá el aislamiento creando un bucket S3/compatible (o carpeta estrictamente particionada y segregada por IAM) exclusivo por cada tenant para almacenar sus archivos PNG y textos nativos procesados.

## Alternativas Consideradas

*   **Acoplamiento directo a AWS SDK:** Descartado. Utilizar directamente `AmazonS3Client` a lo largo de los servicios impediría el despliegue de la solución en GCP, Azure u on-premise (nubes híbridas).
*   **MinIO como Object Store por defecto:** Descartado explícitamente. Las directrices arquitectónicas vigentes prohíben MinIO, favoreciendo alternativas robustas de clúster on-premise como Ceph RGW o SeaweedFS debido a su escalabilidad nativa y capacidades de Object Lock requeridas para WORM.
*   **Almacenamiento temporal en File System Local (Pods):** Descartado. Los contenedores (pods) en Kubernetes son efímeros por naturaleza. Almacenar documentos recibidos localmente provocaría pérdida de datos en reinicios, limitando la escalabilidad horizontal y rompiendo el principio stateless de los microservicios computacionales.

## Consecuencias
*   **Positivas:** Altísima portabilidad de la plataforma. Cumplimiento nativo de normativas de auditoría bancaria mediante el mapeo del puerto `ImmutableStore` con las capacidades de bloqueo legal de los proveedores cloud/on-premise.
*   **Negativas / Riesgos:** Mayor esfuerzo de desarrollo inicial para crear la estructura de módulos Maven (puerto/adaptadores) y definir tests de integración (Testcontainers) que certifiquen que todos los adaptadores, independientemente de la nube subyacente, respetan el mismo contrato de comportamiento, en especial con las garantías WORM y bloqueos.

## Controles de Seguridad Aplicables
*   **SEC-010:** Repositorio WORM inmutable para logs y evidencias de auditoría, implementado en la capa de almacenamiento objetual.
*   **SEC-020:** Segregación de información (Bucket dedicado por tenant o esquema IAM estricto en object storage compatible).
*   **SEC-024:** Cifrado en reposo persistente. Todo objeto debe escribirse cifrado utilizando llaves generadas (KEK) administradas.