(ns resolver-sim.allocation.kernel
  "Public PRF reference allocation kernel.

   The kernel is the deterministic reference computation that the independent
   Rust kernel must reproduce byte-for-byte at the declared public-output
   boundary. It:

     1. validates the allocation context;
     2. recomputes all committed roots;
     3. derives the rate summary;
     4. evaluates the ordered 14 assertions;
     5. deterministically selects the outcome;
     6. constructs the selected result;
     7. computes the Merkle result root;
     8. computes total allocated and residual capacity;
     9. derives the round-lifecycle projection from the observed round-state;
    10. computes the certificate assertions digest (CERTIFICATE_ASSERTIONS_V2);
    11. returns stable public values.

   Failing vectors produce a stable rejection classification and structured
   reason rather than relying on exception-message text."
  (:require [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.proposal :as proposal]
            [resolver-sim.allocation.reconciliation :as reconciliation]
            [resolver-sim.allocation.roots :as roots]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.allocation.selection :as selection]))

(def lifecycle-decision-opts
  "Cancellation decision profile used for the kernel's round-lifecycle
   projection: the canonical three-member profile under its named allocation
   policy id (mirrors `resolver-sim.allocation.certificate`)."
  {:profile-id "alloc/2-3"})

(def assertion-ids
  "The ordered 14-assertion contract. The order is part of the compatibility
   contract between PRF and the Rust kernel."
  [:allocation.assertion/claimant-set-root-valid
   :allocation.assertion/outcome-set-root-valid
   :allocation.assertion/proposed-rates-root-valid
   :allocation.assertion/rates-canonical-exact
   :allocation.assertion/rates-sum-to-one
   :allocation.assertion/outcomes-eligible-only
   :allocation.assertion/outcomes-no-duplicate-claims
   :allocation.assertion/outcomes-all-or-nothing
   :allocation.assertion/outcomes-exact-capacity
   :allocation.assertion/proportional-proposed
   :allocation.assertion/randomness-selection-valid
   :allocation.assertion/selected-outcome-membership
   :allocation.assertion/result-root-valid
   :allocation.assertion/result-capacity-reconciles])

(def ^:private assertion-classification
  "Stable rejection classification per failing assertion id."
  {:allocation.assertion/claimant-set-root-valid :claimant-set-root-mismatch
   :allocation.assertion/outcome-set-root-valid :outcome-set-root-mismatch
   :allocation.assertion/proposed-rates-root-valid :proposed-rates-root-mismatch
   :allocation.assertion/rates-canonical-exact :rates-not-canonical
   :allocation.assertion/rates-sum-to-one :rates-not-sum-to-one
   :allocation.assertion/outcomes-eligible-only :ineligible-claimant
   :allocation.assertion/outcomes-no-duplicate-claims :duplicate-claim-in-outcome
   :allocation.assertion/outcomes-all-or-nothing :allocation-not-all-or-nothing
   :allocation.assertion/outcomes-exact-capacity :outcome-not-exact-capacity
   :allocation.assertion/proportional-proposed :proportionality-failure
   :allocation.assertion/randomness-selection-valid :randomness-selection-invalid
   :allocation.assertion/selected-outcome-membership :selected-outcome-mismatch
   :allocation.assertion/result-root-valid :result-root-mismatch
   :allocation.assertion/result-capacity-reconciles :result-capacity-mismatch})

