terraform {
  required_version = ">= 1.8.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.67.0"
    }
  }

  # Configuración recomendada de backend remoto documentada
  # backend "s3" {
  #   bucket         = "idp-tofu-state"
  #   key            = "infra/aws/terraform.tfstate"
  #   region         = "us-east-1"
  #   encrypt        = true
  #   dynamodb_table = "idp-tofu-locks"
  # }
}
