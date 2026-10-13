#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/prf-accounting-package.XXXXXX")
trap 'rm -rf "$probe_dir"' EXIT

cd "$repo_root"
mkdir -p "$probe_dir/packages/accounting-reductions" \
         "$probe_dir/packages/canonical"
cp -R packages/accounting-reductions/clj \
      "$probe_dir/packages/accounting-reductions/clj"
cp -R packages/canonical/clj "$probe_dir/packages/canonical/clj"

content_hash=$(cd "$probe_dir" && find packages/accounting-reductions/clj packages/canonical/clj \
  -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
printf 'isolated-accounting-package-content-set=sha256:%s\n' "$content_hash"

cd "$probe_dir/packages/accounting-reductions/clj"
clojure -Srepro -M:test
