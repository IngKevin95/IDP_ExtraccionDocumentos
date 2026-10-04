output "vpc_id" {
  description = "El ID de la VPC creada"
  value       = module.vpc.vpc_id
}

output "eks_cluster_name" {
  description = "El nombre del cluster EKS"
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "El endpoint del plano de control del cluster EKS"
  value       = module.eks.cluster_endpoint
}

output "openbao_kms_key_arn" {
  description = "El ARN de la llave KMS para el auto-unseal de OpenBao"
  value       = aws_kms_key.openbao.arn
}

output "cnpg_backups_bucket_name" {
  description = "El nombre del bucket S3 para los backups de la base de datos (CloudNativePG)"
  value       = aws_s3_bucket.cnpg_backups.id
}

output "ecr_repository_url" {
  description = "La URL del repositorio de ECR"
  value       = aws_ecr_repository.idp_services.repository_url
}
