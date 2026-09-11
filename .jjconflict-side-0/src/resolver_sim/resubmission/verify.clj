(ns resolver-sim.resubmission.verify
  "Resubmission validation stages (§15 of the design contract).

   Stages:
     1. pure artifact validation      link schema/hash/signature mechanics
                                     (resolver-sim.evidence.artifact/verify-artifact)
     2. bundle binding                current registry/results/envelope binding
     3. historical authority          researcher + validator policy/key state
     4. historical receipt            parent receipt + dispositions
     5. mutable chain admission       head check + CAS (resolver-sim.resubmission.chain)

   This namespace implements stages 1-4 as pure/deterministic checks and the
   composed acceptance report; stage 5 is the chain store's atomic admit!.

   A valid resubmission link must not upgrade an invalid certificate, and a
   valid certificate must not imply a valid resubmission link: the current
   gates (result-capacity-reconciles, valid-certificate, results-artifact) are
   always independently recomputed and passed in as `gates`."
  (:require [resolver-sim.evidence.artifact :as artifact]
            [resolver-sim.resubmission.derive-kind :as derive]
            [resolver-sim.resubmission.disposition :as disposition]
            [resolver-sim.resubmission.link :as link]
            [resolver-sim.resubmission.receipt :as receipt]))

(defn validate-link-artifact
  "Stage 1: pure artifact + link signature + hash. Returns
   {:valid? bool :reason kw :details {}}."
  [link researcher-public-hex]
  (let [r (link/verify-link-signature link researcher-public-hex)]
    (if-not (:valid? r)
      {:valid? false :reason (:reason r) :details {:signature (:detail r)}}
      (let [v (artifact/verify-artifact link link/link-schema link/link-kind link/link-verifier)]
        (if (:valid? v)
          {:valid? true :reason :ok :details {}}
          {:valid? false :reason (:reason v)
           :details {:stage :content-integrity :reason (:reason v)}})))))

(defn validate-bundle-binding
  "Stage 2: the link's current block must match the acceptance validator's
   independently recomputed binding for the current run.

   `current-binding`: {:run-id str :results-artifact-hash str :results-root str}
   Compared against the link's :resubmission/current block. Returns
   {:valid? bool :reason kw :details {}}."
  [link current-binding]
  (let [cur (:resubmission/current link)]
    (cond
      (not= (:run-id current-binding) (:run-id cur))
      {:valid? false :reason :current-run-id-mismatch
       :details {:declared (:run-id cur) :verified (:run-id current-binding)}}

      (not= (:results-artifact-hash current-binding) (:results-artifact-hash cur))
      {:valid? false :reason :current-results-hash-mismatch
       :details {:declared (:results-artifact-hash cur)
                 :verified (:results-artifact-hash current-binding)}}

      (not= (:results-root current-binding)
            (get-in cur [:results :root/hash]))
      {:valid? false :reason :current-results-root-mismatch
       :details {:declared (get-in cur [:results :root/hash])
                 :verified (:results-root current-binding)}}

      :else {:valid? true :reason :ok :details {}})))

(defn validate-researcher-authority
  "Stage 3a: researcher authorization against the pinned policy snapshot.

   `researcher-store` is a lookup fn (policy-hash key-id) -> 
   {:public-hex str :status kw :valid-at-cutpoint bool} | nil."
  [link researcher-store]
  (let [res (:resubmission/researcher link)
        policy-hash (:policy/hash res)
        key-id (:key/id res)
        entry (researcher-store policy-hash key-id)]
    (cond
      (nil? entry)
      {:valid? false :reason :researcher-not-authorised
       :details {:policy-hash policy-hash :key-id key-id}}

      (not= :active (:status entry))
      {:valid? false :reason :key-revoked :details {:status (:status entry)}}

      (false? (:valid-at-cutpoint entry))
      {:valid? false :reason :key-not-valid-at-cutpoint :details {}}

      :else
      {:valid? true :reason :ok
       :details {:public-hex (:public-hex entry)}})))

(defn- parent-from-receipt
  "Project the receipt's status-bearing roots and rejection classification into
   the shape derive-kind expects. The rejection classification is derived from
   the blocking findings' reasons (the receipt has no single rejection field)."
  [parent-receipt]
  (let [roots (get parent-receipt :attempt-receipt/roots)
        findings (:attempt-receipt/findings parent-receipt)
        semantic (first (filter derive/semantic-rejection-classifications
                                (map :reason findings)))]
    {:roots (into {}
                  (map (fn [[k v]] [k {:status (:status v) :hash (:hash v)}]))
                  roots)
     :rejection-classification semantic}))

