(ns resolver-sim.yield.pro-rata-propagation-helpers
  "Shared semantic-case generator and world builder for pro-rata propagation
   property tests.

   A test case is a compact domain representation:
     {:source-balance 1000
      :participants [{:participant-id \"alice\" :eligible-obligation 500
                      :fulfilled 400 :deferred 100}
                     ...]
      :token-id :USDC}

   The builder constructs a valid world from a case using the same patterns
   as the hand-crafted accounting-world in pro-rata-accounting-test, so
   check-pro-rata-accounting-reconciles passes."
  (:require [clojure.test.check.generators :as gen]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]))

;; ---------------------------------------------------------------------------
;; Generator utilities
;; ---------------------------------------------------------------------------

(def participant-id-pool
  ["alice" "bob" "carol" "dave" "eve" "fay" "gabe" "hugh"])

(defn- make-participant
  [id eligible fulfilled]
  (let [eligible (max 1 (long eligible))
        fulfilled (min (max 1 (long fulfilled)) eligible)
        deferred (- eligible fulfilled)]
    {:participant-id id
     :eligible-obligation eligible
     :fulfilled fulfilled
     :deferred deferred
     :unmet 0 :waived 0
     :obligation-after deferred
     :origin {:obligation-id (keyword (str "obl-" id))
              :participant-id id
              :sequence 1}}))

(defn- gen-unique-vector
  "Generate a vector of n participants with unique IDs from the pool.
   Each participant gets an independent generated amount."
  [gen-fn n]
  (let [ids (vec (take n participant-id-pool))]
    (gen/fmap
     (fn [amounts]
       (mapv (fn [id amount] (gen-fn id amount)) ids amounts))
     (gen/vector (gen/choose 1 200) n))))

(def gen-case
  "Generate a valid semantic case with unique participant IDs.
   source-balance is always >= total-allocated."
  (gen/bind
   (gen/choose 1 6)
   (fn [n]
     (let [ids (vec (take n participant-id-pool))]
       (gen/fmap
        (fn [amounts]
          (let [participants
                (mapv (fn [id amount]
                        (let [eligible (max 1 (long amount))]
                          (make-participant id eligible eligible)))
                      ids amounts)
                total (reduce + 0 (map :fulfilled participants))
                source-balance (max 1 total)]
            {:token-id :USDC
             :source-balance source-balance
             :participants participants}))
        (gen/vector (gen/choose 1 200) n))))))

(def gen-case-with-deferred
  "Generate a valid case where some participants may have deferred amounts.
   Each participant's fulfillment ratio is randomly chosen."
  (gen/bind
   (gen/choose 1 5)
   (fn [n]
     (let [ids (vec (take n participant-id-pool))]
       (gen/fmap
        (fn [amt-ratio-pairs]
          (let [participants
                (mapv (fn [[id [eligible ratio]]]
                        (let [eligible (max 2 (long eligible))
                              fulfilled (max 1 (long (* eligible ratio)))]
                          (make-participant id eligible fulfilled)))
                      (map vector ids amt-ratio-pairs))
                total-fulfilled (reduce + 0 (map :fulfilled participants))
                source-balance (max 1 total-fulfilled)]
            {:token-id :USDC
             :source-balance source-balance
             :participants participants}))
        (gen/vector (gen/tuple (gen/choose 2 200)
                               (gen/double* {:min 0.1 :max 0.95})) n))))))

(def gen-any-case
  "Mix of fully-fulfilled and deferred cases."
  (gen/frequency [[3 gen-case]
                  [2 gen-case-with-deferred]]))

;; ---------------------------------------------------------------------------
;; World builder
;; ---------------------------------------------------------------------------

(defn normalize-participant
  "Canonical participant entry for propagation artifacts."
  [p]
  (let [fulfilled (long (:fulfilled p 0))
        deferred (long (:deferred p 0))
        eligible (long (:eligible-obligation p fulfilled))]
    {:participant-id (:participant-id p)
     :eligible-obligation eligible
     :fulfilled fulfilled
     :deferred deferred
     :unmet 0 :waived 0
     :obligation-after deferred
     :origin (:origin p
                      {:obligation-id (keyword (str "obl-" (:participant-id p)))
                       :participant-id (:participant-id p)
                       :sequence 1})}))

