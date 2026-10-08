terraform {
  required_version = ">= 1.8.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.5"
    }

    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }

    # Solo para google_project_service_identity (agente de servicio de Artifact Registry).
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 6.0"
    }
  }

  # Configuración recomendada de backend remoto documentada
  # backend "gcs" {
  #   bucket = "idp-tofu-state"
  #   prefix = "infra/gcp"
  # }
}
