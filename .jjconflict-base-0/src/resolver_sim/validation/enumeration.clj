(ns resolver-sim.validation.enumeration
  "Composable bounded-enumeration framework for strategic validation.

   Provides scope definitions, state generators, and stratified sampling
   strategies for enumerating bounded parameter spaces.  Designed to be
   usable across different mechanisms (partial-fill, dispute timing,
   bond parameters) via a common interface."
  (:import [java.util SplittableRandom]))

;; ---------------------------------------------------------------------------
;; Enumeration scope
;; ---------------------------------------------------------------------------

(defrecord EnumerationScope [dimensions        ;; map of dimension-kw -> [min max]
                             sampling          ;; :exhaustive | :stratified | :random
                             max-states        ;; max number of states to generate
                             seed]             ;; random seed (for :random sampling)

  Object
  (toString [this]
    (str "#EnumerationScope{:dimensions " dimensions
         " :sampling " sampling
         " :max-states " max-states "}")))

(defn make-scope
  "Create an EnumerationScope with defaults."
  [& {:keys [dimensions sampling max-states seed]
      :or {dimensions {:claim-count [1 5] :request [0 20] :liquidity [0 20]}
           sampling :exhaustive
           max-states 500}}]
  (EnumerationScope. dimensions sampling max-states seed))

;; ---------------------------------------------------------------------------
;; Dimension generators
;; ---------------------------------------------------------------------------

(defprotocol DimensionGenerator
  "Protocol for generating values for a dimension."
  (generate-values [this scope] "Return a seq of values for this dimension."))

(extend-protocol DimensionGenerator
  clojure.lang.MapEntry
  (generate-values [[_ [lo hi]] _]
    (range lo (inc hi)))

  clojure.lang.Keyword
  (generate-values [k scope]
    (if-let [[lo hi] (get (:dimensions scope) k)]
      (range lo (inc hi))
      (throw (ex-info (str "Unknown dimension: " k) {:dimension k}))))

  clojure.lang.IPersistentVector
  (generate-values [v _]
    (range (first v) (inc (last v)))))

;; ---------------------------------------------------------------------------
;; State enumeration
;; ---------------------------------------------------------------------------

(defn- enumerate-integer-partitions
  "Generate all integer partitions of n into k parts, each in [0,n].
   Returns a lazy seq of vectors."
  [n k]
  (letfn [(parts [n k]
            (if (= k 1)
              [[n]]
              (mapcat (fn [i]
                        (map (fn [p] (cons i p))
                             (parts (- n i) (dec k))))
                      (range 0 (inc n)))))]
    (parts n k)))

(defn- boundary-states
  "Generate boundary states for a given scope: zero, max, equal-shares,
   single-claim, max-claims, and extreme ratios.  Returns a lazy seq
   of state maps."
  [scope]
  (let [{:keys [claim-count request liquidity]} (:dimensions scope)
        cc-min (first claim-count) cc-max (last claim-count)
        rq-max (last request) lq-max (last liquidity)
        states (atom [])
        _ (swap! states conj {:claims [0] :claim-count 1 :request-sum 0 :liquidity 0})
        _ (swap! states conj {:claims [rq-max] :claim-count 1 :request-sum rq-max :liquidity lq-max})
        _ (when (>= cc-max 2)
            (swap! states conj {:claims [rq-max rq-max] :claim-count 2
                                :request-sum (* 2 rq-max) :liquidity lq-max})
            (swap! states conj {:claims [rq-max 0] :claim-count 2
                                :request-sum rq-max :liquidity lq-max})
            (swap! states conj {:claims [1 1] :claim-count 2 :request-sum 2
                                :liquidity 1}))]
    @states))

(defn generate-states
  "Generate a lazy seq of state maps for a given scope.
   Each state map has `:claims` (vector of request amounts), `:claim-count`,
   `:request-sum`, and `:liquidity`.

   When sampling is `:stratified`, boundary states (zero, max, extreme
   ratios) are emitted first, followed by the combinatorial interior."
  [scope]
  (let [{:keys [dimensions sampling max-states]} scope
        [cc-lo cc-hi] (:claim-count dimensions)
        [rq-lo rq-hi] (:request dimensions)
        [lq-lo lq-hi] (:liquidity dimensions)]
    (case sampling
      :stratified
      (let [boundary (boundary-states scope)
            interior (fn []
                       (take (- max-states (count boundary))
                             (for [cc (range cc-lo (inc cc-hi))
                                   rv (enumerate-integer-partitions rq-hi cc)
                                   lq (range lq-lo (inc lq-hi))
                                   :when (or (not= (vec rv) [0])
                                             (zero? lq))]
                               {:claims rv
                                :claim-count cc
                                :request-sum (reduce + 0 rv)
                                :liquidity lq})))]
        (concat boundary (interior)))

      :exhaustive
      (take max-states
            (for [cc (range cc-lo (inc cc-hi))
                  rv (enumerate-integer-partitions rq-hi cc)
                  lq (range lq-lo (inc lq-hi))
                  :when (or (not= (vec rv) [0])
                            (zero? lq))]
              {:claims rv
               :claim-count cc
               :request-sum (reduce + 0 rv)
               :liquidity lq}))

      :random
      (let [rng (SplittableRandom. (or (:seed scope) 42))]
        (take max-states
              (repeatedly (fn []
                            (let [cc (+ cc-lo (.nextInt rng (inc (- cc-hi cc-lo))))]
                              {:claims (repeatedly cc #(+ rq-lo (.nextInt rng (inc (- rq-hi rq-lo)))))
                               :claim-count cc
                               :request-sum 0  ;; computed by caller if needed
                               :liquidity (+ lq-lo (.nextInt rng (inc (- lq-hi lq-lo))))}))))))))