(defn build-propagations-from-case
  "Construct yield/pro-rata-propagations and yield/applied-pro-rata-propagations
   maps from a semantic case, following the same structure as accounting-world."
  [{:keys [token-id source-balance participants]
    :or {token-id :USDC}}]
  (let [norm-ps (mapv normalize-participant participants)
        total-allocated (reduce + 0 (map :fulfilled norm-ps))
        residual (- source-balance total-allocated)
        policy (propagation-policy/normalize-and-validate
                propagation-policy/shared-withdrawal-policy)
        policy-hash (:policy/hash policy)
        calc-id "prop-test-c1"
        outcome-hash "prop-test-o1"
        id-key [:pro-rata-propagation calc-id outcome-hash policy-hash]

        accounting-entries
        (vec (concat
              [{:entry/type :debit :account :shared-liquidity
                :token token-id :delta (- total-allocated)}]
              (mapv (fn [p]
                      {:entry/type :credit :account :withdrawn
                       :token token-id
                       :participant-id (:participant-id p)
                       :obligation-id (get-in p [:origin :obligation-id])
                       :delta (:fulfilled p)})
                    norm-ps)))

        entry-hash (pf/accounting-entry-set-hash accounting-entries)

        propagation {:propagation/id "p1"
                     :calculation-ref calc-id
                     :outcome-ref outcome-hash
                     :token token-id
                     :propagation/hash outcome-hash
                     :propagation/content-hash entry-hash
                     :propagation-policy (propagation-policy/policy-reference policy)
                     :summary {:available source-balance
                               :allocated total-allocated
                               :unallocated-residual residual}
                     :residual {:destination :remain-in-shared-liquidity}
                     :participants norm-ps
                     :accounting-entries accounting-entries
                     :accounting-entry-set-hash entry-hash}

        app {:schema-version "pro-rata-propagation-application.v3"
             :propagation-id "p1"
             :propagation/reference {:propagation/id "p1"
                                     :propagation/hash outcome-hash
                                     :propagation/content-hash entry-hash}
             :calculation-id calc-id
             :outcome-hash outcome-hash
             :policy-hash policy-hash
             :application-key id-key
             :application-order {:schema-version "pro-rata-application-order.v2"
                                 :step 1 :event-id 0}
             :accounting-entry-set-hash entry-hash
             :source-account {:account :shared-liquidity
                              :token token-id
                              :before source-balance
                              :delta (- total-allocated)
                              :after residual}
             :residual {:token token-id
                        :available source-balance
                        :allocated total-allocated
                        :amount residual
                        :destination :remain-in-shared-liquidity}
             :participants
             (mapv (fn [p]
                     (let [pid (:participant-id p)
                           fulfilled (:fulfilled p 0)
                           oid (get-in p [:origin :obligation-id])]
                       {:participant-id pid
                        :obligation-id oid
                        :withdrawn {:token token-id
                                    :before 0
                                    :delta fulfilled
                                    :after fulfilled}
                        :obligation {:before (:eligible-obligation p)}
                        :cumulative-fulfilled {:before 0
                                               :delta fulfilled
                                               :after fulfilled}}))
                   norm-ps)}
        app (assoc app :application/hash (pf/application-hash app))

        withdrawn-map
        (into {} (map (fn [p] [(:participant-id p) (:fulfilled p 0)]) norm-ps))

        positions
        (into {}
              (map (fn [p]
                     (let [pid (:participant-id p)
                           deferred (:deferred p 0)
                           oid (get-in p [:origin :obligation-id])]
                       [pid
                        (cond-> {:token token-id
                                 :status (if (pos? deferred) :partially-deferred :withdrawn)}
                          (pos? deferred)
                          (assoc :deferred-position
                                 {:position/current-amount deferred
                                  :position/root-obligation-id oid
                                  :position/origin-propagation-id "p1"
                                  :position/round 1
                                  :position/type :deferred-withdrawal
                                  :position/eligibility :later-liquidity}))]))
                   norm-ps))]
    {:yield/pro-rata-propagations {"p1" propagation}
     :yield/applied-pro-rata-propagations {"p1" app}
     :total-held {token-id residual}
     :yield/withdrawn {token-id withdrawn-map}
     :yield/positions positions}))