(defn validate-derived-kind
  "Stage 3b: the declared kind must match the root-comparison derivation.
   `parent-receipt` carries the status-bearing roots; `current` carries the
   current root hashes; `declared-kind` is :resubmission/kind."
  [parent-receipt declared-kind current]
  (let [derived (:kind (derive/derive-kind (parent-from-receipt parent-receipt) current))]
    (cond
      (and (= :submission-repair declared-kind)
           (= :verified (get-in parent-receipt [:attempt-receipt/roots :results :status])))
      {:valid? false :reason :submission-repair-not-permitted
       :details {:parent-results :verified}}

      (derive/declared-kind-consistent? derived declared-kind)
      {:valid? true :reason :ok :details {:derived derived}}

      :else
      {:valid? false
       :reason (or (derive/kind-mismatch-reason derived declared-kind)
                   :declared-kind-mismatch)
       :details {:derived derived :declared declared-kind}})))

(defn validate-remediation
  "Stage 3c: every blocking finding of the parent receipt must be accounted for.
   Returns {:valid? bool :missing [finding-ids] :details {}}."
  [parent-receipt link]
  (let [blocking (set (map :finding/id
                           (filter :blocking?
                                   (:attempt-receipt/findings parent-receipt))))
        accounted (set (map :finding-id (:resubmission/remediation link)))
        missing (vec (sort (remove accounted blocking)))]
    (if (seq missing)
      {:valid? false :reason :rejection-finding-unaccounted :missing missing}
      {:valid? true :reason :ok :details {:blocking-count (count blocking)}})))

(defn validate-parent-receipt
  "Stage 4: parent attempt receipt validity (shape + signature) and direct
   resubmission eligibility, with lifecycle resolved from immutable dispositions.

   `dispositions`: ordered seq (most-recent-first) of attempt-disposition maps.
   `disposition-verify-fn`: (fn disposition) -> {:valid? bool}.
   `validator-public-hex`: the acceptance validator's raw public key hex."
  [parent-receipt validator-public-hex dispositions disposition-verify-fn]
  (let [sig (receipt/verify-receipt-signature parent-receipt validator-public-hex)]
    (cond
      (not (receipt/valid-receipt-shape? parent-receipt))
      {:valid? false :reason :malformed-parent-receipt :details {}}

      (not (:valid? sig))
      {:valid? false :reason (:reason sig) :details {:signature (:detail sig)}}

      :else
      (let [attempt-receipt-hash (:attempt-receipt/id parent-receipt)
            effective (disposition/effective-lifecycle-status dispositions
                                                              attempt-receipt-hash
                                                              disposition-verify-fn)]
        (cond
          (nil? effective)
          {:valid? false :reason :invalid-disposition-chain :details {}}

          (not= :active effective)
          {:valid? false
           :reason (if (= :superseded effective)
                     :parent-rejection-superseded
                     (if (= :withdrawn effective)
                       :parent-attempt-withdrawn
                       :parent-rejection-not-final))
           :details {:effective-lifecycle effective}}

          (not= :rejected (:attempt-receipt/outcome parent-receipt))
          {:valid? false :reason :parent-not-rejected :details {}}

          (not= :final (:attempt-receipt/finality parent-receipt))
          {:valid? false :reason :parent-rejection-not-final :details {}}

          (not= :eligible (:attempt-receipt/resubmission-eligibility parent-receipt))
          {:valid? false :reason :parent-not-resubmittable :details {}}

          :else
          {:valid? true :reason :ok :details {:receipt-hash (:attempt-receipt/id parent-receipt)}})))))

(defn resubmission-acceptance-report
  "Compose the final acceptance report separating valid lineage from successful
   correction.

   inputs:
     :link-valid?         stage-1 result bool
     :bundle-binding      stage-2 result
     :authority           stage-3a result
     :derived-kind        stage-3b result
     :remediation         stage-3c result
     :parent              stage-4 result
     :previous-blocking-findings [finding-ids]
     :current-gate-results {:result-capacity-reconciles kw
                            :valid-certificate kw
                            :results-artifact kw}

   Returns the composed report map."
  [{:keys [link-artifact bundle-binding authority derived-kind remediation parent
           previous-blocking-findings current-gate-results]}]
  (let [stages {:link-artifact link-artifact
                :bundle-binding bundle-binding
                :researcher-authority authority
                :derived-kind derived-kind
                :remediation remediation
                :parent-receipt parent}
        link-valid? (boolean
                     (and (:valid? link-artifact)
                          (every? :valid? (vals (dissoc stages :link-artifact)))))]
    (merge
     {:resubmission-link-valid? link-valid?
      :previous-blocking-findings (vec previous-blocking-findings)
      :current-gate-results (or current-gate-results
                                {:result-capacity-reconciles :pending
                                 :valid-certificate :pending
                                 :results-artifact :pending})}
     (into {} (map (fn [[k v]] [k {:valid? (:valid? v) :reason (:reason v)}]) stages)))))
