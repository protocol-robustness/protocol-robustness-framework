#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="${INFRA_DIR:-$HOME/infra}"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       PRF Assurance Lab — Full Infrastructure Setup         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

for cmd in terraform aws; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: $cmd is not installed or not in PATH." >&2
    exit 1
  fi
done

echo "=== Step 1: Terraform — provision AWS resources ==="
cd "$INFRA_DIR/assurance-lab-terraform"

if [[ ! -f terraform.tfvars ]]; then
  echo "Creating terraform.tfvars from example..."
  cp terraform.tfvars.example terraform.tfvars
  echo "EDIT terraform.tfvars with your values (owner_email, key_name, etc.), then re-run."
  exit 1
fi

terraform init
terraform validate
terraform plan

read -p "Apply terraform? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  terraform apply -auto-approve
else
  echo "Skipping terraform apply."
fi

SERVER_IP=$(terraform output -raw server_ip 2>/dev/null || terraform output -raw eip 2>/dev/null || echo "")
if [[ -z "$SERVER_IP" ]]; then
  echo "ERROR: Could not get server_ip from terraform output." >&2
  exit 1
fi

CLOUDFRONT=$(terraform output -raw cloudfront_domain_name 2>/dev/null || echo "")
echo ""
echo "Server IP: $SERVER_IP"
[[ -n "$CLOUDFRONT" ]] && echo "CloudFront: https://$CLOUDFRONT"
echo ""

echo "=== Step 2: Ansible — provision EC2 host ==="
cd "$INFRA_DIR/ansible"

cat > inventory.ini <<INV
[servers]
devserver ansible_host=${SERVER_IP} ansible_user=ubuntu ansible_ssh_private_key_file=~/.ssh/id_ed25519
INV
echo "Created inventory.ini with server IP: $SERVER_IP"

if [[ ! -f group_vars/all.yml ]]; then
  echo "Creating group_vars/all.yml from template..."
  cat > group_vars/all.yml <<CONF
---
prf_project_dir: /opt/prf
lab_runs_dir: /var/lib/lab/runs
lab_port: 8082
app_root: /opt
app_dir: /opt/prf
app_user: ubuntu
app_group: ubuntu
lab_region: "eu-north-1"
CONF
fi

if [[ -z "${LAB_ORIGIN_TOKEN:-}" ]]; then
  echo "WARNING: LAB_ORIGIN_TOKEN not set. API proxy will not authenticate."
fi

ansible-playbook -i inventory.ini playbook.yml

echo ""
echo "=== Step 3: Verification ==="
echo "SSH into host: ssh -i ~/.ssh/id_ed25519 ubuntu@$SERVER_IP"
echo "Check services: systemctl status myapp postgresql nginx"
echo "Test site: curl http://$SERVER_IP/"
[[ -n "$CLOUDFRONT" ]] && echo "Or via CloudFront: https://$CLOUDFRONT/lab/"