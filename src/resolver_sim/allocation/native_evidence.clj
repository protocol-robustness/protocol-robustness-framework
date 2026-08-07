(ns resolver-sim.allocation.native-evidence
  "Binding native Rust evidence to exact-replication classification.

   A proof-backed exact-replication classification must ONLY be emitted when the
   pinned native Rust implementation was actually executed and its public result
   was compared against the reference implementation under the exact-replication
   contract. A mock result never satisfies a proof-backed predicate.

   Classification is deterministic and fail-closed: when native evidence is
   absent, malformed, stale, bound to another result, or produced by another
   pinned implementation, the classification downgrades to something no stronger
   than the evidence actually evaluated:

     :native-exact-match         proof-backed (native executed + compared + bound)
     :pending-independent-replay no native evidence yet (default)
     :independent-replay         native evidence present but not proof-backed
                                 (other result / other pinned rust / other
                                 pinned prf / other input / comparison mismatch)
     :not-yet-evaluated          malformed or stale evidence
     :mock-native                explicit mock; never proof-backed

   Downgrade reasons distinguish the pinned-implementation identity source:
   :other-pinned-rust-implementation (rust identity mismatch) vs
   :other-pinned-prf-implementation (prf identity mismatch).

   The evidence is bound to at least: results artifact hash, input/request root,
   pinned PRF identity, pinned Rust implementation identity, conformance
   vector / run identity, comparison outcome, and verifier version.")

(def native-verifier-version "native-evidence.v1")
(def native-schema "native-evidence.v1")

(defn malformed-native-evidence?
  "True when evidence is not a map, has the wrong schema, or is not the
   exact-replication kind."
  [e]
  (or (not (map? e))
      (not= native-schema (:native-evidence/schema e))
      (not= :exact-replication (:native-evidence/kind e))))

(defn exact-replication-classification
  "Classify exact-replication status from native evidence and the reference
   binding. Returns:

     {:classification kw
      :proof-backed? bool
      :reason kw
      :evidence {...}}   (the evidence, with :native-evidence/status attached)

   `reference` binds what the native evidence MUST match:
     {:results-artifact-hash <str or nil>
      :input-root <str or nil>
      :result-root <str or nil>
      :pinned-prf {:implementation ... :version ...}
      :pinned-rust {:implementation ... :version ... :commit ...}}"
  [native-evidence reference]
  (let [ref (or reference {})
        attach (fn [reason status]
                 {:classification (case status
                                    :proof-backed :native-exact-match
                                    :mock :mock-native
                                    :not-yet-evaluated :not-yet-evaluated
                                    :pending :pending-independent-replay
                                    :downgrade :independent-replay)
                  :proof-backed? (= status :proof-backed)
                  :reason reason
                  :evidence (when (map? native-evidence)
                              (assoc native-evidence
                                     :native-evidence/status status
                                     :native-evidence/reason reason))})]
    (cond
      (nil? native-evidence)
      (attach :no-native-evidence :pending)

      (malformed-native-evidence? native-evidence)
      (attach :malformed-native-evidence :not-yet-evaluated)

      (= :mock (:native-evidence/source native-evidence))
      (attach :mock-not-proof :mock)

      (not= native-verifier-version (:native-evidence/verifier-version native-evidence))
      (attach :stale-verifier :not-yet-evaluated)

      (and (some? (:results-artifact-hash ref))
           (not= (:results-artifact-hash ref)
                 (:native-evidence/results-artifact-hash native-evidence)))
      (attach :bound-to-another-result :downgrade)

      (and (some? (:pinned-rust ref))
           (not= (:pinned-rust ref)
                 (:native-evidence/rust-identity native-evidence)))
      (attach :other-pinned-rust-implementation :downgrade)

      (and (some? (:pinned-prf ref))
           (not= (:pinned-prf ref)
                 (:native-evidence/prf-identity native-evidence)))
      (attach :other-pinned-prf-implementation :downgrade)

      (and (some? (:input-root ref))
           (not= (:input-root ref)
                 (:native-evidence/input-root native-evidence)))
      (attach :bound-to-another-input :downgrade)

      (and (some? (:result-root ref))
           (some? (:native-evidence/result-root native-evidence))
           (not= (:result-root ref)
                 (:native-evidence/result-root native-evidence)))
      (attach :bound-to-another-result :downgrade)

      (not= :match (:native-evidence/comparison native-evidence))
      (attach :comparison-mismatch :downgrade)

      :else
      (attach :ok :proof-backed))))