;; ---------------------------------------------------------------------------
;; Multi-propagation chain builder

(defn build-two-propagation-world
  "Build a world with two sequential propagations.
   The first consumes from source-balance-1, the second draws from its residual.
   Returns world with both propagations and applications, or nil if impossible."
  [case1 case2]
  (let [w1 (build-propagations-from-case case1)
        tok2 (:token-id case2 :USDC)
        p1 (get-in w1 [:yield/pro-rata-propagations "p1"])
        residual1 (get-in p1 [:summary :unallocated-residual] 0)
        alloc2 (reduce + 0 (map :fulfilled (:participants case2 0)))]
    (when (<= alloc2 residual1)
      (let [norm-ps2 (mapv normalize-participant (:participants case2))
            total-allocated2 (reduce + 0 (map :fulfilled norm-ps2))
            residual2 (- residual1 total-allocated2)
            policy (propagation-policy/normalize-and-validate
                    propagation-policy/shared-withdrawal-policy)
            policy-hash (:policy/hash policy)
            calc-id2 "prop-test-c2"
            outcome-hash2 "prop-test-o2"
            id-key2 [:pro-rata-propagation calc-id2 outcome-hash2 policy-hash]
            accounting-entries2
            (vec (concat
                  [{:entry/type :debit :account :shared-liquidity
                    :token tok2 :delta (- total-allocated2)}]
                  (mapv (fn [p]
                          {:entry/type :credit :account :withdrawn
                           :token tok2
                           :participant-id (:participant-id p)
                           :obligation-id (get-in p [:origin :obligation-id])
                           :delta (:fulfilled p)})
                        norm-ps2)))
            entry-hash2 (pf/accounting-entry-set-hash accounting-entries2)
            propagation2 {:propagation/id "p2"
                          :calculation-ref calc-id2
                          :outcome-ref outcome-hash2
                          :token tok2
                          :propagation/hash outcome-hash2
                          :propagation/content-hash entry-hash2
                          :propagation-policy (propagation-policy/policy-reference policy)
                          :summary {:available residual1
                                    :allocated total-allocated2
                                    :unallocated-residual residual2}
                          :residual {:destination :remain-in-shared-liquidity}
                          :participants norm-ps2
                          :accounting-entries accounting-entries2
                          :accounting-entry-set-hash entry-hash2}
            app2 {:schema-version "pro-rata-propagation-application.v3"
                  :propagation-id "p2"
                  :propagation/reference {:propagation/id "p2"
                                          :propagation/hash outcome-hash2
                                          :propagation/content-hash entry-hash2}
                  :calculation-id calc-id2
                  :outcome-hash outcome-hash2
                  :policy-hash policy-hash
                  :application-key id-key2
                  :application-order {:schema-version "pro-rata-application-order.v2"
                                      :step 2 :event-id 0}
                  :accounting-entry-set-hash entry-hash2
                  :source-account {:account :shared-liquidity
                                   :token tok2
                                   :before residual1
                                   :delta (- total-allocated2)
                                   :after residual2}
                  :residual {:token tok2
                             :available residual1
                             :allocated total-allocated2
                             :amount residual2
                             :destination :remain-in-shared-liquidity}
                  :participants
                  (mapv (fn [p]
                          (let [pid (:participant-id p)
                                fulfilled (:fulfilled p 0)
                                oid (get-in p [:origin :obligation-id])]
                            {:participant-id pid
                             :obligation-id oid
                             :withdrawn {:token tok2
                                         :before (get-in w1 [:yield/withdrawn tok2 pid] 0)
                                         :delta fulfilled
                                         :after (+ (get-in w1 [:yield/withdrawn tok2 pid] 0)
                                                   fulfilled)}
                             :obligation {:before (:eligible-obligation p)}
                             :cumulative-fulfilled {:before 0
                                                    :delta fulfilled
                                                    :after fulfilled}}))
                        norm-ps2)}
            app2 (assoc app2 :application/hash (pf/application-hash app2))
            updated-withdrawn
            (reduce (fn [m p]
                      (let [pid (:participant-id p)
                            amt (:fulfilled p 0)]
                        (update-in m [tok2 pid] (fnil + 0) amt)))
                    (:yield/withdrawn w1)
                    norm-ps2)
            positions2
            (into {}
                  (map (fn [p]
                         (let [pid (:participant-id p)
                               deferred (:deferred p 0)
                               oid (get-in p [:origin :obligation-id])]
                           [pid
                            (cond-> {:token tok2
                                     :status (if (pos? deferred) :partially-deferred :withdrawn)}
                              (pos? deferred)
                              (assoc :deferred-position
                                     {:position/current-amount deferred
                                      :position/root-obligation-id oid
                                      :position/origin-propagation-id "p2"
                                      :position/round 1
                                      :position/type :deferred-withdrawal
                                      :position/eligibility :later-liquidity}))]))
                       norm-ps2))]
        {:yield/pro-rata-propagations (merge (:yield/pro-rata-propagations w1) {"p2" propagation2})
         :yield/applied-pro-rata-propagations (merge (:yield/applied-pro-rata-propagations w1) {"p2" app2})
         :total-held (merge (:total-held w1) {tok2 residual2})
         :yield/withdrawn updated-withdrawn
         :yield/positions (merge (:yield/positions w1) positions2)}))))

