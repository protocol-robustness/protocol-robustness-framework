(ns resolver-sim.benchmark.packs.partial-fill.outcome
  "Partial-fill outcome normalisation for the benchmark outcome manifest.
   
   Produces the :results/operational and :results/incentives sections
   that enter the shared benchmark outcome hash.
   
   All functions are projection-only — no state mutation or IO.")

;; ── Operational result evaluation ─────────────────────────────────────────

(defn evaluate-operational
  "Evaluate operational results from partial-fill decisions and
   state write-back evidence.
   
   Returns a map of dimension -> :pass | :fail | :inconclusive."
  [partial-fill-decisions state-write-back-evidence]
  (let [decisions (vals partial-fill-decisions)]
    {:conservation
     (if (every? (fn [d]
                   (let [r (:requested d {})
                         f (:filled d {})
                         df (:deferred d {})
                         h (:haircut d {})
                         total (+ (reduce + 0 (vals f))
                                  (reduce + 0 (vals df))
                                  (reduce + 0 (vals h)))]
                     (= (reduce + 0 (vals r)) total)))
                 decisions)
       :pass :fail)

     :quota-bounded
     (if (every? (fn [d]
                   (let [rows (get-in d [:evidence :allocation-rows] [])]
                     (every? (fn [row]
                               (let [filled (long (:filled row 0))
                                     owed (long (:owed row 0))
                                     cap (long (:effective-cap row owed))]
                                 (<= filled (min owed cap))))
                             rows)))
                 decisions)
       :pass :fail)

     :current-amount-write-back
     (if (and (seq state-write-back-evidence)
              (every? (fn [wb] (true? (:verified? wb)))
                      state-write-back-evidence))
       :pass
       (if (seq state-write-back-evidence) :fail :inconclusive))

     :authoritative-application
     (let [wb-seq (seq state-write-back-evidence)]
       (cond
         (nil? wb-seq) :inconclusive
         (every? (fn [wb]
                   (and (true? (get-in wb [:withdrawn :verified?]))
                        (true? (get-in wb [:position :verified?]))))
                 wb-seq) :pass
         :else :fail))}))

;; ── Incentive result evaluation ───────────────────────────────────────────

(defn evaluate-incentives
  "Evaluate incentive-relevant properties from partial-fill decisions.
   Returns a map of property -> boolean."
  [partial-fill-decisions]
  (let [decisions (vals partial-fill-decisions)]
    {:request-splitting-profitable?
     (some (fn [d]
             (some #(= :request-splitting
                       (:strategy-tag %))
                   (get-in d [:evidence :allocation-rows] [])))
           decisions)

     :ordering-manipulation-profitable?
     (some (fn [d]
             (let [rows (get-in d [:evidence :allocation-rows] [])
                   row-ids (map :key rows)]
               (not= row-ids (sort row-ids))))
           decisions)

     :shortfall-remains-accounted?
     (every? (fn [d]
               (let [deferred (vals (:deferred d {}))]
                 (every? (fn [v] (>= v 0)) deferred)))
             decisions)}))

;; ── Normalised scenario-level outcome ─────────────────────────────────────

(defn normalise-participant-outcome
  "Produce a deterministic, researcher-independent representation
   of a single participant's partial-fill outcome.
   
   Uses clear field names that do not collide with the existing
   :allocation-applied (zero? deferred) semantics."
  [participant-map]
  (let [fulfilled (long (:fulfilled participant-map 0))
        deferred (long (:deferred participant-map 0))
        haircut (long (:haircut participant-map 0))
        obligation (long (or (:obligation-before participant-map) 0))]
    {:participant/id (:participant-id participant-map)
     :obligation/before (:obligation-before participant-map)
     :obligation/fulfilled fulfilled
     :obligation/deferred deferred
     :obligation/haircut haircut
     :obligation/after (:obligation-after participant-map)
     ;; Amount applied now, never a complete-settlement classification.
     :allocation/positive-amount-applied? (pos? fulfilled)
     ;; Preserve the formerly overloaded weaker fact explicitly.
     :allocation/no-deferred-residual? (zero? deferred)
     ;; Complete satisfaction requires the entire obligation, no deferred
     ;; residual, and no permanent haircut.
     :allocation/fully-satisfied? (and (= fulfilled obligation)
                                       (zero? deferred)
                                       (zero? haircut))
     :allocation/applied? true}))

(defn normalise-decision-outcome
  "Produce a deterministic, researcher-independent representation
   of one partial-fill decision."
  [decision]
  (let [participants (get-in decision [:evidence :allocation-rows] [])]
    {:decision/id (:decision/id decision)
     :decision/hash (:decision/hash decision)
     :settlement-mode (:settlement-mode decision)
     :requested (:requested decision)
     :filled (:filled decision)
     :deferred (:deferred decision)
     :participants (mapv (fn [p]
                           (let [key (:key p)
                                 owed (long (:owed p 0))
                                 filled (long (:filled p 0))
                                 haircut (long (:haircut p 0))
                                 deferred (max 0 (- owed filled haircut))]
                             {:participant/id key
                              :obligation/before owed
                              :obligation/fulfilled filled
                              :obligation/deferred deferred
                              :obligation/haircut haircut
                              :allocation/positive-amount-applied? (pos? filled)
                              :allocation/no-deferred-residual? (zero? deferred)
                              :allocation/fully-satisfied? (and (= filled owed)
                                                                (zero? deferred)
                                                                (zero? haircut))
                              :allocation/applied? true}))
                         participants)
     :evidence/available-liquidity (get-in decision [:evidence :available-liquidity])
     :evidence/shortage (get-in decision [:evidence :shortage])}))
