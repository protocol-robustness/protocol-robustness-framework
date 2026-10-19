#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/prf-incentives-api-closure.XXXXXX")
trap 'rm -rf "$probe_dir"' EXIT
cd "$repo_root"

while IFS= read -r namespace; do
  relative_path=$(printf '%s' "$namespace" | tr '.' '/' | tr '-' '_').clj
  if [[ "$namespace" == prf.* ]]; then
    continue # resolved from the immutable prf/canonical Git dependency
  else
    source_path="src/$relative_path"
  fi
  if [[ ! -f "$source_path" ]]; then
    printf 'missing incentive dependency source: %s (%s)\n' \
      "$namespace" "$source_path" >&2
    exit 1
  fi
  mkdir -p "$probe_dir/src/$(dirname "$relative_path")"
  cp "$source_path" "$probe_dir/src/$relative_path"
done < <(clojure -M:test -e '
  (require (quote clojure.edn))
  (let [manifest (clojure.edn/read-string
                  (slurp "packages/prf-incentives/DEPENDENCIES.edn"))]
    (doseq [namespace (:current-transitive-source-closure manifest)]
      (println namespace)))')

mkdir -p "$probe_dir/resources/config" "$probe_dir/test/resolver_sim/research" \
         "$probe_dir/test/resolver_sim/benchmark"
cp config/defaults.edn "$probe_dir/resources/config/defaults.edn"
cp test/resolver_sim/research/research_api_boundary_test.clj \
   "$probe_dir/test/resolver_sim/research/research_api_boundary_test.clj"
cp test/resolver_sim/benchmark/researcher_reward_test.clj \
   "$probe_dir/test/resolver_sim/benchmark/researcher_reward_test.clj"

content_hash=$(cd "$probe_dir" && find src resources test -type f -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
printf 'isolated-source-content-set=sha256:%s\n' "$content_hash"

cd "$probe_dir"
clojure -Srepro -Sdeps '{:paths ["src" "test" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        prf/canonical {:git/url "https://github.com/protocol-robustness/prf-canonical.git"
                       :git/sha "5e2b520feca812b049ba397b7b3681963c66a48c"
                       :deps/root "clj"}}}' -M -e '
  (require (quote clojure.test)
           (quote resolver-sim.incentives.api)
           (quote resolver-sim.research.api)
           (quote resolver-sim.research.research-api-boundary-test)
           (quote resolver-sim.benchmark.researcher-reward-test))
  (let [result (clojure.test/run-tests
                (quote resolver-sim.research.research-api-boundary-test)
                (quote resolver-sim.benchmark.researcher-reward-test))]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1)))'
