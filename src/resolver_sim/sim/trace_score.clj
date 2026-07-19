(ns resolver-sim.sim.trace-score
  "Pure trace scoring for replay results (no I/O).

   Used by simulation phases and by io/trace-score for persistence workflows.
   The optional escrow projection convention treats :pending and :disputed as
   open states; protocols that do not expose :escrow-transfers simply receive
   no escrow liveness penalty.")

(defn- classify-issue
  [result]
  (let [metrics (:metrics result {})
        comps   (:score-components result {})
        liveness? (pos? (:liveness-failure comps 0))
        inv?      (pos? (:invariant-violations metrics 0))
        attack?   (pos? (:attack-successes metrics 0))]
    (cond
      (and liveness? inv?) :mixed
      inv?                 :invariant-failure
      liveness?            :liveness-failure
      attack?              :attack-success
      :else                :none)))

(def ^:private open-escrow-states #{:pending :disputed})

(defn- liveness-failure?
  [trace]
  (let [last-entry (last trace)
        proj       (:projection last-entry)]
    (when proj
      (let [transfers (vals (:escrow-transfers proj {}))]
        (boolean (some #(contains? open-escrow-states (:escrow-state %)) transfers))))))

(defn score-result
  "Compute :trace-score for a replay-with-protocol result map."
  [result]
  (let [metrics              (:metrics result {})
        attack-successes     (:attack-successes metrics 0)
        invariant-violations (:invariant-violations metrics 0)
        trace                (:trace result [])
        liveness?            (liveness-failure? trace)
        liveness-penalty     (if liveness? 1 0)
        score                (+ attack-successes
                                (* 10 invariant-violations)
                                (* 5 liveness-penalty))]
    (assoc result
           :trace-score      score
           :issue/type       (classify-issue (assoc result :score-components {:liveness-failure liveness-penalty}))
           :score-components {:attacker-profit      attack-successes
                              :invariant-violations invariant-violations
                              :liveness-failure     liveness-penalty})))

(defn score-category
  "Classify a scored replay into behavioural categories.
   Note: The :cascade tag is triggered by (> disputes-triggered 1)
   — a simple count heuristic.  It does not measure whether later
   disputes were affected by earlier ones (sequential dependency),
   so two independent disputes are also tagged as :cascade."
  [scored-result]
  (let [comps   (:score-components scored-result {})
        metrics (:metrics scored-result {})]
    (cond-> #{}
      (> (:attacker-profit comps 0) 0) (conj :top-profitable)
      (> (:liveness-failure comps 0) 0) (conj :liveness-fail)
      (> (:disputes-triggered metrics 0) 1) (conj :cascade)
      (> (:invariant-violations metrics 0) 0) (conj :abnormal-slash))))

(defn select-top-n
  [n scored-results]
  (->> scored-results
       (sort-by (fn [r]
                  [(- (:trace-score r 0))
                   (- (get-in r [:score-components :invariant-violations] 0))
                   (- (get-in r [:score-components :attacker-profit] 0))]))
       (take n)
       vec))

(defn select-top-percentile
  [p scored-results]
  (let [n (max 1 (int (Math/ceil (* p (count scored-results)))))]
    (select-top-n n scored-results)))
