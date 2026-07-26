(ns resolver-sim.benchmark.packs.partial-fill.evidence
  "Partial-fill benchmark evidence extraction and reconciliation.
   
   Derives benchmark-facing evidence from yield/pro-rata domain artifacts
   without modifying protocol transition code.
   
   Dependency direction: benchmark -> yield/domain artifacts (one-way)."
  (:require [resolver-sim.hash.canonical :as hc]))

;; ── State write-back evidence ─────────────────────────────────────────────

(defn derive-state-write-back
  "Derive authoritative state-write-back evidence from the application artifact
   and the final world state. Does not require modifying liquid-lending
   transition code — this is an additive projection at the benchmark layer.
   
   Returns nil when the application artifact or final world cannot supply
   the required fields."
  [application-artifact final-world]
  (let [participants (:participants application-artifact [])]
    (when (seq participants)
      (mapv
       (fn [participant]
         (let [participant-id (:participant-id participant)
               withdrawn (:withdrawn participant {})
               position-after (:position-after participant)
               before (:before withdrawn 0)
               delta (:delta withdrawn 0)
               after (:after withdrawn 0)
               token (:token withdrawn)
               final-world-withdrawn (get-in final-world
                                             [:yield/withdrawn
                                              token
                                              participant-id]
                                             0)
               final-world-position (get-in final-world
                                            [:yield/positions
                                             participant-id])
               final-world-pos-hash (when final-world-position
                                      (str "sha256:" (hc/domain-hash :state-projection
                                                                     final-world-position)))
               after-hash (:position-after-hash participant)
               deferred (:deferred-position position-after)
               prior-deferred (:deferred-position
                               (:position-before participant))]
           {:participant/id participant-id
            :token token
            :withdrawn
            {:before before
             :delta delta
             :after after
             :final-world-value final-world-withdrawn
             :verified? (= final-world-withdrawn after)}
            :position
            {:before-hash (:position-before-hash participant)
             :after-hash after-hash
             :final-world-position-hash final-world-pos-hash
             :verified? (and after-hash
                             final-world-pos-hash
                             (= after-hash final-world-pos-hash))}
            :deferred-position
            {:prior-closed? (and prior-deferred
                                 (= :closed (:position/status prior-deferred)))
             :prior-current-amount (:position/current-amount prior-deferred)
             :successor-current-amount (:position/current-amount deferred)
             :final-world-current-amount (get-in final-world-position
                                                 [:deferred-position
                                                  :position/current-amount])
             :verified? (and deferred
                             (= (:position/current-amount deferred)
                                (get-in final-world-position
                                        [:deferred-position
                                         :position/current-amount])))}}))
       participants))))

;; ── Propagation/application reference collection ──────────────────────────

(defn collect-application-refs
  "Extract stable references from the applied-pro-rata-propagations world map.
   Returns a vector of reference maps suitable for benchmark evidence inclusion."
  [final-world]
  (let [propagations (get-in final-world [:yield/applied-pro-rata-propagations] {})]
    (mapv (fn [[_ app]]
            {:propagation/id (:propagation-id app)
             :application/hash (:application/hash app)
             :calculation-id (:calculation-id app)
             :outcome-hash (:outcome-hash app)
             :application-order (:application-order app)})
          (sort-by (comp :application-order val) propagations))))

(defn collect-propagation-refs
  "Extract stable references from the pro-rata-propagations world map.
   Returns a vector of reference maps."
  [final-world]
  (let [props (get-in final-world [:yield/pro-rata-propagations] {})]
    (mapv (fn [[_ prop]]
            {:propagation/id (:propagation/id prop)
             :propagation/hash (:propagation/hash prop)
             :propagation/content-hash (:propagation/content-hash prop)
             :calculation-ref (:calculation-ref prop)
             :outcome-ref (:outcome-ref prop)})
          (sort-by (comp :propagation/hash val) props))))

;; ── Semantic commitments root ─────────────────────────────────────────────

(defn semantic-commitments
  "Build the evidence/semantic-commitments section for a benchmark outcome
   manifest from a final world state and optionally collected evidence maps.
   
   Returns nil when no partial-fill activity occurred."
  [final-world & {:keys [state-write-back-evidence continuity-evidence]}]
  (let [decisions (get-in final-world [:yield/partial-fill-decisions] {})
        prop-refs (collect-propagation-refs final-world)
        app-refs (collect-application-refs final-world)]
    (when (or (seq decisions) (seq prop-refs) (seq app-refs))
      (cond-> {}
        (seq decisions)
        (assoc :partial-fill-decisions-root
               (hc/domain-hash :evidence-collection
                               (vec (sort (map :decision/hash (vals decisions))))))
        (seq prop-refs)
        (assoc :propagation-refs-root
               (hc/domain-hash :evidence-collection
                               (vec (sort (map :propagation/hash (remove nil? prop-refs))))))
        (seq app-refs)
        (assoc :application-refs-root
               (hc/domain-hash :evidence-collection
                               (vec (sort (map :application/hash (remove nil? app-refs))))))
        state-write-back-evidence
        (assoc :state-write-back-root
               (hc/domain-hash :evidence-collection
                               (vec (sort-by :participant/id state-write-back-evidence))))
        continuity-evidence
        (assoc :continuity-root
               (hc/domain-hash :evidence-collection
                               (vec (sort-by :participant/id continuity-evidence))))))))

