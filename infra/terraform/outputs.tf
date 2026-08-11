output "eip" {
  description = "Public Elastic IP for direct SSH / debugging"
  value       = aws_eip.prf_lab.public_ip
}

output "instance_id" {
  description = "EC2 instance ID"
  value       = aws_instance.prf_lab.id
}

output "security_group_id" {
  description = "Security group ID"
  value       = aws_security_group.prf_lab.id
}
