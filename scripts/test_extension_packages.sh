#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

clojure -Srepro -M:test -e "(require 'clojure.test 'resolver-sim.extensions.package-boundary-test) (let [r (clojure.test/run-tests 'resolver-sim.extensions.package-boundary-test)] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))"

(cd extensions/force-authorisation && clojure -Srepro -M:test -e "(require 'clojure.test 'prf.extensions.force-authorisation.manifest-test 'prf.extensions.force-authorisation.scope-verification-test) (let [r (clojure.test/run-tests 'prf.extensions.force-authorisation.manifest-test 'prf.extensions.force-authorisation.scope-verification-test)] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))")

(cd extensions/held-custody && clojure -Srepro -M:test -e "(require 'clojure.test 'prf.extensions.held-custody.authoritative-gate-test 'prf.extensions.held-custody.authorisation-classification-test 'prf.extensions.held-custody.core-seam-test 'prf.extensions.held-custody.golden-vectors-test 'prf.extensions.held-custody.historical-read-conformance-test 'prf.extensions.held-custody.legacy-add-held-test 'prf.extensions.held-custody.manifest-test 'prf.extensions.held-custody.mutation-test 'prf.extensions.held-custody.semantic-admission-test 'prf.extensions.held-custody.aggregate-test) (let [r (apply clojure.test/run-tests ['prf.extensions.held-custody.authoritative-gate-test 'prf.extensions.held-custody.authorisation-classification-test 'prf.extensions.held-custody.core-seam-test 'prf.extensions.held-custody.golden-vectors-test 'prf.extensions.held-custody.historical-read-conformance-test 'prf.extensions.held-custody.legacy-add-held-test 'prf.extensions.held-custody.manifest-test 'prf.extensions.held-custody.mutation-test 'prf.extensions.held-custody.semantic-admission-test 'prf.extensions.held-custody.aggregate-test])] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))")

bash scripts/qualify_extension_core_api_v1.sh