(defn parse-committed
  "Parse the optional committed-roots block of a vector/input document.
   Returns a map of the committed projection, or nil when absent."
  [input]
  (when-let [committed (get input "committed")]
    (when-not (map? committed)
      (context/rejection! :malformed-committed "committed must be an object"))
    (let [hex-field (fn [field]
                      (let [v (get committed field)]
                        (when (some? v)
                          (when-not (and (string? v) (re-matches #"^0x[0-9a-f]{64}$" v))
                            (context/rejection! :malformed-committed
                                                (str field " must be 0x-prefixed 32-byte hex: "
                                                     (pr-str v))))
                          v)))
          index (get committed "selected-outcome-index")]
      (when (and (some? index)
                 (not (integer? index))
                 (not (re-matches #"^[0-9]+$" (str index))))
        (context/rejection! :malformed-committed "selected-outcome-index must be a non-negative integer"))
      {:claimant-set-root (hex-field "claimant-set-root")
       :outcome-set-root (hex-field "outcome-set-root")
       :proposed-rates-root (hex-field "proposed-rates-root")
       :result-root (hex-field "result-root")
       :selected-outcome-id (get committed "selected-outcome-id")
       :selected-outcome-index (when (some? index) (bigint (if (integer? index) index (bigint (str index)))))})))

(defn- run-assertions
  "Evaluate the ordered 14 assertions against the context and the committed
   projection. Returns a vector of {:assertion/id <kw> :assertion/result bool}
   in contract order. Failing reconciliation carries an :assertion/reason so
   the rejection classification is specific, not a generic capacity mismatch."
  [context committed roots-result leaves selected-outcome rounding-policy]
  (let [normalize (fn [s] (if (and (string? s) (re-matches #"^[0-9a-f]{64}$" s))
                            (str "0x" s)
                            s))
        committed-root (fn [k] (get committed k))
        roots-eq (fn [k] (or (nil? (committed-root k))
                             (= (normalize (get roots-result k)) (normalize (committed-root k)))))
        claim-root-ok (roots-eq :claimant-set-root)
        outcome-root-ok (roots-eq :outcome-set-root)
        rates-root-ok (roots-eq :proposed-rates-root)
        selection-receipt (:selection-receipt roots-result)
        result-root-ok (roots-eq :result-root)
        committed-sel-id (get committed :selected-outcome-id)
        committed-sel-index (get committed :selected-outcome-index)
        sel-id (:selected-outcome-id roots-result)
        sel-index (:selected-outcome-index roots-result)
        membership-ok (and sel-id
                           (some #(= sel-id (:outcome/id %)) (:outcomes context))
                           (or (nil? committed-sel-id) (= sel-id committed-sel-id))
                           (or (nil? committed-sel-index) (= sel-index committed-sel-index)))
        selection-ok (and (pos? (:outcome-count selection-receipt))
                          (some? (:selected-index selection-receipt)))
        capacity-reconcile
        (reconciliation/reconcile
         {:context context
          :selected-outcome selected-outcome
          :leaves leaves
          :total-allocated (:total-allocated roots-result)
          :residual-capacity (:residual-capacity roots-result)
          :committed-result-root (get committed :result-root)
          :rounding-policy rounding-policy})]    (mapv (fn [assertion-id]
                                                         (let [res (case assertion-id
                                                                     :allocation.assertion/claimant-set-root-valid claim-root-ok
                                                                     :allocation.assertion/outcome-set-root-valid outcome-root-ok
                                                                     :allocation.assertion/proposed-rates-root-valid rates-root-ok
                                                                     :allocation.assertion/rates-canonical-exact (proposal/rates-canonical-exact? context)
                                                                     :allocation.assertion/rates-sum-to-one (proposal/rates-sum-to-one? (:proposed-rates context))
                                                                     :allocation.assertion/outcomes-eligible-only (proposal/outcomes-eligible-only? context)
                                                                     :allocation.assertion/outcomes-no-duplicate-claims (proposal/outcomes-no-duplicate-claims? context)
                                                                     :allocation.assertion/outcomes-all-or-nothing (proposal/outcomes-all-or-nothing? context)
                                                                     :allocation.assertion/outcomes-exact-capacity (proposal/outcomes-exact-capacity? context)
                                                                     :allocation.assertion/proportional-proposed (proposal/proportional-proposed? context)
                                                                     :allocation.assertion/randomness-selection-valid selection-ok
                                                                     :allocation.assertion/selected-outcome-membership membership-ok
                                                                     :allocation.assertion/result-root-valid result-root-ok
                                                                     :allocation.assertion/result-capacity-reconciles (:ok? capacity-reconcile)
                                                                     false)]
                                                           (cond-> {:assertion/id assertion-id :assertion/result res}
                                                             (and (= assertion-id :allocation.assertion/result-capacity-reconciles)
                                                                  (not res))
                                                             (assoc :assertion/reason (:reason capacity-reconcile)))))
                                                       assertion-ids)))

(defn- first-failing-classification
  "Map the ordered assertion results to the first failing assertion's stable
   classification, or nil when all pass. A failing reconciliation carries a
   specific :assertion/reason (e.g. :result-award-mismatch) that overrides the
   generic per-assertion classification map."
  [assertions]
  (when-let [failing (first (filter (comp false? :assertion/result) assertions))]
    (or (:assertion/reason failing)
        (get assertion-classification (:assertion/id failing) :assertion-failed))))

(defn result-leaves
  "Construct the result Merkle leaves for the selected outcome in canonical
   claimant ordering."
  [context selected-outcome context-hash]
  (let [alloc-by-claim (into {} (map (juxt :claim/id :allocated))
                             (:allocations selected-outcome))]
    (mapv (fn [claimant]
            (let [cid (:claim/id claimant)
                  final (bigint (get alloc-by-claim cid 0))]
              {:claim/id cid
               :beneficiary (:economic-owner-id claimant)
               :input-amount (:amount claimant)
               :input-weight (:weight claimant)
               :final-allocation final
               :selected-outcome-id (:outcome/id selected-outcome)
               :result-status (if (pos? final)
                                "allocated"
                                "not-allocated")
               :context-hash context-hash}))
          (:claimants context))))

(defn run-kernel
  "Run the reference kernel against a parsed JSON input document.

   Returns a map with stable public values. Never throws for malformed or
   non-passing inputs: it returns a structured rejection instead."
  [input]
  (try
    (let [context (context/build-context input)
          committed (parse-committed input)
          round-state-token (get input "round-state")
          lifecycle (round-state/round-lifecycle lifecycle-decision-opts round-state-token)
          ctx-hash (context/context-hash context)
          claim-root (roots/claimant-set-root context)
          outcome-root (roots/outcome-set-root context)
          rates-root (roots/proposed-rates-root context)
          summary-hash (proposal/rate-derived-summary-hash context)
          outcome-count (count (:outcomes context))
          sel (selection/select-index (:authoritative-randomness context) outcome-count)
          selected-outcome (nth (:outcomes context) (long (:selected-index sel)))
          leaves (result-leaves context selected-outcome ctx-hash)
          result-root (roots/result-merkle-root leaves)
          total-allocated (reduce + 0 (map :final-allocation leaves))
          residual (- (bigint (:capacity context)) total-allocated)
          roots-result {:claimant-set-root claim-root
                        :outcome-set-root outcome-root
                        :proposed-rates-root rates-root
                        :rate-derived-summary-hash summary-hash
                        :selection-receipt {:algorithm context/selection-algorithm
                                            :outcome-count outcome-count
                                            :accepted-counter (:accepted-counter sel)
                                            :candidate-digest (:candidate-digest sel)
                                            :selected-index (:selected-index sel)
                                            :selected-outcome-id (:outcome/id selected-outcome)
                                            :selected-outcome-hash (roots/selected-outcome-hash selected-outcome)}
                        :selected-outcome-id (:outcome/id selected-outcome)
                        :selected-outcome-index (:selected-index sel)
                        :selected-outcome-hash (roots/selected-outcome-hash selected-outcome)
                        :result-root result-root
                        :total-allocated total-allocated
                        :residual-capacity residual}
          assertions (run-assertions context committed roots-result leaves selected-outcome
                                     (get input "rounding-policy"))
          all-pass? (every? :assertion/result assertions)
          digest-input {:allocation-context-hash ctx-hash
                        :assertions (vec assertions)
                        :selected-outcome-id (:selected-outcome-id roots-result)
                        :selected-outcome-index (:selected-outcome-index roots-result)
                        :result-root result-root
                        :total-allocated total-allocated
                        :residual-capacity residual
                        :allocation-kernel-version context/kernel-version
                        :round-state (:round-state lifecycle)
                        :derived-state (:derived-state lifecycle)
                        :lifecycle-profile-id (:lifecycle-profile-id lifecycle)
                        :lifecycle-profile-version (:lifecycle-profile-version lifecycle)
                        :cancellation-window-schema (:cancellation-window-schema lifecycle)
                        :cancellation-window (:cancellation-window lifecycle)
                        :cancellation-possible (:cancellation-possible lifecycle)
                        :cancellation-blocking-reasons (:cancellation-blocking-reasons lifecycle)
                        :lifecycle-assertion-status (:lifecycle-assertion-status lifecycle)
                        :lifecycle-assurance (:assurance lifecycle)}
          digest (roots/certificate-assertions-digest-v2 digest-input)]
      (merge
       {:result/status (if all-pass? :passing :rejected)
        :allocation-context-hash ctx-hash
        :claimant-set-root claim-root
        :outcome-set-root outcome-root
        :proposed-rates-root rates-root
        :rate-derived-summary-hash summary-hash
        :assertions (vec assertions)
        :selection-receipt (:selection-receipt roots-result)
        :selected-outcome-id (:selected-outcome-id roots-result)
        :selected-outcome-index (:selected-outcome-index roots-result)
        :selected-outcome-hash (:selected-outcome-hash roots-result)
        :result-root result-root
        :total-allocated total-allocated
        :residual-capacity residual
        :round-lifecycle lifecycle
        :certificate-assertions-digest digest
        :allocation-kernel-version context/kernel-version
         :selection-algorithm context/selection-algorithm-str}
       (when-not all-pass?
         {:rejection/classification (first-failing-classification assertions)
          :rejection/reason "One or more kernel assertions failed"})))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        {:result/status :rejected
         :rejection/classification (or (:rejection/classification data) :malformed-input)
         :rejection/reason (or (:rejection/reason data) (.getMessage e))}))))
