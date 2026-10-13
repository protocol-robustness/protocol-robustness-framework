#!/usr/bin/env python3
"""Regenerate prototype cross-language vectors using PRF's authoritative encoder."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
DEST = Path(__file__).resolve().parents[1] / "fixtures"
PROGRAM = r'''
(require '[resolver-sim.hash.canonical :as h] :reload)
(require '[resolver-sim.registry.observation :as observation] :reload)
(let [hashref (fn [c] (str "sha256:" (apply str (repeat 64 c))))
      address (fn [n] (str "0x" (format "%040x" n)))
      ref (fn [t c] {:artifact/type t :artifact/root (hashref c)})
      route {:route :selector :selector "0x12345678" :definition-ref nil}
      envelope {:artifact/schema "AssuredOperationEnvelope.v1"
                :chain-instance/root (hashref "1")
                :deployment {:release-ref (ref :prf/protocol-registry-release.v1 "2")
                             :candidate-ref (ref :prf/registry-binding-candidate.v1 "3")
                             :binding-id :binding/target :scope-root (hashref "4")}
                :target/address (address 4097)
                :target/code-bindings [{:role :runtime/target :address (address 4097)
                                       :runtime-code-hash (str "keccak256:" (apply str (repeat 64 "5")))
                                       :deployment-ref (ref :prf/registry-binding-candidate.v1 "3")}]
                :execution/profile-ref (ref "ao-dispatcher-profile.prototype.v1" "6")
                :operation route :calldata/hash (hashref "7") :native/value 0
                :caller {:mode :principal :principal (address 12291) :authority-ref nil}
                :replay {:namespace-ref (ref "ao-replay-namespace.prototype.v1" "8") :nonce 123}
                :validity {:not-before-block nil :not-after-block nil} :domain-context-ref nil}
      registration {:artifact/schema "ProtectedOperationRegistration.v1"
                    :chain-instance/root (hashref "1")
                    :deployment-ref (ref :prf/registry-binding-candidate.v1 "3")
                    :target/address (address 4097)
                    :execution/profile-ref (ref "ao-dispatcher-profile.prototype.v1" "6")
                    :operation route :revision 0 :parent-ref nil :status :protected
                    :assurance-policy-ref (ref "treasury-withdrawal-policy.prototype.v1" "9")
                    :control-ref (ref "ao-registration-control.prototype.v1" "a")}
      result {:target/address (address 4097) :balance 1000 :revision 1}
      policy {:artifact/schema "treasury-withdrawal-policy.prototype.v1"
              :signer/address (address 12291) :maximum-amount 100 :minimum-post-balance 10}
      maximum (dec (.shiftLeft java.math.BigInteger/ONE 256))]
  (h/validate-prefix-free-domain-tags!
    (into (set (vals h/domain-tags))
      ["prf.assured-operation-envelope.v1" "prf.protected-operation-registration.v1"
       "ao.prototype.registration-control.v1" "ao.prototype.replay-namespace.v1"
       "ao.prototype.dispatcher-profile.v1" "ao.prototype.treasury-policy.v1"
       "ao.prototype.treasury-projection-definition.v1" "ao.prototype.treasury-withdrawal-permission.v1"]))
  (prn {:envelope envelope :registration registration :result result :policy policy})
  (println (h/domain-hash "prf.assured-operation-envelope.v1" envelope))
  (println (h/domain-hash "prf.protected-operation-registration.v1" registration))
  (println (observation/result-root result))
  (println (h/domain-hash "ao.prototype.treasury-policy.v1" policy))
  (println (h/canonical-bytes-hex maximum)))
'''

def main():
    lines = subprocess.check_output(
        ["bb", "--classpath", "src", "-e", PROGRAM], cwd=ROOT, text=True
    ).splitlines()
    if len(lines) != 6:
        raise RuntimeError(f"Expected 6 encoder output lines, got {len(lines)}")
    DEST.mkdir(exist_ok=True)
    (DEST / "canonical-bodies.edn").write_text(lines[0] + "\n")
    fields = ["envelope", "registration", "result", "policy", "uint256MaximumBytes"]
    (DEST / "canonical-golden.json").write_text(
        json.dumps(dict(zip(fields, ("0x" + x for x in lines[1:]))), indent=2) + "\n"
    )
    print("Wrote four PRF canonical roots and the 257-bit ZigZag boundary vector.")

if __name__ == "__main__":
    main()
