# 0018. Renderer sandbox + antivirus

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El flujo del sistema IDP se basa en recepcionar archivos PDF (o formatos de imágenes). El análisis directo de documentos es intrínsecamente peligroso debido al riesgo de scripts embebidos, XXE y vulnerabilidades de bibliotecas (RCE). El componente encargado de abrir, validar y rasterizar documentos a imágenes PNG (para el consumo del LLM multimodal) es la superficie más expuesta.

## Decisión
El proceso de renderización se aísla de manera absoluta mediante el patrón de aislamiento y desescalada de privilegios en el componente `renderer`.
1. Interacción síncrona segura: Se realiza una llamada HTTP síncrona desde el `document-service` hacia el `renderer` securizada con mTLS. El `renderer` no posee acceso a Kafka ni utiliza credenciales. El servicio devuelve los PNG generados por página y la capa de texto nativa del PDF si existe; luego, `document-service` persiste esto en el bucket y publica `documento.renderizado`.
2. Diseño sin estado y sin red: Una NetworkPolicy en Kubernetes restringe al `renderer` de forma estricta: ingress permitido únicamente desde el `document-service` y egress denegado completamente.
3. Análisis Antivirus en origen: El clúster cuenta con un demonio `clamd` desplegado como sidecar en el pod del `renderer`. Las firmas de virus provienen de un mirror interno que es actualizado por un CronJob `freshclam` separado (el único componente de este segmento que posee egress hacia el mirror de firmas). Si el archivo es inválido o contiene malware, el `document-service` publica el evento obligatorio `documento.rechazado`.
4. Contramedidas intrínsecas y Rasterización estéril: Se rechaza todo JavaScript, entidades DTD/XML (XXE) y contraseñas inexplicables.
5. Soporte DOCX: Conversión de archivos DOCX a PDF usando LibreOffice headless ejecutado dentro del mismo entorno sandbox (sin red externa, macros deshabilitadas y con límites estrictos de CPU/tiempo de ejecución para prevenir ataques de denegación de servicio por documentos maliciosos).

## Alternativas consideradas
- Monolito de lectura y extracción: Contradice el principio de menor privilegio al juntar análisis de PDFs (alto riesgo) con acceso a APIs de LLM o S3.
- OCR externo en sistema (Tesseract/Ghostscript): Aumenta superficie de ataque de dependencias compartidas C/C++.
- Sanitización activa del PDF preservando texto reensamblado: Riesgo de corromper la semántica del oficio.

## Consecuencias
- Positivas: Disminución drástica del impacto de 0-days en bibliotecas de procesamiento documental.
- Negativas o costos: Necesidad de gestionar exhaustivamente el uso de heap RAM por presión de imágenes. Exige ajustar requests/limits de Kubernetes por pod.

## Controles relacionados
SEC-023, SEC-024, SEC-025, SEC-026