(ns resolver-sim.scenario.theory-validation
  "Theory block validation.

   Validates structure BEFORE evaluation so that malformed predicates
   produce clear error messages instead of silently producing garbage
   evidence leaves.

   Uses plain Clojure validation (depth-bounded) instead of Malli schemas
   to avoid recursive schema expansion issues.")

(def ^:private valid-ops #{:= :> :< :>= :<= :not=})

(defn- validate-predicate
  "Validate a single predicate map at a given depth bound.
   Returns nil or an error string."
  [pred depth]
  (when (pos? depth)
    (cond
      (:metric pred)
      (cond
        (not (contains? pred :op)) "missing :op in metric leaf"
        (not (valid-ops (:op pred))) (str "invalid :op " (:op pred) " in metric leaf")
        (nil? (:metric pred)) "nil :metric in metric leaf"
        :else nil)

      (:state pred)
      (let [s (:state pred)]
        (cond
          (not (map? s)) "expected :state to be a map"
          (not (contains? s :query)) "missing :query in :state"
          (not (contains? s :op)) "missing :op in :state"
          (not (valid-ops (:op s))) (str "invalid :op " (:op s) " in :state")
          :else nil))

      (:and pred)
      (let [children (:and pred)]
        (first (keep #(validate-predicate % (dec depth)) children)))

      (:or pred)
      (let [children (:or pred)]
        (first (keep #(validate-predicate % (dec depth)) children))))

      (:not pred)
      (validate-predicate (:not pred) (dec depth))

      (:implies pred)
      (or (validate-predicate (:if pred) (dec depth))
          (validate-predicate (:then pred) (dec depth)))

      (:always pred)
      (validate-predicate (:always pred) (dec depth))

      (:eventually pred)
      (validate-predicate (:eventually pred) (dec depth))

      (:after pred)
      (let [a (:after pred)]
        (cond
          (not (map? a)) "expected :after to be a map"
          (not (string? (:event a))) "expected :event in :after to be a string"
          :else (validate-predicate (:predicate a) (dec depth)))

      (:before pred)
      (let [b (:before pred)]
        (cond
          (not (map? b)) "expected :before to be a map"
          (not (string? (:event b))) "expected :event in :before to be a string"
          :else (validate-predicate (:predicate b) (dec depth)))

      :else "unrecognized predicate shape: expected :metric, :state, :and, :or, :not, :implies, :always, :eventually, :after, or :before")))


(defn- validate-falsifies-if
  "Validate a :falsifies-if value (vector of predicates or a single predicate)."
  [conds]
  (if (vector? conds)
    (first (keep #(validate-predicate % 10) conds))
    (validate-predicate conds 10)))


(defn validate-theory
  "Validate a theory block.
   Returns {:valid? true} or {:valid? false :errors [msg ...]}."
  [theory]
  (if (nil? theory)
    {:valid? true}
    (let [errors (cond-> []
                   (contains? theory :falsifies-if)
                   (conj (let [e (validate-falsifies-if (:falsifies-if theory))]
                           (when e e)))

                   (contains? theory :claim-id)
                   (conj (let [c (:claim-id theory)]
                           (when (and (some? c) (not (or (keyword? c) (string? c))))
                             ":claim-id must be a keyword or string")))

                   (contains? theory :assumptions)
                   (conj (let [a (:assumptions theory)]
                           (when (and (some? a) (not (every? keyword? a)))
                             ":assumptions must be a vector of keywords"))))
          valid-errors (filter string? errors)]
      (if (seq valid-errors)
        {:valid? false :errors valid-errors}
        {:valid? true})))))