(defn- add-excess-balance
  "Add random excess to source-balance so the case has residual
   available for a second propagation."
  [case]
  (let [excess (rand-int 500)]
    (update case :source-balance + excess)))

(def gen-two-case-chain
  "Generate two cases where the second fits within the first's residual.
   The first case has excess source balance to leave room for the second.
   Uses disjoint participant IDs and :USDT token for the second case."
  (gen/bind
   (gen/tuple gen-case gen-case)
   (fn [[c1 c2]]
     (let [c1 (add-excess-balance c1)
           alloc1 (reduce + 0 (map :fulfilled (:participants c1)))
           residual1 (- (:source-balance c1) alloc1)
           c1-ids (set (map :participant-id (:participants c1)))
           pool (vec (remove c1-ids participant-id-pool))
           n (count (:participants c2))
           new-ids (take n (concat pool (map #(str "ext-" %) (range 20))))
           scale-factor (if (pos? residual1)
                          (/ residual1 (max 1 (reduce + 0 (map :fulfilled (:participants c2)))))
                          1.0)]
       (if (>= scale-factor 1.0)
          ;; c2 fits as-is — just remap IDs; keep same token for source-chain continuity
         (gen/return
          [c1
           (-> c2
               (update :participants
                       (fn [ps]
                         (mapv (fn [p new-id]
                                 (assoc p :participant-id new-id
                                        :origin (assoc (:origin p)
                                                       :obligation-id
                                                       (keyword (str "obl-" new-id)))))
                               ps new-ids))))])
           ;; Scale c2 to fit within residual1
          (let [per-p (long (/ residual1 n))
                rem (mod residual1 n)]
           (gen/return
            [c1
             (-> c2
                 (update :participants
                         (fn [ps]
                           (mapv (fn [p idx new-id]
                                   (let [eligible (max 1 (long (:eligible-obligation p 0)))
                                         f (max 1 (min eligible
                                                       (if (< idx rem) (inc per-p) per-p)))]
                                     (make-participant new-id eligible f)))
                                 ps (range) new-ids))))])))))))
;; Independent test oracle
;; ---------------------------------------------------------------------------

(defn expected-participant-ids
  "Set of participant-ids that should appear in the propagation."
  [case]
  (set (map :participant-id (:participants case))))

(defn expected-total-allocated
  "Sum of fulfilled amounts — should match summary.allocated."
  [case]
  (reduce + 0 (map :fulfilled (:participants case))))

(defn expected-total-deferred
  "Sum of deferred amounts."
  [case]
  (reduce + 0 (map :deferred (:participants case))))

(defn expected-source-balance-after
  "Source balance after propagation: before minus allocated."
  [case]
  (- (:source-balance case 0)
     (expected-total-allocated case)))

(defn expected-entry-count
  "Number of accounting entries: 1 debit + N credits."
  [case]
  (inc (count (:participants case))))
