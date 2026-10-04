# 0022. Cadena de suministro

Estado: Aceptada
Fecha: 2026-09-29

## Contexto
El sector bancario es blanco habitual de ataques sofisticados hacia la cadena de suministro de software (Supply Chain Attacks). Infecciones en librerías, imágenes base o vulnerabilidades CI/CD pueden introducir riesgos inasumibles de exfiltración de modelos o PII.

## Decisión
Se implementa una defensa multicapa automatizada ("Zero Trust Pipeline").
1. Análisis Estático y Composición: Los PRs pasan por SAST utilizando CodeQL o Semgrep complementados con SpotBugs. Se realiza análisis de composición (SCA) en imágenes y dependencias mediante Trivy y Dependabot.
2. Manifiestos de Software (SBOM): El pipeline genera y almacena un Software Bill of Materials exhaustivo en formato estándar CycloneDX.
3. Firmas Criptográficas y Controles sin Privilegios: Se utilizan imágenes base distroless ejecutando bajo usuarios no-root. Las imágenes de contenedores se firman criptográficamente usando Cosign.
4. Admisión Condicionada en Clúster (Kyverno): En vez de utilizar OPA Gatekeeper, se implementan políticas estrictas mediante el controlador Kyverno configurado con `verifyImages`, rechazando cualquier pod cuya imagen carezca de una firma verificada generada por nuestro pipeline oficial CI/CD.

## Alternativas consideradas
- Auditorías manuales (Pen-testing ex-post): Insuficiente ante vulnerabilidades día cero dinámicas.
- Escaneos en fases locales: Genera falsas seguridades y facilita compilaciones adulteradas en máquinas de desarrolladores.
- Uso de OPA Gatekeeper para la firma de imágenes: Se opta por Kyverno por su integración nativa y optimizada para verificación de firmas (verifyImages) y validación de contexto de seguridad simplificada.

## Consecuencias
- Positivas: Previene incidentes críticos garantizando inmutabilidad y autenticidad probada de los binarios implementados en línea con normativas.
- Negativas o costos: Ralentización del tiempo de compilación (build times) y detención inmediata del flujo productivo global si una dependencia clave sufre compromiso reportado (CVE crítico).

## Controles relacionados
SEC-044, SEC-045