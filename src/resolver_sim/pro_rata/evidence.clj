(ns resolver-sim.pro-rata.evidence
  "Hash-bound, domain-neutral evidence envelopes for complete pro-rata results.

   The envelope does not select accounts or interpret shortfall. Domain artifacts
   may reference it, but remain responsible for their own propagation and state
   semantics."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]))

(def ^:private schema-version "pro-rata-mechanism-evidence.v1")

(defn- validation-results
  [result]
  [{:claim/id :pro-rata/result-hash-valid
    :holds? (allocation/allocation-hash-valid? result)}
   {:claim/id :pro-rata/cap-respecting
    :holds? (empty? (invariants/cap-respecting-violations result))}
   {:claim/id :pro-rata/quota-bounded
    :holds? (empty? (invariants/quota-bounded-violations result))}
   {:claim/id :pro-rata/round-trace-coherent
    :holds? (empty? (invariants/round-trace-violations result))}
   {:claim/id :pro-rata/residual-valid
    :holds? (empty? (invariants/residual-violations result))}
   {:claim/id :pro-rata/canonical-remainder-assignment
    :status (if (= :largest-remainder (:rounding-policy result)) :exercised :not-exercised)
    :holds? (or (not= :largest-remainder (:rounding-policy result))
                (empty? (invariants/canonical-remainder-assignment-violations result)))}])

(defn mechanism-evidence-artifact
  "Construct a complete, hash-committed mechanism evidence envelope.

   The allocation result is embedded so a later domain-specific propagation can
   bind to one immutable mathematical witness without reconstructing selected
   witness fields. `:mechanism/validation-results` records only local structural
   validation summaries; claim-engine evidence remains a separate integration
   concern."
  [result]
  (let [base {:schema-version schema-version
              :evidence/id [:pro-rata-mechanism-evidence
                            (:allocation/id result)
                            (:allocation/hash result)]
              :mechanism (:mechanism result)
              :mechanism/result result
              :mechanism/result-hash (:allocation/hash result)
              :mechanism/validation-results (validation-results result)}]
    (assoc base :evidence/hash
           (hc/hash-with-intent {:hash/intent :projection-artifact} base))))

(defn evidence-reference
  "Return the compact reference domain artifacts persist in addition to their
   authoritative decision reference."
  [artifact]
  {:schema-version "pro-rata-mechanism-evidence-reference.v1"
   :evidence/id (:evidence/id artifact)
   :evidence/hash (:evidence/hash artifact)
   :mechanism (:mechanism artifact)
   :allocation/id (get-in artifact [:mechanism/result :allocation/id])
   :allocation/hash (get-in artifact [:mechanism/result :allocation/hash])})

(defn- reconstruction-violations
  "Rebuild the result from its committed request rather than trusting stored
   allocations, rounds, or validation verdicts."
  [result]
  (try
    (let [request (:canonical-request result)]
      (cond
        (nil? request)
        [{:reason :pro-rata/missing-canonical-request}]

        :else
        (let [reconstructed (allocation/allocate request)]
          (when-not (= reconstructed result)
            [{:reason :pro-rata/allocation-reconstruction-mismatch
              :expected-hash (:allocation/hash reconstructed)
              :observed-hash (:allocation/hash result)}]))))
    (catch Exception error
      [{:reason :pro-rata/allocation-reconstruction-failed
        :message (.getMessage error)
        :class (.getName (class error))}])))

(defn evidence-violations
  "Independently validate a complete persisted envelope. In addition to schema
   and hash checks, this reconstructs the allocation from its canonical request
   and compares the full semantic witness."
  [artifact]
  (let [result (:mechanism/result artifact)
        expected-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                           (dissoc artifact :evidence/hash))]
    (vec
     (concat
      (when-not (= schema-version (:schema-version artifact))
        [{:reason :pro-rata/unsupported-mechanism-evidence-schema
          :observed (:schema-version artifact)}])
      (when-not (= (:evidence/hash artifact) expected-hash)
        [{:reason :pro-rata/mechanism-evidence-hash-mismatch
          :expected expected-hash :observed (:evidence/hash artifact)}])
      (when-not (= (:mechanism/result-hash artifact) (:allocation/hash result))
        [{:reason :pro-rata/mechanism-evidence-result-hash-mismatch
          :expected (:allocation/hash result)
          :observed (:mechanism/result-hash artifact)}])
      (when-not (= (:mechanism artifact) (:mechanism result))
        [{:reason :pro-rata/mechanism-evidence-mechanism-mismatch
          :expected (:mechanism result) :observed (:mechanism artifact)}])
      (invariants/result-violations result)
      (reconstruction-violations result)))))

