# Tareas: Servicio Renderer

Estas tareas definen la secuencia de implementación del servicio `renderer`. La aprobación de un Pull Request requiere el cumplimiento estricto de su respectivo Criterio de Aceptación (AC) definido en la especificación.

## T-01: Andamiaje del proyecto y configuración base
- **Descripción:** Crear el módulo Maven `services/renderer` con dependencias mínimas (Spring Web, Actuator, Apache PDFBox, Apache Tika-core). Configurar los límites de tamaño en `application.yml` (50MB límite de subida). Implementar clases base (Controller, GlobalExceptionHandler según taxonomía de IDP).
- **Criterio de hecho:** La aplicación levanta. Hay un endpoint de liveness `/actuator/health` funcionando, y un endpoint POST `/v1/render` que recibe un MultipartFile y devuelve error no implementado.
- **Validación:** Validado localmente comprobando el arranque.

## T-02: Implementación de Validación de Formatos (Magic Bytes)
- **Descripción:** Desarrollar `FileTypeValidator` apoyado en Apache Tika para leer la cabecera (magic bytes) del `InputStream`. Rechazar archivos que no sean PDF, DOCX, PNG, JPG, o TIFF.
- **Criterio de hecho:** Todo request cuyo contenido binario real no corresponda a los tipos permitidos, independientemente de la extensión del archivo, es rechazado con error 400.
- **Validación:** Implementación del **AC-07** mediante test unitario enviando un ejecutable renombrado a `.pdf`.

## T-03: Integración de Sidecar Antivirus (ClamAV)
- **Descripción:** Crear `ClamAvClient` para conectar al demonio local por TCP (127.0.0.1:3310). Implementar el envío asíncrono (chunks) por stream para evitar cargar el binario completo en memoria simultánea a la transmisión HTTP.
- **Criterio de hecho:** Todo documento recibido pasa primero por el motor antivirus.
- **Validación:** Testcontainer de ClamAV validando el **AC-04**.

## T-04: Implementación de Procesamiento de PDF (Apache PDFBox)
- **Descripción:** Desarrollar `PdfProcessor`. Implementar validación de número máximo de páginas (limitar a 100). Iterar las páginas para: (1) usar `PDFTextStripper` en la región exacta y recuperar texto, y (2) usar `PDFRenderer` a DPIs fijos (ej. 150 DPI) para rasterizar a una imagen PNG en memoria. Configurar restricciones contra JS.
- **Criterio de hecho:** Un PDF es convertido eficientemente a representaciones de imagen y texto en objetos transitorios internos. Aborta a las 100 páginas o si hay scripts perjudiciales.
- **Validación:** Tests unitarios comprobando el **AC-01** (camino feliz), **AC-05** (sin JS/Acciones) y **AC-08** (límite de páginas).

## T-05: Implementación de Procesamiento DOCX (LibreOffice Headless)
- **Descripción:** Desarrollar `DocxProcessor`. Construir el comando `soffice --headless ...` para convertir DOCX temporal a PDF. Implementar la escritura local temporal en volumen `/tmp`, esperar el proceso con un límite de tiempo (30s) y si sobrepasa, interrumpir (`process.destroyForcibly()`). Posterior a la generación del PDF, reutiliza el `PdfProcessor` (T-04).
- **Criterio de hecho:** Los DOCX se convierten a PDF y luego se rasterizan, respetando timeouts.
- **Validación:** Test del **AC-02** (feliz) y **AC-06** (rechazando o fallando macros de manera segura).

## T-06: Implementación de Ensamblaje ZIP e Integración de Respuestas
- **Descripción:** Conectar el `RenderService` con el `RenderController`. Tomar las imágenes PNG y el texto generados, e inyectarlos directamente como entradas de un `ZipOutputStream` sobre la respuesta del Servlet, evitando crear el ZIP físico completo en memoria o disco. El archivo debe incluir las imágenes `page_N.png` y un `metadata.json` con el texto.
- **Criterio de hecho:** El cliente HTTP (document-service simulado) recibe un `application/zip` descargable que contiene todas las hojas y un JSON con el texto por hoja.
- **Validación:** Test e2e (Spring Boot Test) cubriendo la respuesta final del API y validando integridad del ZIP descomprimido (**AC-01, AC-03**).

## T-07: Configuración de Seguridad mTLS
- **Descripción:** Ajustar las propiedades de servidor (Tomcat en Spring) para requerir `client-auth=need`. Referenciar el Truststore de CA interna que autorizará exclusivamente al Common Name del `document-service`.
- **Criterio de hecho:** Peticiones HTTP regulares (sin certificado de cliente o con uno no autorizado) reciben 401/403.
- **Validación:** Configurar TLS en los tests de integración y forzar peticiones inválidas.

## T-08: Configuración Dockerfile con LibreOffice
- **Descripción:** Escribir un Dockerfile multicapa basado en una imagen Java (ej. `eclipse-temurin:21-jre-alpine` u Oracle Linux/Ubuntu) instalando LibreOffice y dependencias gráficas/fuentes mínimas necesarias para conversión sin X11. Asegurar que corra bajo UID no-root. Configurar NetworkPolicy para denegar egress de red.
- **Criterio de hecho:** La imagen final del `renderer` permite ejecución exitosa de DOCX. No posee acceso a internet.
- **Validación:** Build de contenedor local y validación con DOCX de prueba dentro del contenedor. Validación de SEC-025 de egress denegado (AC-09).