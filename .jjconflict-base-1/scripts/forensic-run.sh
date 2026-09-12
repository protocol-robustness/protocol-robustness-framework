#!/usr/bin/env bash
# Forensic run wrapper: captures pre-run commitment, runs scenario suite,
# signs results.  Works with bb test:reference, bb test:parity, or
# direct clojure -M:runner/sew invocation.
#
# Usage:
#   ./scripts/forensic-run.sh --suite sew-reference-v1
#   ./scripts/forensic-run.sh --scenario scenario.trace.json
set -euo pipefail
RUN_ID="run-$(date -u +%Y%m%dT%H%M%S)"
RESULTS_DIR="results/runs/$RUN_ID"
mkdir -p "$RESULTS_DIR"

echo "=== Forensic Run: $RUN_ID ==="

# 1. Source hash
echo -n "  Source hash... "
SOURCE_HASH=$(clojure -M -e "(require 'resolver-sim.forensic.source-hash) (println (pr-str (resolver-sim.forensic.source-hash/source-hash)))" 2>/dev/null)
echo "OK"

# 2. Deps hash  
echo -n "  Deps hash... "
DEPS_HASH=$(clojure -M:runner/sew -e "(require 'resolver-sim.forensic.deps-hash) (println (pr-str (resolver-sim.forensic.deps-hash/deps-hash)))" 2>/dev/null)
echo "OK"

# 3. Build pre-run commitment as EDN and write to results dir
{
  echo "{:pre-run/schema-version \"pre-run-commitment.v1\""
  echo " :pre-run/run-id \"$RUN_ID\""
  echo " :pre-run/suite-key $(echo "$*" | grep -oP '(?<=--suite )\S+' || echo nil)"
  echo " :pre-run/generated-at \"$(date -u -Iseconds)\""
  echo " :pre-run/source $SOURCE_HASH"
  echo " :pre-run/deps $(echo "$DEPS_HASH" | head -1)"
  echo " :pre-run/runner-binary nil"
  echo " :pre-run/config-hash \"$(sha256sum config/evidence.json 2>/dev/null | cut -d' ' -f1 || echo '')\""
  echo " :pre-run/commitment-hash nil}"
} | cat > "$RESULTS_DIR/pre-run-commitment.edn"

# Compute commitment hash (SHA-256 of the serialized map minus the hash field)
COMMIT_HASH=$(sha256sum "$RESULTS_DIR/pre-run-commitment.edn" | cut -d' ' -f1)
sed -i "s/:pre-run/commitment-hash nil/:pre-run/commitment-hash \"$COMMIT_HASH\"/" "$RESULTS_DIR/pre-run-commitment.edn"
echo "  Commitment hash: $COMMIT_HASH"

# 4. Run the actual scenario suite
echo "=== Execution ==="
clojure -M:runner/sew -m resolver-sim.minimal-runner "$@"
EXIT_CODE=$?

# 5. Sign pre-run commitment if signing key available
if [ -n "${PRF_SIGNING_KEY:-}" ] || [ -f signing-key.pem ]; then
  KEY="${PRF_SIGNING_KEY:-signing-key.pem}"
  echo -n "  Signing commitment... "
  clojure -M:runner/sew -e "
    (require 'resolver-sim.forensic.signing)
    (let [data (clojure.data.json/read-str (slurp \"$RESULTS_DIR/pre-run-commitment.edn\") :key-fn keyword)
          result (resolver-sim.forensic.signing/sign-and-write! \"$RESULTS_DIR/pre-run-commitment.edn\" data \"$KEY\")]
      (println (:sig-path result)))" 2>/dev/null
  echo "OK"
fi

# 6. Write execution overview
echo "{\"run-id\": \"$RUN_ID\", \"exit-code\": $EXIT_CODE, \"suite\": $(echo "$*" | grep -oP '(?<=--suite )\S+' || echo null)}" \
  > "$RESULTS_DIR/run-overview.json"

echo "=== Done (exit: $EXIT_CODE) ==="
exit $EXIT_CODE
