#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       PRF Assurance Lab — Phase 1 Manual Validation         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Run these steps on the EC2 host (t4g.small, eu-north-1a)."
echo ""

echo "=== Pre-flight: SSH access and repo sync ==="
echo "1. terraform.tfvars: operator_ssh_cidrs = [\"<your-ip>/32\"]"
echo "2. terraform plan && terraform apply"
echo "3. git clone or rsync the PRF repo to /opt/prf/"
echo "4. ssh -i <key> ubuntu@<eip>"
echo ""

echo "=== Step 1: Install prerequisites ==="
echo "  sudo bash $SCRIPT_DIR/setup-prerequisites.sh"
echo ""

echo "=== Step 2: Generate test signing keys ==="
echo "  sudo bash $SCRIPT_DIR/generate-test-keys.sh /secure/operator-keys"
echo ""

echo "=== Step 3: Create active trust policy ==="
echo "  sudo bash $SCRIPT_DIR/create-active-policy.sh /opt/prf /secure/operator-policy/trust-policy.edn /secure/operator-keys/release-signing-key.pub"
echo ""

echo "=== Step 4: Run the attestation loop ==="
echo "  sudo bash $SCRIPT_DIR/run-attestation-loop.sh /opt/prf target prf /secure/operator-policy/trust-policy.edn /secure/operator-keys/release-signing-key '{:release/id \"2026.08.11\" :release/channel :stable}'"
echo ""

echo "=== Post-flight: close SSH ==="
echo "1. terraform.tfvars: remove your IP from operator_ssh_cidrs"
echo "2. terraform apply"
echo ""

echo "Scripts are located in: $SCRIPT_DIR"
