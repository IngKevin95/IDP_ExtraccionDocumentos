# 0011. Extracción Multimodal pura sin OCR determinístico

Estado: Aceptada
Fecha: 2026-09-29

## Contexto

El objetivo fundacional de la aplicación (Fase 4 y 5) es capturar datos críticos (radicados de juzgado de 23 dígitos, cédulas, números de cuentas, montos financieros exactos) e información tabular (listas de deudores, cuentas a embargar) presentes en oficios judiciales de embargo/desembargo. Los oficios provienen de múltiples tribunales, variando infinitamente en estructura, sellos superpuestos sobre el texto, manchas de escaneo y rotaciones de cámara, careciendo a menudo de una capa de texto PDF nativa seleccionable. Las arquitecturas tradicionales de procesamiento de documentos (IDP legacy) dependían invariablemente de una primera fase de OCR puro (Optical Character Recognition, como Tesseract, Azure AI Vision, Textract) que pasaba cajas limítrofes (bounding boxes) y el texto sucio y disgregado a modelos de lenguaje o reglas regex para la extracción final semántica.

## Decisión

Acorde al lineamiento central (Sección 0 del Plan Maestro) y a la evidencia recolectada en la validación DocFly, se decide prescindir categóricamente de motores de OCR clásicos intermediarios. Se ejecutará una **arquitectura de extracción multimodal directa (Single-page Image Mode)**:

1. **Rasterizado Puro:** El módulo seguro (`renderer`) convertirá las páginas PDF (independientemente de si poseen capa de texto vectorial nativa o no) en imágenes rasterizadas estáticas de alta fidelidad (PNG).
2. **Entrada directa al LLM Multimodal:** Las imágenes se inyectarán crudas, junto con un contexto instruccional (*prompt/few-shot*), directamente a Modelos de Lenguaje Multimodal de Frontera (LMM) como el modelo LLM vigente homologado.
3. **Manejo Tabular Particionado:** Las tablas extensas que atraviesan varias páginas se extraerán de manera individual procesando imagen por imagen ("single page mode") mediante un modelo ligero o prompt especializado y las filas extraídas serán cosidas (stitched) a posteriori en el orquestador backend de Java.

## Alternativas consideradas

- **Arquitectura de dos pasos (Legacy OCR -> Texto String -> LLM NLP puro):** Aplicar OCR, extraer todo el texto en una megacadena desestructurada, y enviársela a un modelo de texto LLM. *Por qué se descarta:* El OCR colapsa y corrompe drásticamente la distribución espacial (layout) del documento, arruinando estructuras tabulares complejas. Las celdas se mezclan y cuando el texto sucio llega al LLM de texto plano, es insalvable y genera falsos positivos financieros.
- **Modelos de extracción de documentos SaaS en la nube (AWS Textract / Azure Document Intelligence):** Servicios entrenados de caja para IDP. *Por qué se descarta:* Primero, crean un lock-in absoluto del proveedor y obligan a pagar altos costos por página que destruyen la rentabilidad base. Segundo, en tribunales de jurisdicciones muy particulares, su precisión con sellos manuscritos falla, imposibilitando inyectar "few-shots" específicos de oficios que un LLM Multimodal general sí acepta orgánicamente en su ventana de contexto.

## Consecuencias

### Positivas
- **Supremacía en fidelidad espacial:** Los LLMs multimodales procesan de forma "orgánica" el documento, entendiendo la contigüidad, las tablas complejas sin bordes, y los sellos superpuestos sobre firmas, imitando la cognición humana en la lectura, elevando masivamente la tasa de extracción en layouts desconocidos.
- **Simplificación abrumadora de arquitectura:** Se elimina una pieza de infraestructura móvil, costosa e intensiva en latencia (el clúster Tesseract o las llamadas a APIs de OCR). Todo el peso cognitivo transita directamente de PDFBox (render en Java) al LlmProvider (Spring AI).
- **Adaptabilidad a la rotación y distorsión:** Los modelos multimodales modernos muestran capacidades zero-shot inherentes para entender el texto en documentos rotados o fotos sesgadas sin necesitar complejas redes neuronales de pre-enderezamiento ("deskew").

### Negativas o Costos
- **Costo transaccional de LLM y latencia de inferencia:** Inyectar imágenes (tokens visuales) consume una fracción significativamente más alta del costo API y procesar inferencia de imágenes en los pesos multimodales toma más segundos que procesar texto plano en modelos paralelos o OCR local en C++.
- **Problema de la "Alucinación Visual":** A diferencia del OCR que, al fallar omite, el LMM forzado puede ocasionalmente alucinar números o celdas de tabla tratando de resolver manchas o firmas. Requiere compensaciones arquitectónicas pesadas aguas abajo (ver ADR 0012 Motor de Fiabilidad).

## Controles relacionados

- **SEC-032:** Fidelidad absoluta en cifras, montos y fechas extraídas (Sin redondeos. Requiere que el LMM respete verbatim el píxel).
- **SEC-037:** Proveedores homologados (Garantizar que enviar la imagen de un oficio a AWS Bedrock o GCP Vertex excluye los datos explícitamente del entrenamiento de pesos frontera).


**Equidad de procesamiento:** El consumo del LLM estará protegido por cuotas y un tope de concurrencia por tenant usando bulkhead de Resilience4j.

- **SEC-049:** Registro inmutable y firmado del conjunto prompt+configuración+versión de modelo usado por cada extracción/respuesta.
