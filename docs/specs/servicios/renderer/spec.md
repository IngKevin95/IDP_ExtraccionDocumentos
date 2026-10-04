# Especificación: Servicio Renderer

## 1. Propósito
El servicio `renderer` actúa como la principal barrera de defensa y motor de preprocesamiento del IDP para los archivos de entrada. Su responsabilidad exclusiva es recibir un documento, validar su formato real (magic bytes), ejecutar un análisis antivirus (mediante un sidecar), sanitizar su contenido (eliminando scripts y macros), extraer la capa de texto nativa si existe, y rasterizar todas las páginas de forma segura produciendo imágenes PNG puras.

## 2. Alcance y No Alcance
**En alcance:**
- Escaneo de malware (integración con ClamAV local).
- Validación estricta de tipos de archivo (PDF, DOCX, JPG, PNG, TIFF).
- Conversión segura de DOCX a PDF usando LibreOffice en modo headless.
- Rasterización de PDF a imágenes PNG por página usando librerías Java nativas.
- Extracción de capa de texto nativa incrustada en PDFs o DOCX.
- Devolución empaquetada (ZIP) de los resultados por HTTP síncrono.

**Fuera de alcance:**
- Extraer texto mediante OCR (se delega a LLM multimodal en extracción posterior).
- Persistencia de los resultados en el Storage (responsabilidad de `document-service`).
- Interacción con Kafka, bases de datos, o publicación de eventos.
- Autorización de usuarios o tenants (el acceso se basa exclusivamente en mTLS de servicio a servicio).

## 3. Requisitos Cubiertos
*Según la definición en `docs/producto.md` y `docs/arquitectura.md`:*
- **RF-103**: Sandbox de rasterizado (PDF, DOCX e imágenes) sin red ni credenciales.
- **RF-102**: Validación fail-fast del archivo (magic bytes, límites, malware).
- **RNF-101**: Aislamiento estricto; egress denegado (ver SEC-025).
- **SEC-023, SEC-024, SEC-025, SEC-026**: Validación de entrada, rasterizado seguro, antivirus y XXE deshabilitado.
- **RNF-105**: Control de memoria al rasterizar (procesamiento página a página, límites de heap) para no degradar el pipeline.

## 4. Reglas
- **Regla 4.1 (Aislamiento de Red):** El `renderer` no posee acceso a red externa. Su NetworkPolicy en Kubernetes bloquea todo tráfico Egress, a excepción de las conexiones locales al sidecar y el tráfico de respuesta al Ingress de `document-service`.
- **Regla 4.2 (Sin Estado):** El servicio no retiene datos en disco ni en memoria más allá del tiempo estricto de la petición HTTP. El filesystem del contenedor debe montarse como de solo lectura, usando un volumen efímero `emptyDir` para el procesamiento temporal.
- **Regla 4.3 (Descarte por Protección):** Ante cualquier error en el parseo, detección de JavaScript embebido, resolución de entidades DTD (XML/XXE), o hallazgo antivirus, la petición fallará inmediatamente con código de estado HTTP y no entregará fragmentos parciales.
- **Regla 4.4 (Límites de Seguridad):** Existe un límite estricto de tamaño de archivo (e.g. 50 MB) y un límite máximo de páginas a renderizar (e.g. 100). Superar estos límites levanta un error estructurado.

## 5. Contratos
- **API Expresada en OpenAPI:** `contracts/openapi/renderer.yaml`.
- **Eventos:** *No aplica*. El servicio `renderer` no consume ni publica eventos en Kafka (ADR-0018).

## 6. Modelo de Datos
Este servicio es **stateless** y no cuenta con una base de datos ni entidades persistentes. Todo su procesamiento ocurre en memoria y en un sistema de archivos temporal efímero en memoria (`/tmp` mapeado en RAM o disco local efímero).

