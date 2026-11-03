#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

clojure -Srepro -M:test -e "(require 'clojure.test 'resolver-sim.privacy.policy-test 'resolver-sim.privacy.initialization-test 'resolver-sim.privacy.resolution-test 'resolver-sim.commands.privacy-test) (let [r (clojure.test/run-tests 'resolver-sim.privacy.policy-test 'resolver-sim.privacy.initialization-test 'resolver-sim.privacy.resolution-test 'resolver-sim.commands.privacy-test)] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))"
clojure -Srepro -M:test -e "(require 'resolver-sim.commands.registry-validate) (let [r (resolver-sim.commands.registry-validate/validate {})] (when (pos? (:exit-code r)) (System/exit 1)))"
clojure -Srepro -M:test scripts/concepts_validate.clj

tmp_dir="$(mktemp -d)"
repeat_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir" "$repeat_dir"' EXIT
clojure -Srepro -M:test -e "(load-file \"examples/use-cases/stablecoin-privacy/fixtures.clj\") (stablecoin-privacy-fixtures/-main \"$tmp_dir\")"
clojure -Srepro -M:test -e "(load-file \"examples/use-cases/stablecoin-privacy/fixtures.clj\") (stablecoin-privacy-fixtures/-main \"$repeat_dir\")"
diff -r "$tmp_dir" "$repeat_dir"

clojure -Srepro -M:cli privacy policy inspect --input "$tmp_dir/policy.edn"
clojure -Srepro -M:cli privacy evaluate --input "$tmp_dir/allow-evaluate.edn"
clojure -Srepro -M:cli privacy verify --input "$tmp_dir/allow.edn"
clojure -Srepro -M:cli privacy verify --input "$tmp_dir/deny.edn"

set +e
clojure -Srepro -M:cli privacy evaluate --input "$tmp_dir/deny-evaluate.edn"
deny_exit=$?
set -e
if [[ "$deny_exit" -ne 1 ]]; then
  printf 'expected denied privacy evaluation to exit 1, got %s\n' "$deny_exit" >&2
  exit 1
fi
