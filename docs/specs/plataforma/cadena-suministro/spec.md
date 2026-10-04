# Especificación: Seguridad de la Cadena de Suministro (CI)

## 1. Propósito
Proteger la integridad de los artefactos de software generados por el proyecto, garantizando que el código y las dependencias están libres de vulnerabilidades conocidas antes de desplegarse, asegurando el origen de las imágenes de contenedores mediante firmas criptográficas y cumpliendo con estándares modernos de Supply Chain Security (ej. SLSA).

## 2. Alcance y No Alcance
**Alcance:**
* Análisis Estático de Código de Seguridad (SAST).
* Escaneo de dependencias transitivas y base de contenedores (SCA y Container Scanning).
* Generación y registro de Listas de Materiales de Software (SBOM).
* Firma criptográfica de artefactos Docker.
* Políticas de control de admisión en clúster (validación de firma en tiempo de despliegue).

**No Alcance:**
* Análisis Dinámico (DAST) en entornos productivos.
* Aprovisionamiento de infraestructura de CI/CD (se asume existencia de GitHub Actions, GitLab CI, etc., pero se norman sus "gates").

## 3. Requisitos Cubiertos
* **RNF-102:** Disponibilidad multi-cloud. Al firmar independientemente del registro base, se asegura la portabilidad segura.
* **Aspectos Generales de Seguridad (Matriz):** Múltiples controles de DevSecOps, evitando la inyección de código malicioso o dependencias envenenadas.

## 4. Reglas
1. **Política Zero-Critical:** El pipeline (CI) debe romperse y fallar si se detecta cualquier vulnerabilidad de severidad ALTA o CRÍTICA en código estático, dependencias de librerías, o paquetes de la imagen base.
2. **Firma Obligatoria:** Toda imagen promovida como Release-Candidate o Producción debe ser firmada. Imágenes sin firma no pueden ser desplegadas bajo ninguna circunstancia.
3. **Visibilidad SBOM:** Cada construcción (build) de backend o frontend debe generar su SBOM en formato estándar (CycloneDX) y publicarlo al registro.
4. **Validación Automática de PR:** Ningún Pull Request podrá hacer merge a `develop` o `main` sin pasar los chequeos SAST y SCA.

## 5. Contrato
* **Artefactos:** Imágenes Docker en OCI Registry (firmadas mediante `cosign`). Documentos SBOM en formato `CycloneDX`.
* **Políticas de Clúster:** Reglas definidas para un Admission Controller (ej. `Kyverno` o `OPA Gatekeeper`) que exigen la presencia de validación `cosign` en todo namespace de la aplicación.

## 6. Modelo de Datos
*No se utiliza base de datos transaccional local. Toda la metadata de seguridad reside como metadatos en el Container Registry o en la plataforma de control de código (GitHub/GitLab).*

## 7. Controles de Seguridad
* **SEC-SAST:** Integración de herramientas como CodeQL o Semgrep para analizar el código Java, buscando inyecciones SQL, fallas lógicas y problemas de concurrencia (complementado con SpotBugs).
* **SEC-SCA:** Dependabot o Renovate para actualización automatizada; `Trivy` para el escaneo profundo de imágenes OCI y librerías JAR.
* **SEC-PROVENANCE:** Implementación de firmas con `cosign` (Sigstore) para atestiguar que el artefacto proviene de la tubería oficial.
* **SEC-ADMISSION:** Uso de `Kyverno` (`verifyImages`) configurado en los clústeres destino para bloquear la instanciación de Pods que utilicen imágenes no firmadas por la llave oficial de CI.

## 8. Escenarios de Aceptación

* **AC-01 [Análisis Estático SAST]:** Given un desarrollador que abre un PR introduciendo código Java susceptible a SQL Injection, When el pipeline de CI se ejecuta, Then Semgrep/CodeQL detecta el patrón malicioso y bloquea el Pull Request marcándolo como fallido.
* **AC-02 [Vulnerabilidad de Dependencia]:** Given una nueva vulnerabilidad crítica (CVE) descubierta en una librería transitiva, When el proceso diario de escaneo con Trivy se ejecuta, Then se detecta la falla y se emite una alerta, y si se realiza un nuevo build, el CI falla.
* **AC-03 [Firma de Imagen]:** Given un build exitoso (tests y escaneos pasados) en la rama `main`, When el pipeline construye y empuja la imagen Docker, Then invoca `cosign` de forma automática, adjuntando la firma digital a la imagen en el registro OCI.
* **AC-04 [Bloqueo de Despliegue - Kyverno]:** Given un administrador intentando desplegar manualmente una imagen no firmada o modificada (tagueada como la original), When Kube-apiserver recibe la solicitud de creación de Pod, Then el Admission Controller (Kyverno) rechaza el despliegue indicando la falla de validación criptográfica `verifyImages`.
* **AC-05 [Generación SBOM]:** Given la fase de empaquetado Maven de un microservicio, When finaliza el proceso de compilación, Then se genera automáticamente el archivo CycloneDX detallando todas las dependencias exactas y se asocia al artefacto de despliegue final.
* **AC-06 [Calidad de Código]:** Given código Java nuevo que contiene fallas de concurrencia o code-smells severos, When se ejecutan los análisis de SpotBugs, Then el pipeline falla indicando el error exacto que debe corregirse antes del merge.
* **AC-07 [Actualización Automática]:** Given una actualización menor de una librería no vulnerable (ej. parche de rendimiento), When la herramienta Dependabot la detecta, Then se crea automáticamente un PR, que si pasa todos los tests unitarios e integración, está listo para revisión.
* **AC-08 [Aislamiento de Pipeline]:** Given el entorno de CI ejecutándose, When se construyen las imágenes y se corren pruebas con Testcontainers, Then el contenedor del pipeline no requiere privilegios elevados permanentes (`privileged: true` para construcción insegura, sino herramientas como Kaniko o Buildah sin root).

## 9. Métricas y SLO
* **Métricas:**
  * Cantidad de vulnerabilidades Críticas/Altas introducidas por PR.
  * Porcentaje de imágenes en registro que poseen SBOM y firma válida.
* **SLO:**
  * Tiempo medio de resolución (MTTR) de vulnerabilidades Críticas reportadas por SCA <= 48 horas.

## 10. Dependencias
* **Herramientas de Análisis:** Semgrep / CodeQL (SAST), SpotBugs (Análisis Java Bytecode), Trivy (Container & SCA scanner).
* **Firma y Procedencia:** Sigstore (`cosign`).
* **Verificación en Ejecución:** Kyverno (Política `verifyImages`) o equivalente.
* **Formatos:** CycloneDX (SBOM).