## 7. Controles de Seguridad
| ID Control | Descripción | Implementación en renderer |
| :--- | :--- | :--- |
| **SEC-024** | Rasterizado de PDF a imagen, rechazando JS y macros | Bloqueo activo al parsear, y uso de LibreOffice con banderas `--norestore --nologo --nolockcheck --headless` y perfiles seguros. |
| **SEC-025** | Análisis antivirus en sandbox sin salida a internet y ausencia de credenciales en pod | Un contenedor sidecar local (`clamd`) analiza los bytes por un socket de dominio o TCP local antes del parseo documental. El pod del renderer carece de roles IAM de AWS/GCP o credenciales hacia servicios, Kafka o bóvedas, bloqueando totalmente conexiones salientes de internet y asegurando menor privilegio. |
| **SEC-026** | Deshabilitación de XML External Entities (XXE) | Las bibliotecas de extracción (p.ej. Apache PDFBox) deben ser configuradas desactivando parsers DTD externos. |

## 8. Escenarios de Aceptación

- **AC-01 (Camino feliz PDF):** Given un documento PDF válido y sin malware, When se invoca `/v1/render`, Then el servicio escanea exitosamente, rasteriza cada página a PNG, extrae la capa de texto y responde 200 OK con un ZIP.
- **AC-02 (Camino feliz DOCX):** Given un archivo DOCX de texto plano, When se invoca `/v1/render`, Then LibreOffice lo convierte a PDF internamente, es rasterizado, el texto nativo se extrae, y retorna 200 OK con el ZIP resultante.
- **AC-03 (Imágenes puras):** Given una imagen TIFF o JPG válida, When se invoca `/v1/render`, Then se retorna la conversión a PNG empaquetada y un capa de texto vacía (sin error).
- **AC-04 (Antivirus - EICAR):** Given un archivo con la firma de prueba EICAR, When se invoca `/v1/render`, Then el análisis ClamAV lo detecta y el servicio responde inmediatamente 400 Bad Request (`ERR_MALWARE_DETECTED`), sin intentar parsear el documento.
- **AC-05 (PDF con JavaScript):** Given un PDF que contiene Action Scripts o JavaScript malicioso para ejecución en el visor, When el parseador PDFBox intenta abrirlo y detecta acciones prohibidas o se purga la interactividad, Then se deniega el procesamiento (o se remueve el JS, garantizando PNG puros) respondiendo exitosamente.
- **AC-06 (DOCX con Macros):** Given un archivo DOCX que contiene macros VBA (`.docm` o disfrazado de `.docx`), When se invoca `/v1/render`, Then la validación o LibreOffice lo rechaza, devolviendo un 422 Unprocessable Entity.
- **AC-07 (Magic Bytes Inválidos):** Given un archivo renombrado a `.pdf` pero cuyo contenido real es un binario ejecutable (`.exe`/ELF), When se invoca `/v1/render`, Then falla la validación de formato respondiendo 400 Bad Request.
- **AC-08 (Protección DoS/Límites):** Given un PDF que requiere excesiva carga de CPU (bomba de descompresión o supera las 100 páginas), When se invoca `/v1/render`, Then se interrumpe el procesamiento al alcanzar el timeout o límite de páginas, devolviendo 413 Payload Too Large.
- **AC-09 (Aislamiento de Pod y Egress Denegado):** Given el despliegue del pod `renderer`, When se intenta establecer una conexión de red hacia internet o se intenta leer variables de entorno con credenciales, Then la conexión es bloqueada (egress denegado) y no existen roles IAM ni secretos montados, garantizando el aislamiento estricto y menor privilegio (SEC-025).

## 9. Métricas y SLO
- **Disponibilidad:** 99.9% (Al no tener dependencias externas, depende de la estabilidad del clúster).
- **Latencia P95:** < 5 segundos por página a renderizar.
- **Métricas custom:** 
  - `renderer.pages.processed` (Contador por formato original).
  - `renderer.scan.malware_detected` (Contador de amenazas interceptadas).
  - `renderer.memory.heap_usage_percent` (Gauge crítico).

## 10. Dependencias
- **ClamAV (Sidecar):** Demonio local `clamd` para análisis antivirus.
- **LibreOffice:** Instalado en el sistema operativo base de la imagen Docker para procesar documentos de la suite ofimática.
- **document-service:** Único cliente autorizado a invocar esta API a través de mTLS en el clúster.