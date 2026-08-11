terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# Use existing VPC and subnet (adjust if needed)
data "aws_vpc" "default" {
  default = true
}

data "aws_subnet" "default" {
  availability_zone = "${var.aws_region}a"
  vpc_id            = data.aws_vpc.default.id

  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# AMI: Ubuntu 22.04 LTS for ARM64 (t4g.small)
data "aws_ami" "ubuntu_arm64" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-2024*/ubuntu-jammy-*-arm64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# Security Group
resource "aws_security_group" "prf_lab" {
  name_prefix = "prf-lab-"
  description = "PRF Assurance Lab security group"
  vpc_id      = data.aws_vpc.default.id

  # SSH from operator IPs
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.operator_ssh_cidrs
  }

  # HTTP from CloudFront origin-facing prefix list
  ingress {
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    prefix_list_ids = [data.aws_managed_prefix_list.cloudfront_origin.id]
  }

  # HTTPS from CloudFront origin-facing prefix list
  ingress {
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    prefix_list_ids = [data.aws_managed_prefix_list.cloudfront_origin.id]
  }

  # CloudWatch / SSM
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "prf-lab"
  }
}

data "aws_managed_prefix_list" "cloudfront_origin" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# EC2 Instance
resource "aws_instance" "prf_lab" {
  name           = "prf-lab"
  ami            = data.aws_ami.ubuntu_arm64.id
  instance_type  = "t4g.small"
  subnet_id      = data.aws_subnet.default.id
  security_groups = [aws_security_group.prf_lab.name]

  iam_instance_profile = aws_iam_instance_profile.prf_lab.name

  user_data = <<-EOF
              #!/bin/bash
              apt-get update
              apt-get install -y awscli postgresql postgresql-contrib
              systemctl enable postgresql
              systemctl start postgresql
              EOF

  tags = {
    Name = "prf-lab"
  }
}

# Elastic IP
resource "aws_eip" "prf_lab" {
  instance = aws_instance.prf_lab.id
  domain   = "vpc"

  tags = {
    Name = "prf-lab-eip"
  }
}

# IAM role for S3 access (existing + new lab bucket access)
resource "aws_iam_role" "prf_lab" {
  name = "prf-lab-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_instance_profile" "prf_lab" {
  name = "prf-lab-profile"
  role = aws_iam_role.prf_lab.name
}

resource "aws_iam_role_policy_attachment" "prf_lab_s3" {
  role       = aws_iam_role.prf_lab.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"
}

# CloudFront origin for /api/*
# Note: Add this behavior to your existing CloudFront distribution manually
# or via separate terraform resource if you manage CF here.
output "eip" {
  description = "Elastic IP for the PRF lab host"
  value       = aws_eip.prf_lab.public_ip
}

output "instance_id" {
  description = "EC2 instance ID"
  value       = aws_instance.prf_lab.id
}
