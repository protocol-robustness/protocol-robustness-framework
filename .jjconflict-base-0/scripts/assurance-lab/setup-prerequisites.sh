#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=== Phase 1: Install prerequisites on EC2 host ==="

if ! command -v sudo >/dev/null 2>&1; then
  echo "ERROR: sudo not found. Run as root or install sudo first." >&2
  exit 1
fi

echo "--- Updating package index ---"
sudo apt-get update -qq

echo "--- Installing base packages ---"
sudo apt-get install -y -qq \
  openjdk-21-jdk \
  git \
  python3 \
  python3-pip \
  awscli \
  coreutils \
  curl \
  wget \
  unzip \
  ca-certificates

echo "--- Verifying Java ---"
java -version 2>&1 | head -3
javac -version 2>&1 | head -1

echo "--- Installing Clojure CLI ---"
if ! command -v clojure >/dev/null 2>&1; then
  curl -fsSL https://download.clojure.org/install/linux-install-1.11.1.1413.sh -o /tmp/install-clojure.sh
  chmod +x /tmp/install-clojure.sh
  sudo /tmp/install-clojure.sh
  rm -f /tmp/install-clojure.sh
else
  echo "Clojure CLI already installed: $(clojure -Sdescribe 2>/dev/null | grep -m1 'clojure version' || true)"
fi

echo "--- Installing Babashka ---"
if ! command -v bb >/dev/null 2>&1; then
  sudo rm -f /tmp/babashka.deb
  wget -q https://github.com/babashka/babashka/releases/download/v1.3.190/babashka-1.3.190-linux-arm64.deb -O /tmp/babashka.deb
  sudo dpkg -i /tmp/babashka.deb
  rm -f /tmp/babashka.deb
else
  echo "Babashka already installed: $(bb --version 2>/dev/null | head -1 || true)"
fi

echo "--- Verifying toolchain ---"
echo "java:  $(java -version 2>&1 | head -1)"
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found after installation — Java is required for JAR smoke tests." >&2
  exit 1
fi
echo "clojure: $(clojure -Sdescribe 2>/dev/null | grep -m1 'clojure version' || echo 'unknown')"
echo "bb:    $(bb --version 2>/dev/null | head -1 || echo 'unknown')"
echo "aws:   $(aws --version 2>/dev/null | head -1 || echo 'not configured')"
echo "sha256sum: $(sha256sum --version 2>/dev/null | head -1)"

echo ""
echo "Prerequisites installed successfully."