;; ── Proposed effect plan ───────────────────────────────────────────────────

(def proposed-effects-schema-version "pro-rata-proposed-effects.v1")

(defn- derived-effect-rows
  "The sole effect projection admitted by this generic layer. Each allocated
   unit becomes a proposed obligation settlement effect; domain adapters decide
   how that effect is applied to concrete custody/state."
  [allocation-result]
  (mapv (fn [row]
          (let [row-id (:row/id row)
                obligation-id (:obligation/id row)]
            {:effect/id [:pro-rata-allocation-effect
                         (:allocation/id allocation-result) row-id]
             :row/id row-id
             :obligation/id obligation-id
             :amount (:allocated row)}))
        (:rows allocation-result)))

(defn proposed-effects
  "Builds a hash-bound, replay-verifiable proposed effect plan from a complete
   pro-rata allocation witness. The caller cannot independently choose amounts
   or obligation identities: both are derived from the committed allocation
   rows. This is a proposal, not an application receipt."
  [allocation-result]
  (when-not (and (allocation/allocation-hash-valid? allocation-result)
                 (empty? (invariants/result-violations allocation-result)))
    (throw (ex-info "cannot derive proposed effects from an invalid pro-rata allocation"
                    {:allocation/id (:allocation/id allocation-result)})))
  (let [base {:schema-version proposed-effects-schema-version
              :allocation/id (:allocation/id allocation-result)
              :allocation/hash (:allocation/hash allocation-result)
              :effects (derived-effect-rows allocation-result)}]
    (assoc base :proposed-effects/root
           (hc/domain-hash :pro-rata-proposed-effects base))))

(defn proposed-effects-violations
  "Returns proof failures for a proposed pro-rata effect plan. Verification
   reconstructs the expected effect rows from the supplied allocation witness,
   so a plan cannot alter an amount, add/remove a row, or redirect an
   obligation while retaining a valid root."
  [allocation-result proposal]
  (let [base (dissoc proposal :proposed-effects/root)
        expected-effects (derived-effect-rows allocation-result)
        expected-root (hc/domain-hash :pro-rata-proposed-effects base)]
    (vec
     (concat
      (when-not (= proposed-effects-schema-version (:schema-version proposal))
        [{:reason :pro-rata/unsupported-proposed-effects-schema
          :observed (:schema-version proposal)}])
      (when-not (allocation/allocation-hash-valid? allocation-result)
        [{:reason :pro-rata/invalid-allocation-witness}])
      (when-not (= (:allocation/id proposal) (:allocation/id allocation-result))
        [{:reason :pro-rata/proposed-effects-allocation-id-mismatch}])
      (when-not (= (:allocation/hash proposal) (:allocation/hash allocation-result))
        [{:reason :pro-rata/proposed-effects-allocation-hash-mismatch}])
      (when-not (= (:effects proposal) expected-effects)
        [{:reason :pro-rata/proposed-effects-not-derived-from-allocation
          :expected expected-effects :observed (:effects proposal)}])
      (when-not (= (:proposed-effects/root proposal) expected-root)
        [{:reason :pro-rata/proposed-effects-root-mismatch
          :expected expected-root :observed (:proposed-effects/root proposal)}])))))

(defn proposed-effects-valid?
  "True when a proposed effect plan is exactly derivable from its allocation
   witness and its own typed commitment."
  [allocation-result proposal]
  (empty? (proposed-effects-violations allocation-result proposal)))
