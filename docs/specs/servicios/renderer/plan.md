# Plan de Implementación: Servicio Renderer

## 1. Módulo y Estructura
El servicio se construirá como una aplicación Spring Boot dentro del ecosistema multi-módulo del repositorio.

- **Módulo Maven:** `services/renderer`
- **Paquete Base:** `com.banco.idp.renderer`
- **Versión Java:** Java 21 LTS

### 1.1 Estructura de Paquetes
```text
com.banco.idp.renderer
├── api
│   ├── controller      # RenderController (REST endpoints)
│   ├── dto             # Clases de request/response, manejo de Multipart
│   └── exception       # Manejadores de excepciones y GlobalExceptionHandler
├── core
│   ├── service         # RenderService (Orquestador principal)
│   ├── processor       # PdfProcessor, DocxProcessor, ImageProcessor
│   └── model           # Clases internas efímeras (RenderContext, RenderResult)
├── security
│   ├── antivirus       # ClamAvClient, AntivirusScanner
│   ├── validation      # FileTypeValidator (Magic bytes)
│   └── config          # MtlsSecurityConfig
└── config              # ApplicationConfig (Límites, timeouts, threads)
```

## 2. Componentes Principales

### 2.1 API Rest (`RenderController`)
- Expone el endpoint POST `/v1/render`.
- Valida límites de tamaño en crudo mediante filtros Spring.
- Llama a `RenderService` y ensambla el `java.util.zip.ZipOutputStream` que se transmite directamente (stream) a la respuesta HTTP en lugar de retenerlo en memoria.

### 2.2 Validación y Antivirus (`ClamAvClient`, `FileTypeValidator`)
- **FileTypeValidator:** Inspecciona los primeros bytes (magic bytes) del flujo de entrada usando Apache Tika-core o librerías ligeras para corroborar que coincide verdaderamente con formatos permitidos (PDF, DOCX, PNG, JPG, TIFF).
- **ClamAvClient:** Se comunica con el demonio de ClamAV local en el puerto TCP `3310` usando el protocolo INSTREAM. Escribe fragmentos (chunks) para no ocupar doble memoria.

### 2.3 Procesadores de Archivo
- **DocxProcessor:** 
  - Usa la API de procesos (`java.lang.ProcessBuilder`) para invocar a la CLI de LibreOffice: `soffice --headless --convert-to pdf --outdir /tmp /tmp/input.docx`.
  - Debe implementar un timeout estricto (ej. 30 segundos) llamando a `Process.waitFor(timeout)`. Si se excede, mata el proceso.
- **PdfProcessor:** 
  - Utiliza **Apache PDFBox 3.x**.
  - `PDFTextStripper`: Para recuperar la capa de texto nativo página por página.
  - `PDFRenderer`: Para convertir cada página de PDF a imagen rasterizada (`BufferedImage` a `.png`).
  - Implementa controles de memoria en la creación de `PDDocument` (ej. uso de scratch file `MemoryUsageSetting.setupTempFileOnly()`).

## 3. Configuración de Spring
- Configuración Server (`application.yml`):
  - `server.ssl.client-auth=need` (Para exigir mTLS).
  - Configuración del truststore para validar el certificado cliente de `document-service`.
  - `spring.servlet.multipart.max-file-size=50MB` y `max-request-size=50MB`.
- Configuración de negocio (`application.yml`):
  - `renderer.clamav.host=127.0.0.1`
  - `renderer.clamav.port=3310`
  - `renderer.limits.max-pages=100`

## 4. Estrategia de Persistencia
**No aplica.** Este módulo carece deliberadamente de dependencias a PostgreSQL (Flyway), Redis o Kafka. No contiene `spring-data-jpa` ni `spring-kafka`.

## 5. Estrategia de Pruebas

- **Pruebas Unitarias:**
  - `FileTypeValidator`: Pruebas inyectando magic bytes falsos y válidos para asegurar la correcta identificación.
  - `PdfProcessor`: Pruebas con archivos PDF seguros (texto y renderización verificada de tamaño/formato). Pruebas de interrupción al alcanzar límite de páginas.
  - `DocxProcessor`: Se pueden simular respuestas (mocks) del ProcessBuilder para escenarios de error y timeout.

- **Pruebas de Integración (Testcontainers):**
  - Levantar un contenedor Docker `clamav/clamav:latest` en un entorno de integración.
  - Enviar un archivo con patrón EICAR y verificar que el `RenderService` lanza la excepción mapeada al error 400.
  - Validar todo el flujo de ensamblado del ZIP de respuesta mediante el endpoint real in-memory (`@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)`).

- **Pruebas de Contrato:** No se exponen a consumidores externos, pero la especificación OpenAPI (Swagger) será verificada contra el controlador implementado.