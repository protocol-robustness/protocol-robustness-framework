#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ $# -ne 1 ]]; then
  printf 'usage: %s OUTPUT_DIRECTORY (outside the repository)\n' "$0" >&2
  exit 2
fi

mkdir -p "$1"
out_dir=$(cd "$1" && pwd)
case "$out_dir" in
  "$repo_root"|"$repo_root"/*)
    printf 'output directory must be outside the repository: %s\n' "$out_dir" >&2
    exit 2
    ;;
esac

for name in accounting-reductions prf-research-contracts prf-incentives; do
  for suffix in .tar.gz .content-manifest .content-root .archive-sha256; do
    if [[ -e "$out_dir/$name$suffix" ]]; then
      printf 'refusing to overwrite existing export: %s\n' "$out_dir/$name$suffix" >&2
      exit 2
    fi
  done
done

stage=$(mktemp -d "${TMPDIR:-/tmp}/prf-package-candidate-exports.XXXXXX")
trap 'rm -rf "$stage"' EXIT

namespace_path() {
  local namespace=$1
  local relative=${namespace//./\/}
  relative=${relative//-/_}.clj
  printf '%s' "$relative"
}

copy_namespace_closure() {
  local manifest=$1 destination=$2 namespace relative source
  local namespaces
  namespaces=$(PRF_EXPORT_MANIFEST="$manifest" clojure -M:test -e '
    (require (quote clojure.edn))
    (let [m (clojure.edn/read-string
             (slurp (System/getenv "PRF_EXPORT_MANIFEST")))]
      (doseq [n (:current-transitive-source-closure m)] (println n)))')
  while IFS= read -r namespace; do
    [[ -n "$namespace" ]] || continue
    relative=$(namespace_path "$namespace")
    if [[ "$namespace" == prf.* ]]; then
      continue # canonical namespace is supplied by the pinned Git dependency
    else
      source="$repo_root/src/$relative"
    fi
    if [[ ! -f "$source" ]]; then
      printf 'missing source for %s: %s\n' "$namespace" "$source" >&2
      exit 1
    fi
    mkdir -p "$destination/src/$(dirname "$relative")"
    cp "$source" "$destination/src/$relative"
  done <<< "$namespaces"
}

make_export() {
  local name=$1 bundle=$2
  local manifest="$stage/$name.content-manifest"
  (
    cd "$bundle"
    find . -type f -print0 | sort -z | xargs -0 sha256sum
  ) > "$manifest"
  sha256sum "$manifest" | awk '{print $1}' > "$out_dir/$name.content-root"
  cp "$manifest" "$out_dir/$name.content-manifest"
  tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
    -cf - -C "$stage" "$name" | gzip -n -9 > "$out_dir/$name.tar.gz"
  sha256sum "$out_dir/$name.tar.gz" | awk '{print $1}' \
    > "$out_dir/$name.archive-sha256"
  printf '%s content-root=sha256:%s archive-sha256=%s\n' \
    "$name" "$(cat "$out_dir/$name.content-root")" \
    "$(cat "$out_dir/$name.archive-sha256")"
}

accounting="$stage/accounting-reductions"
mkdir -p "$accounting/packages/accounting-reductions"
cp -R "$repo_root/packages/accounting-reductions/clj" \
      "$accounting/packages/accounting-reductions/clj"
cp "$repo_root/packages/accounting-reductions/README.md" \
   "$accounting/packages/accounting-reductions/README.md"
cp "$repo_root/packages/accounting-reductions/RELEASE.edn" \
   "$accounting/packages/accounting-reductions/RELEASE.edn"
make_export accounting-reductions "$accounting"

research="$stage/prf-research-contracts"
mkdir -p "$research/resources/config" \
         "$research/test/resolver_sim/research" \
         "$research/test/resolver_sim/benchmark"
cat > "$research/deps.edn" <<'EOF'
{:paths ["src" "test" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        prf/canonical {:git/url "https://github.com/protocol-robustness/prf-canonical.git"
                       :git/sha "5e2b520feca812b049ba397b7b3681963c66a48c"
                       :deps/root "clj"}}}
EOF
copy_namespace_closure "$repo_root/packages/prf-research-contracts/DEPENDENCIES.edn" "$research"
cp "$repo_root/config/defaults.edn" "$research/resources/config/defaults.edn"
cp "$repo_root/test/resolver_sim/research/research_contract_api_test.clj" \
   "$research/test/resolver_sim/research/"
cp "$repo_root/test/resolver_sim/benchmark/research_claim_acceptance_test.clj" \
   "$research/test/resolver_sim/benchmark/"
cp "$repo_root/test/resolver_sim/benchmark/research_claim_lifecycle_test.clj" \
   "$research/test/resolver_sim/benchmark/"
cp "$repo_root/packages/prf-research-contracts/DEPENDENCIES.edn" "$research/"
cp "$repo_root/packages/prf-research-contracts/README.md" "$research/"
make_export prf-research-contracts "$research"

incentives="$stage/prf-incentives"
mkdir -p "$incentives/resources/config" \
         "$incentives/test/resolver_sim/research" \
         "$incentives/test/resolver_sim/benchmark"
cat > "$incentives/deps.edn" <<'EOF'
{:paths ["src" "test" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        prf/canonical {:git/url "https://github.com/protocol-robustness/prf-canonical.git"
                       :git/sha "5e2b520feca812b049ba397b7b3681963c66a48c"
                       :deps/root "clj"}}}
EOF
copy_namespace_closure "$repo_root/packages/prf-incentives/DEPENDENCIES.edn" "$incentives"
cp "$repo_root/config/defaults.edn" "$incentives/resources/config/defaults.edn"
cp "$repo_root/test/resolver_sim/research/research_api_boundary_test.clj" \
   "$incentives/test/resolver_sim/research/"
cp "$repo_root/test/resolver_sim/benchmark/researcher_reward_test.clj" \
   "$incentives/test/resolver_sim/benchmark/"
cp "$repo_root/packages/prf-incentives/DEPENDENCIES.edn" "$incentives/"
cp "$repo_root/packages/prf-incentives/README.md" "$incentives/"
make_export prf-incentives "$incentives"
printf 'exports=%s\n' "$out_dir"