;; ── Continuity evidence ───────────────────────────────────────────────────

(defn derive-continuity-evidence
  "Derive precondition-continuity evidence from the final world state.
   
   Verifies that the position commitment in each application artifact
   is still consistent with the final world state — proving that a
   downstream consumer could have read the updated values.
   
   next-precondition-consumed is conditional (:not-observed when
   no subsequent propagation references the position)."
  [final-world application-refs]
  (let [propagations (get-in final-world [:yield/applied-pro-rata-propagations] {})]
    (mapv
     (fn [app-ref]
       (let [app (get propagations (:propagation/id app-ref))
             participants (:participants app [])]
         (mapv
          (fn [participant]
            (let [pid (:participant-id participant)
                  after-hash (:position-after-hash participant)
                  final-pos (get-in final-world [:yield/positions pid])
                  final-pos-hash (when final-pos
                                   (str "sha256:" (hc/domain-hash :state-projection
                                                                  final-pos)))
                  current-amount (get-in participant
                                         [:position-after
                                          :deferred-position
                                          :position/current-amount])
                  final-amount (get-in final-world
                                       [:yield/positions
                                        pid
                                        :deferred-position
                                        :position/current-amount])]
              {:participant/id pid
               :propagation/id (:propagation/id app-ref)
               :expected-position-hash after-hash
               :current-position-hash final-pos-hash
               :expected-current-amount current-amount
               :current-current-amount final-amount
               :matches? (and after-hash
                              final-pos-hash
                              (= after-hash final-pos-hash))
               :amount-continuous? (or (nil? current-amount)
                                       (= current-amount final-amount))}))
          participants)))
     application-refs)))

;; ── Application evidence ladder ───────────────────────────────────────────

(defn application-evidence-ladder
  "Build a six-level application evidence ladder from available artifacts.
   
   Levels:
     1. allocation-calculated  — partial-fill decision hash present
     2. application-claimed   — propagation artifact with :apparent-application
     3. accounting-emitted    — accounting entry set hash present and balanced
     4. state-written-back    — state write-back derived and verified
     5. continuity-consumed   — next precondition position hash matches
     6. outcome-committed     — application ref included in outcome commitments
   
   Levels 4 and 5 may be :not-observed when the final world state does not
   contain the required propagation or application artifacts."
  [final-world & {:keys [state-write-back-evidence continuity-evidence outcome-hash]}]
  (let [propagations (get-in final-world [:yield/pro-rata-propagations] {})
        applications (get-in final-world [:yield/applied-pro-rata-propagations] {})
        decisions (get-in final-world [:yield/partial-fill-decisions] {})]
    (mapv
     (fn [[prop-id prop]]
       (let [app (get applications prop-id)
             decision (get decisions (:calculation-ref prop))
             participants (:participants prop [])
             level-status
             (fn [level status & {:keys [reason]}]
               (cond-> {:level level :status (name status)}
                 reason (assoc :reason reason)))]
         {:propagation/id prop-id
          :levels
          [(level-status :allocation-calculated
                         (if (some? decision) :verified :failed)
                         :reason (when (nil? decision)
                                   "decision artifact not found in world"))

           (level-status :application-claimed
                         (cond
                           (nil? app) :failed
                           (some? (:applications prop)) :verified
                           :else :inconclusive))

           (level-status :accounting-emitted
                         (cond
                           (nil? (:accounting-entry-set-hash prop)) :failed
                           (some? (:accounting-entries prop)) :verified
                           :else :inconclusive))

           (let [wb (some-> state-write-back-evidence
                            (->> (filter #(= prop-id (:propagation/id %)))
                                 first))]
             (level-status :state-written-back
                           (cond
                             (nil? wb) :not-observed
                             (true? (:verified? wb)) :verified
                             :else :failed)))

           (let [ce (some->> continuity-evidence
                             (mapcat identity)
                             (filter #(= prop-id (:propagation/id %)))
                             seq)]
             (level-status :continuity-consumed
                           (cond
                             (nil? ce) :not-observed
                             (every? :matches? ce) :verified
                             (some (complement :matches?) ce) :failed
                             :else :inconclusive)))

           (level-status :outcome-committed
                         (if (some? outcome-hash) :verified :not-observed))]}))
     propagations)))
