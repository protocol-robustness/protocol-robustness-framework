(ns resolver-sim.sim.trial-router
  "TrialRouter — protocol and implementations for assigning batch trials to
   individual resolvers in a multi-epoch simulation.

   The router answers the question: given N trial results and M resolvers,
   which resolver handled which trials?

   ## Conservation guarantee
   Every router implementation must satisfy — for each pool (honest / strategic):
     (sum :profit attributed to all resolvers) == (sum :profit in trial pool)
     (sum :trials attributed) == (count trial pool)
     (sum :slashed attributed) == (count (filter :slashed? trial pool))

   These are verified by `assert-conservation!` below.

   ## Routing modes

   | Mode                 | Purpose                                          |
   |----------------------|--------------------------------------------------|
   | :uniform-random      | baseline — seeded shuffle then round-robin       |
   | :capacity-weighted   | realistic load (future)                          |
   | :reputation-weighted | rich-get-richer dynamics (future)                |
   | :adversarial-routing | targeted slow-drip / ring pressure (future)      |

   Only :uniform-random is implemented in this branch. The interface is defined
   so the others can be added without changing multi_epoch.clj.

   Layering: sim/* only. No db/*, io/* imports."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.yield.exact-math :as m]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol TrialRouter
  "Assigns a pool of per-trial results to a set of resolver IDs.

   A 'pool' is a vector of trial-result maps from one strategy perspective
   (e.g. all :profit-honest values for the honest pool, or all :profit-malice
   values for the strategic pool).

   route returns {resolver-id → resolver-epoch-result} where every resolver-id
   in resolver-ids appears as a key, even resolvers that received zero trials."

  (route [router resolver-ids trial-pool rng]
    "Assign trial-pool entries to resolver-ids.

     resolver-ids — collection of resolver IDs to assign to
     trial-pool   — vector of maps, each with at minimum:
                      :profit    numeric
                      :slashed?  bool
                      :verdicts  int (count of verdicts in this trial, default 1)
                      :correct   int (count of correct verdicts, default 0)
                      :appealed  bool
                      :escalated bool
     rng          — seeded SplittableRandom; must be the sole source of randomness

     Returns {resolver-id → {:trials N :profit P :slashed S :verdicts V
                              :correct C :appealed A :escalated E}}
     where every resolver-id key is present (N=0 for resolvers with no trials).")

  (routing-mode [router]
    "Return a keyword identifying this router implementation.
     Canonical values: :uniform-random :capacity-weighted :reputation-weighted
                       :adversarial-routing"))

;; ---------------------------------------------------------------------------
;; Seeded shuffle (Fisher-Yates)
;; ---------------------------------------------------------------------------

(defn- seeded-shuffle
  "Perform a Fisher-Yates shuffle of coll using seeded rng.
   Returns a new vector. Mutates rng state (as designed)."
  [coll ^java.util.SplittableRandom rng]
  (let [arr (object-array coll)
        n   (alength arr)]
    (loop [i (dec n)]
      (when (> i 0)
        (let [j   (rng/next-int rng (inc i))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i)))))
    (vec arr)))

;; ---------------------------------------------------------------------------
;; Aggregate a list of trial results into a single resolver-epoch-result
;; ---------------------------------------------------------------------------

(defn- aggregate-trials
  "Fold a (possibly empty) vector of trial-result maps into one
   resolver-epoch-result map."
  [trial-results]
  (if (empty? trial-results)
    {:trials 0 :profit 0.0 :slashed 0 :verdicts 0 :correct 0 :appealed 0 :escalated 0}
    (reduce (fn [acc t]
              (-> acc
                  (update :trials    inc)
                  (update :profit    + (:profit t 0.0))
                  (update :slashed   + (if (:slashed? t false) 1 0))
                  (update :verdicts  + (get t :verdicts 1))
                  (update :correct   + (get t :correct 0))
                  (update :appealed  + (if (:appeal-triggered? t false) 1 0))
                  (update :escalated + (if (:escalated? t false) 1 0))))
            {:trials 0 :profit 0.0 :slashed 0 :verdicts 0 :correct 0 :appealed 0 :escalated 0}
            trial-results)))

;; ---------------------------------------------------------------------------
;; Conservation check
;; ---------------------------------------------------------------------------

(defn attribution-conserved?
  "True when attribution sums match aggregate totals from the trial pool.

   Returns {:ok? bool :violations [...]} for use in assertions and tests."
  [attribution trial-pool]
  (let [pool-profit   (reduce + 0.0 (map #(double (:profit % 0.0)) trial-pool))
        pool-trials   (count trial-pool)
        pool-slashed  (count (filter :slashed? trial-pool))

        attr-profit   (reduce + 0.0 (map #(double (:profit %)) (vals attribution)))
        attr-trials   (reduce + 0 (map :trials (vals attribution)))
        attr-slashed  (reduce + 0 (map :slashed (vals attribution)))

        eps           1e-6
        violations    (cond-> []
                        (> (Math/abs (- attr-profit pool-profit)) eps)
                        (conj {:check :profit-sum
                               :expected pool-profit :got attr-profit
                               :delta (- attr-profit pool-profit)})

                        (not= attr-trials pool-trials)
                        (conj {:check :trial-count
                               :expected pool-trials :got attr-trials})

                        (not= attr-slashed pool-slashed)
                        (conj {:check :slash-count
                               :expected pool-slashed :got attr-slashed}))]
    {:ok? (empty? violations) :violations violations}))

(defn assert-conservation!
  "Throw if attribution does not conserve the trial pool totals."
  [attribution trial-pool label]
  (let [{:keys [ok? violations]} (attribution-conserved? attribution trial-pool)]
    (when-not ok?
      (throw (ex-info (str "Attribution conservation failure: " label)
                      {:violations violations})))))

;; ---------------------------------------------------------------------------
;; UniformRandomRouter
;; ---------------------------------------------------------------------------

(deftype UniformRandomRouter []
  TrialRouter

  (route [_ resolver-ids trial-pool rng]
    (let [ids-vec (vec resolver-ids)
          n-ids   (count ids-vec)]
      (if (zero? n-ids)
        {}
        (let [;; Shuffle trials with seeded RNG then assign round-robin.
              ;; Round-robin over a shuffled list guarantees:
              ;;   - every trial appears in exactly one resolver's bucket
              ;;   - load difference is at most 1 trial between any two resolvers
              ;;   - assignment is unpredictable without knowing the RNG state
              shuffled (seeded-shuffle trial-pool rng)
              ;; Group by (trial-index mod n-resolvers)
              grouped  (reduce (fn [acc [idx trial]]
                                 (let [rid (nth ids-vec (mod idx n-ids))]
                                   (update acc rid (fnil conj []) trial)))
                               (zipmap ids-vec (repeat []))
                               (map-indexed vector shuffled))]
          ;; Aggregate each resolver's trial list into a single result
          (reduce-kv (fn [acc rid trials]
                       (assoc acc rid (aggregate-trials trials)))
                     {}
                     grouped)))))

  (routing-mode [_] :uniform-random))

(def uniform-random
  "Shared UniformRandomRouter instance — the default for multi-epoch runs."
  (UniformRandomRouter.))

;; ---------------------------------------------------------------------------
;; Weighted trial distribution helper (used by reputation + capacity routers)
;; ---------------------------------------------------------------------------

(defn- distribute-weighted
  "Assign n-trials to ids according to weights using largest-remainder method.
   Delegates rounding to resolver-sim.yield.exact-math/largest-remainder-alloc
   for exact-ratio Hare-quota allocation with deterministic tie-breaking.

   Returns {id → trial-count} where every id has a non-negative count and
   sum of counts == n-trials.

   weights — {id → non-negative numeric weight}. Zero-weight ids receive 0 trials.
   Resolvers not present in weights default to weight 0.
   If all weights are zero, falls back to uniform distribution."
  [ids weights n-trials]
  (let [ids-vec (vec ids)
        n       (count ids-vec)]
    (if (zero? n)
      {}
      (let [raw-weights (mapv #(max 0.0 (double (get weights % 0.0))) ids-vec)
            total-weight (reduce + 0.0 raw-weights)
            ;; Fall back to uniform if all weights zero (e.g. first epoch)
            eff-weights (if (pos? total-weight)
                          raw-weights
                          (vec (repeat n 1.0)))
            claims (mapv (fn [id w] {:id id :amount w}) ids-vec eff-weights)
            alloc-result (m/largest-remainder-alloc n-trials claims)]
        (zipmap ids-vec (mapv :filled (:allocations alloc-result)))))))

;; ---------------------------------------------------------------------------
;; ReputationWeightedRouter
;; ---------------------------------------------------------------------------

(deftype ReputationWeightedRouter [scores]
  ;; scores — {resolver-id → non-negative double} reputation score per resolver.
  ;; Higher score → proportionally more trials. Resolvers absent from scores
  ;; receive weight 0 (but get an empty attribution entry for conservation).
  TrialRouter

  (route [_ resolver-ids trial-pool rng]
    (let [ids-vec (vec resolver-ids)
          n-ids   (count ids-vec)]
      (if (zero? n-ids)
        {}
        (let [counts   (distribute-weighted ids-vec scores (count trial-pool))
              shuffled (seeded-shuffle trial-pool rng)
              ;; Slice the shuffled pool according to computed counts
              grouped  (loop [remaining shuffled
                              acc       {}
                              ids       ids-vec]
                         (if (empty? ids)
                           acc
                           (let [id    (first ids)
                                 n     (get counts id 0)
                                 taken (vec (take n remaining))
                                 left  (drop n remaining)]
                             (recur left
                                    (assoc acc id taken)
                                    (rest ids)))))]
          (reduce-kv (fn [acc rid trials]
                       (assoc acc rid (aggregate-trials trials)))
                     {}
                     grouped)))))

  (routing-mode [_] :reputation-weighted))

(defn make-reputation-router
  "Build a ReputationWeightedRouter from resolver-histories.

   resolver-histories — {id → resolver-state} from reputation/initialize-resolvers
                        or reputation/update-resolver-history.

   Score formula: correct-rate * 2 + 1  (minimum 1 for every active resolver).
   - Correct rate ranges [0, 1], so score ranges [1, 3].
   - New resolvers (no history) get score 1 — same as a 0%-accuracy resolver,
     ensuring they receive some trials and can build a history.
   - Strategic resolvers have correct=0 by design, so score=1 (minimum weight)."
  [resolver-histories]
  (let [scores (reduce-kv
                (fn [acc id r]
                  (let [verdicts (:total-verdicts r 0)
                        correct  (:total-correct r 0)
                        rate     (if (pos? verdicts) (double (/ correct verdicts)) 0.0)]
                    (assoc acc id (+ 1.0 (* 2.0 rate)))))
                {}
                resolver-histories)]
    (ReputationWeightedRouter. scores)))

;; ---------------------------------------------------------------------------
;; CapacityWeightedRouter
;; ---------------------------------------------------------------------------

(deftype CapacityWeightedRouter [capacities]
  ;; capacities — {resolver-id → max-trials-per-epoch} hard caps.
  ;; Resolvers absent from capacities are treated as having unlimited capacity.
  ;; Algorithm: uniform distribution, then cap and redistribute overflow iteratively.
  TrialRouter

  (route [_ resolver-ids trial-pool rng]
    (let [ids-vec (vec resolver-ids)
          n-ids   (count ids-vec)]
      (if (zero? n-ids)
        {}
        (let [n-trials (count trial-pool)
              ;; cap(id) — max trials for resolver id; Long/MAX_VALUE = uncapped
              cap      (fn [id] (get capacities id Long/MAX_VALUE))
              ;; Iteratively distribute: uniform among available resolvers, cap and repeat.
              counts   (loop [remaining n-trials
                              result    (zipmap ids-vec (repeat 0))]
                         (if (zero? remaining)
                           result
                           (let [avail (filterv #(< (get result %) (cap %)) ids-vec)
                                 n-av  (count avail)]
                             (if (zero? n-av)
                               result
                               ;; Uniform weights among available resolvers
                               (let [uniform-w  (zipmap avail (repeat 1.0))
                                     per-id     (distribute-weighted avail uniform-w remaining)
                                     ;; Apply caps: each resolver gets at most (cap - already-assigned)
                                     new-result (reduce (fn [acc id]
                                                          (update acc id +
                                                                  (min (get per-id id 0)
                                                                       (- (cap id) (get acc id 0)))))
                                                        result
                                                        avail)
                                     placed     (reduce + 0 (map #(- (get new-result %) (get result %)) avail))
                                     overflow   (- remaining placed)]
                                 (if (zero? overflow)
                                   new-result
                                   (recur overflow new-result)))))))
              shuffled (seeded-shuffle trial-pool rng)
              grouped  (loop [rem shuffled
                              acc {}
                              ids ids-vec]
                         (if (empty? ids)
                           acc
                           (let [id    (first ids)
                                 n     (get counts id 0)
                                 taken (vec (take n rem))
                                 left  (drop n rem)]
                             (recur left (assoc acc id taken) (rest ids)))))]
          (reduce-kv (fn [acc rid trials]
                       (assoc acc rid (aggregate-trials trials)))
                     {}
                     grouped)))))

  (routing-mode [_] :capacity-weighted))

(defn make-capacity-router
  "Build a CapacityWeightedRouter.

   capacities — {resolver-id → max-trials-per-epoch} integer caps.
   Resolvers absent from this map are uncapped (receive their fair share of overflow)."
  [capacities]
  (CapacityWeightedRouter. capacities))

;; ---------------------------------------------------------------------------
;; AdversarialRoutingRouter
;; ---------------------------------------------------------------------------

(deftype AdversarialRoutingRouter [target-ids pressure]
  ;; target-ids — set of resolver IDs to preferentially load (slow-drip / ring pressure)
  ;; pressure   — double in [0, 1]; fraction of trials routed to targets above fair share.
  ;;              0.0 = uniform; 1.0 = all trials to targets (if any exist)
  TrialRouter

  (route [_ resolver-ids trial-pool rng]
    (let [ids-vec  (vec resolver-ids)
          n-ids    (count ids-vec)]
      (if (zero? n-ids)
        {}
        (let [targets    (filterv #(contains? target-ids %) ids-vec)
              others     (filterv #(not (contains? target-ids %)) ids-vec)
              n-trials   (count trial-pool)
              n-targets  (count targets)
              n-others   (count others)
              ;; Route pressure * n-trials to targets, remainder to others
              n-for-targets (if (pos? n-targets)
                              (min n-trials (long (Math/round (* pressure n-trials))))
                              0)
              n-for-others  (- n-trials n-for-targets)
              shuffled (seeded-shuffle trial-pool rng)
              target-trials (vec (take n-for-targets shuffled))
              other-trials  (vec (drop n-for-targets shuffled))
              target-counts (distribute-weighted targets (zipmap targets (repeat 1.0)) n-for-targets)
              other-counts  (distribute-weighted others  (zipmap others  (repeat 1.0)) n-for-others)
              all-counts    (merge target-counts other-counts)
              grouped  (loop [remaining shuffled
                              acc       {}
                              ids       (concat targets others)
                              remaining-trials (concat target-trials other-trials)]
                         (if (empty? ids)
                           acc
                           (let [id    (first ids)
                                 n     (get all-counts id 0)
                                 taken (vec (take n remaining-trials))
                                 left  (drop n remaining-trials)]
                             (recur remaining
                                    (assoc acc id taken)
                                    (rest ids)
                                    left))))]
          (reduce-kv (fn [acc rid trials]
                       (assoc acc rid (aggregate-trials trials)))
                     {}
                     grouped)))))

  (routing-mode [_] :adversarial-routing))

(defn make-adversarial-router
  "Build an AdversarialRoutingRouter.

   target-ids — set of resolver IDs to overload (models ring-backed routing attack).
   pressure   — fraction of trials routed to targets above fair share.
                Typical values: 0.6–0.9 for a meaningful slow-drip attack."
  [target-ids pressure]
  (AdversarialRoutingRouter. (set target-ids) (double pressure)))

;; ---------------------------------------------------------------------------
;; Two-pool routing helper
;; ---------------------------------------------------------------------------

(defn route-epoch
  "Route one epoch's trial results to honest and strategic resolver pools.

   honest-ids        — seq of resolver IDs with :honest strategy
   strategic-ids     — seq of resolver IDs with non-honest strategies
   honest-trials     — vector of per-trial results run with strategy=:honest
   strategic-trials  — vector of per-trial results run with strategy=:malicious
                       (may equal honest-trials if only one batch is run)
   router            — TrialRouter implementation
   rng               — seeded RNG (will be split into two independent streams)

   Returns {:honest-attribution    {id → resolver-epoch-result}
            :strategic-attribution {id → resolver-epoch-result}}

   Conservation guarantee (checked internally via assert-conservation!):
     sum(honest profits)    == sum(:profit-honest honest-trials)
     sum(strategic profits) == sum(:profit-malice strategic-trials)"
  [honest-ids strategic-ids honest-trials strategic-trials router rng]
  (let [[rng-h rng-s] (rng/split-rng rng)

        honest-pool
        (mapv (fn [t] {:profit            (:profit-honest t 0.0)
                       :slashed?          false
                       :verdicts          1
                       :correct           (if (:dispute-correct? t false) 1 0)
                       :appeal-triggered? (:appeal-triggered? t false)
                       :escalated?        (:escalated? t false)})
              honest-trials)

        strategic-pool
        (mapv (fn [t] {:profit            (:profit-malice t 0.0)
                       :slashed?          (:slashed? t false)
                       :verdicts          1
                       :correct           0
                       :appeal-triggered? (:appeal-triggered? t false)
                       :escalated?        (:escalated? t false)})
              strategic-trials)

        honest-attr    (route router honest-ids    honest-pool    rng-h)
        strategic-attr (route router strategic-ids strategic-pool rng-s)]

    (assert-conservation! honest-attr    honest-pool    "honest pool")
    (assert-conservation! strategic-attr strategic-pool "strategic pool")

    {:honest-attribution    honest-attr
     :strategic-attribution strategic-attr}))