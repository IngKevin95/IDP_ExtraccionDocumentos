# Infraestructura como Código (IaC)

Este directorio contiene la definición de la infraestructura de la plataforma IDP utilizando [OpenTofu](https://opentofu.org/). 

La infraestructura se define modularmente por proveedor de nube pública, asegurando que la arquitectura base (redes privadas, clústeres de Kubernetes, almacenamiento de objetos y gestión de llaves) siga las mismas convenciones y estándares de seguridad.

## Flujo de Trabajo (OpenTofu)

Para desplegar o actualizar la infraestructura, se utiliza el flujo estándar de OpenTofu:

1. **Inicializar el entorno:**
   ```bash
   tofu init
   ```
   Descarga los providers y configura el backend.

2. **Revisar los cambios propuestos:**
   ```bash
   tofu plan -var-file="ambientes/dev.tfvars" -out="tfplan"
   ```
   Verifica qué recursos serán creados, modificados o destruidos.

3. **Aplicar los cambios:**
   ```bash
   tofu apply "tfplan"
   ```

## Backend Remoto y Ambientes

Se recomienda **estrictamente** configurar un backend remoto (ej. S3 + DynamoDB en AWS, GCS en GCP, o Azure Blob Storage) en el bloque `terraform` de cada proveedor para mantener el estado seguro y permitir la colaboración, así como habilitar el bloqueo de estado (state locking).

La separación por ambiente (ej. `dev`, `staging`, `prod`) se debe gestionar mediante workspaces de OpenTofu o, preferiblemente, utilizando directorios separados por ambiente que invoquen estos módulos base, pasando el archivo de variables correspondiente (`.tfvars`).
