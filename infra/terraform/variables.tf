variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "eu-north-1"
}

variable "operator_ssh_cidrs" {
  description = "List of CIDR blocks allowed to SSH"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "lab_origin_token" {
  description = "Shared secret for CloudFront origin authentication"
  type        = string
  sensitive   = true
}

variable "lab_publish_bucket" {
  description = "S3 bucket for lab run artifacts"
  type        = string
  default     = ""
}

variable "prf_project_dir" {
  description = "Local path to the PRF repo for deployment"
  type        = string
}
