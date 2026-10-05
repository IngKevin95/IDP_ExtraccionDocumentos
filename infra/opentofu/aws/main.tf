provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "OpenTofu"
    }
  }
}

locals {
  cluster_name = "${var.project_name}-${var.environment}-eks"
  vpc_name     = "${var.project_name}-${var.environment}-vpc"
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

# ------------------------------------------------------------------------------
# VPC (Red Privada)
# ------------------------------------------------------------------------------
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.7.3"

  name = local.vpc_name
  cidr = var.vpc_cidr

  azs             = slice(data.aws_availability_zones.available.names, 0, 3)
  private_subnets = [for k, v in slice(data.aws_availability_zones.available.names, 0, 3) : cidrsubnet(var.vpc_cidr, 4, k)]
  public_subnets  = [for k, v in slice(data.aws_availability_zones.available.names, 0, 3) : cidrsubnet(var.vpc_cidr, 4, k + 4)]

  enable_nat_gateway     = true
  single_nat_gateway     = var.environment != "prod"
  one_nat_gateway_per_az = var.environment == "prod"

  enable_dns_hostnames = true
  enable_dns_support   = true

  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }

  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}

# KMS Key para EKS Cluster Encryption
resource "aws_kms_key" "eks_encryption" {
  description             = "KMS key para EKS cluster encryption"
  deletion_window_in_days = var.kms_deletion_window_in_days
  enable_key_rotation     = true
}

# ------------------------------------------------------------------------------
# EKS Cluster
# ------------------------------------------------------------------------------
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "20.31.0"

  cluster_name    = local.cluster_name
  cluster_version = var.eks_cluster_version

  cluster_endpoint_public_access       = length(var.eks_public_access_cidrs) > 0
  cluster_endpoint_public_access_cidrs = var.eks_public_access_cidrs
  cluster_endpoint_private_access      = true

  cluster_enabled_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  create_cloudwatch_log_group = true

  create_kms_key = false
  cluster_encryption_config = {
    resources        = ["secrets"]
    provider_key_arn = aws_kms_key.eks_encryption.arn
  }

  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = module.vpc.private_subnets
  control_plane_subnet_ids = module.vpc.private_subnets

  cluster_addons = {
    eks-pod-identity-agent = {
      most_recent = true
    }
    coredns = {
      most_recent = true
    }
    kube-proxy = {
      most_recent = true
    }
    vpc-cni = {
      most_recent = true
    }
  }

  access_entries = {}

  eks_managed_node_groups = {
    default = {
      instance_types = var.node_instance_types
      min_size       = 2
      max_size       = 5
      desired_size   = 2
      capacity_type  = var.environment == "prod" ? "ON_DEMAND" : "SPOT"
    }
  }
}

# ------------------------------------------------------------------------------
# KMS Key para OpenBao (Auto-unseal)
# ------------------------------------------------------------------------------
data "aws_iam_policy_document" "openbao_kms" {
  statement {
    sid       = "Enable IAM User Permissions"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  statement {
    sid       = "Allow OpenBao Pod Identity to Use the Key"
    effect    = "Allow"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:DescribeKey"]
    resources = ["*"]
    principals {
      type        = "AWS"
      # Asume que se crea un rol específico para OpenBao con EKS Pod Identity
      identifiers = ["*"] # Debería ser el ARN del rol de OpenBao
    }
    condition {
      test     = "StringLike"
      variable = "aws:PrincipalArn"
      values   = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/openbao-role-*"]
    }
  }
}

resource "aws_kms_key" "openbao" {
  description             = "KMS key para auto-unseal de OpenBao"
  deletion_window_in_days = var.kms_deletion_window_in_days
  enable_key_rotation     = true
  policy                  = data.aws_iam_policy_document.openbao_kms.json
}

resource "aws_kms_alias" "openbao" {
  name          = "alias/${var.project_name}-${var.environment}-openbao-unseal"
  target_key_id = aws_kms_key.openbao.key_id
}

# ------------------------------------------------------------------------------
# KMS Key para S3 SSE (CNPG)
# ------------------------------------------------------------------------------
data "aws_iam_policy_document" "s3_kms" {
  statement {
    sid       = "Enable IAM User Permissions"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  statement {
    sid       = "Allow CNPG Pod Identity to Use the Key"
    effect    = "Allow"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
    condition {
      test     = "StringLike"
      variable = "aws:PrincipalArn"
      values   = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/cnpg-role-*"]
    }
  }
}

resource "aws_kms_key" "s3_kms" {
  description             = "KMS key para cifrado de S3 buckets (CloudNativePG)"
  deletion_window_in_days = var.kms_deletion_window_in_days
  enable_key_rotation     = true
  policy                  = data.aws_iam_policy_document.s3_kms.json
}

resource "aws_kms_alias" "s3_kms" {
  name          = "alias/${var.project_name}-${var.environment}-cnpg-backups"
  target_key_id = aws_kms_key.s3_kms.key_id
}

# ------------------------------------------------------------------------------
# S3 Bucket para Backups de CloudNativePG
# ------------------------------------------------------------------------------
resource "aws_s3_bucket" "cnpg_backups" {
  bucket        = "${var.project_name}-${var.environment}-cnpg-backups"
  force_destroy = var.environment != "prod"
}

resource "aws_s3_bucket_versioning" "cnpg_backups" {
  bucket = aws_s3_bucket.cnpg_backups.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "cnpg_backups" {
  bucket = aws_s3_bucket.cnpg_backups.id

  rule {
    bucket_key_enabled = true
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.s3_kms.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "cnpg_backups" {
  bucket = aws_s3_bucket.cnpg_backups.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "cnpg_backups_tls" {
  bucket = aws_s3_bucket.cnpg_backups.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EnforceTLS"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.cnpg_backups.arn,
          "${aws_s3_bucket.cnpg_backups.arn}/*"
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }
    ]
  })
}

resource "aws_s3_bucket_lifecycle_configuration" "cnpg_backups" {
  bucket = aws_s3_bucket.cnpg_backups.id
  rule {
    id     = "retention"
    status = "Enabled"
    expiration {
      days = 30
    }
  }
}

resource "aws_s3_bucket_logging" "cnpg_backups" {
  bucket = aws_s3_bucket.cnpg_backups.id

  target_bucket = aws_s3_bucket.access_logs.id
  target_prefix = "cnpg-backups-logs/"
}

resource "aws_s3_bucket" "access_logs" {
  bucket        = "${var.project_name}-${var.environment}-access-logs"
  force_destroy = var.environment != "prod"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ------------------------------------------------------------------------------
# ECR Repository para Imagenes de la Plataforma
# ------------------------------------------------------------------------------
resource "aws_kms_key" "ecr_kms" {
  description             = "KMS key para ECR"
  deletion_window_in_days = var.kms_deletion_window_in_days
  enable_key_rotation     = true
}

resource "aws_ecr_repository" "idp_services" {
  name                 = "${var.project_name}/${var.environment}/services"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.ecr_kms.arn
  }
}