# PRF Assurance Lab — Infrastructure

Terraform + Ansible for the PRF Assurance Lab EC2 host.

## Directory layout

```
infra/
├── terraform/
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   └── terraform.tfvars.example
└── ansible/
    ├── playbook.yml
    ├── inventory.example
    ├── group_vars/
    │   └── all.yml
    └── roles/
        ├── common/
        ├── postgres/
        ├── nginx/
        ├── prf-lab/
        └── site/
```

## Prerequisites

- Terraform >= 1.5.0
- Ansible >= 2.15
- AWS CLI configured with credentials
- SSH key for EC2 access

## 1. Terraform — provision AWS resources

```bash
cd infra/terraform

cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your values

terraform init
terraform plan
terraform apply
```

## 2. Ansible — provision the EC2 host

```bash
cd infra/ansible

cp inventory.example inventory.yml
# Edit inventory.yml with the EC2 EIP and SSH key path

# Set required environment variables
export LAB_ORIGIN_TOKEN="<random-secret>"
export PRF_PROJECT_DIR="/path/to/protocol-robustness-framework"

# Run the playbook
ansible-playbook -i inventory.yml playbook.yml
```

## What gets installed

| Component | Method | Purpose |
|---|---|---|
| OpenJDK 21 | apt | JVM runtime |
| Clojure CLI | official script | Build toolchain |
| Babashka | deb package | Task runner (`bb`) |
| Node.js 22 + pnpm | Nodesource + npm | Site build |
| PostgreSQL | apt | Database (lab admission) |
| nginx | apt | Static site + API proxy |
| PRF repo | rsync | Deployed to `/opt/prf` |
| Lab HTTP server | systemd (`myapp`) | Serves `/api/lab/*` on `127.0.0.1:8082` |
| Attestation runner | systemd (`prf-attestation`) | Builds + signs attestation bundles |
| Static site export | pnpm build | Served by nginx on port 80 |

## Services

| Service | Port | Access |
|---|---|---|
| nginx (static site) | 80 | CloudFront + direct EIP |
| myapp (lab API) | 8082 | localhost only (proxied by nginx) |
| postgresql | 5432 | localhost only |

## Verification

```bash
# From EC2 host
systemctl status myapp prf-attestation postgresql nginx

# Test lab API through nginx
curl -H "X-PRF-Origin-Token: $LAB_ORIGIN_TOKEN" http://localhost/api/lab/health

# Test static site
curl http://localhost/

# Test database
sudo -u postgres psql -c "SELECT 1;" -d prf_lab
```

## CloudFront configuration

Ensure your CloudFront distribution has:

1. **Default behavior** → S3 bucket (unchanged)
2. **/api/lab/* behavior** → Origin: EC2 EIP, Port 80
   - Origin header: `X-PRF-Origin-Token: <your-lab_origin_token>`
   - Viewer protocol policy: HTTPS only
   - Allowed HTTP methods: GET, HEAD, OPTIONS, POST

## Cost

- EC2 t4g.small: ~$4/month
- EIP: free while attached
- No additional services beyond existing S3/CloudFront
- **Total incremental: ~$4/month**

## Security notes

- `myapp` binds `127.0.0.1:8082` — never exposed directly
- nginx validates `X-PRF-Origin-Token` on all `/api/*` requests
- Security group allows port 80 only from CloudFront origin prefix list
- PostgreSQL listens on localhost only
- Lab signing keys are stored under `/secure/operator-keys/` with `0600` permissions
