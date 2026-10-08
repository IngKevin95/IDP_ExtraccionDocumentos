terraform {
  required_version = ">= 1.8.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.40"
    }
    azapi = {
      source  = "azure/azapi"
      version = "~> 2.4"
    }
  }

  # Configuración recomendada de backend remoto documentada
  # backend "azurerm" {
  #   resource_group_name  = "idp-tfstate-rg"
  #   storage_account_name = "idptfstate"
  #   container_name       = "tfstate"
  #   key                  = "infra/azure/terraform.tfstate"
  #   use_azuread_auth     = true
  # }
}
