(ns resolver-sim.protocols.sew.invariants.yield
  "Yield-related invariant predicates for the Sew contract model.")

(defn yield-position-consistency?
  "True when every yield position has a valid status transition: active positions
   have non-negative principal and unrealized-yield; unwinding positions have a
   non-nil shortfall with positive deferred-amount; withdrawn positions have zero
   remaining balance."
  [world]
  (let [violations (for [[oid pos] (:yield/positions world {})
                         :let [status (:status pos)
                               principal (or (:principal pos) 0)]
                         :when (case status
                                 :active (or (neg? principal) (neg? (get pos :unrealized-yield 0)))
                                 :unwinding (or (nil? (:shortfall pos))
                                                (not (pos? (get-in pos [:shortfall :deferred-amount] 0))))
                                 :withdrawn (not= 0 (+ principal (get pos :realized-yield 0)
                                                         (get pos :unrealized-yield 0)))
                                 false)]
                     {:owner-id (if (vector? oid) (second oid) oid)
                      :status status
                      :principal principal
                      :detail (case status
                                :active "negative principal or unrealized-yield"
                                :unwinding "missing shortfall or non-positive deferred-amount"
                                :withdrawn "non-zero balance after withdrawal"
                                "unknown status")})]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn yield-exposure-ok?
  "True when no single owner's resolver-owed yield exceeds the total resolver stake
   for that workflow. Prevents yield exposure from exceeding available slash collateral."
  [world]
  (let [resolver-stakes (get-in world [:params :resolver-stakes] {})
        violations (for [[oid pos] (:yield/positions world {})
                         :when (and (= :active (:status pos))
                                    (vector? oid)
                                    (let [wf-id (second oid)
                                          unrealized (get pos :unrealized-yield 0)
                                          realized (get pos :realized-yield 0)
                                          total-exposure (+ unrealized realized)
                                          stake (get resolver-stakes wf-id 0)]
                                      (and (pos? total-exposure) (< stake total-exposure))))]
                     {:owner-id (second oid)
                      :workflow-id (first oid)
                      :yield-exposure (+ (get pos :unrealized-yield 0) (get pos :realized-yield 0))
                      :resolver-stake (get resolver-stakes (second oid) 0)
                      :detail "yield exposure exceeds resolver stake"})]
    {:holds? (empty? violations)
     :violations (vec violations)}))

(defn realized-non-negative?
  "True when all realized-yield values across all positions are non-negative."
  [world]
  (let [violations (for [[oid pos] (:yield/positions world {})
                         :let [realized (get pos :realized-yield 0)]
                         :when (neg? realized)]
                     {:owner-id (if (vector? oid) (second oid) oid)
                      :realized-yield realized
                      :detail "negative realized yield"})]
    {:holds? (empty? violations)
     :violations (vec violations)}))
