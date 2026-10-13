(ns resolver-sim.research.interaction-contrast
  "Second implementation of the finite four-observation interaction contract.
   Computes the difference between two within-row contrasts. Uses shared PRF
   canonical encoding, but does not call the original interaction engine."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]))

(defn- root [domain body]
  (reference/sha256-ref (canonical/domain-hash domain body)))

(defn- axis-valid? [axis]
  (and (= "research-axis-semantics.v1" (:artifact/schema axis))
       (= (:research-axis-semantics/root axis)
          (root :research-axis-semantics-v1
                (dissoc axis :research-axis-semantics/root)))
       (:axis/interaction-eligible? axis)
       (not= :researcher (:axis/kind axis))))

(defn- observation-valid? [observation]
  (let [status (:observation/status observation)
        numeric? (contains? #{:equal :different} status)]
    (and (contains? #{:equal :different :missing :not-comparable} status)
         (every? reference/valid-sha256-ref?
                 (map observation [:observation/root :research-analysis/root
                                   :accounting-policy/root]))
         (qualified-keyword? (:experiment/domain observation))
         (qualified-keyword? (:measure/id observation))
         (if numeric? (integer? (:observation/value observation))
             (nil? (:observation/value observation)))
         (if numeric? (nil? (:observation/reason observation))
             (keyword? (:observation/reason observation))))))

(defn compute [x-axis y-axis levels observations]
  (let [keys-in-order [:x1-y1 :x1-y2 :x2-y1 :x2-y2]
        cells (mapv observations keys-in-order)]
    (when-not (and (every? axis-valid? [x-axis y-axis])
                   (= #{:x1 :x2 :y1 :y2} (set (keys levels)))
                   (every? observation-valid? cells))
      (throw (ex-info "Invalid interaction contrast input" {})))
    (let [reason (cond
                   (some #(= :not-comparable (:observation/status %)) cells)
                   :constituent-not-comparable
                   (apply not= (map :accounting-policy/root cells))
                   :different-accounting-policies
                   (apply not= (map :experiment/domain cells))
                   :different-experiment-domains
                   (apply not= (map :measure/id cells))
                   :incompatible-outcome-measures
                   (some #(= :missing (:observation/status %)) cells)
                   :constituent-missing)
          values (zipmap keys-in-order (map :observation/value cells))
          value (when-not reason
                  (let [lower-row (- (values :x1-y2) (values :x1-y1))
                        upper-row (- (values :x2-y2) (values :x2-y1))]
                    (- upper-row lower-row)))
          status (cond (= reason :constituent-missing) :missing
                       reason :not-comparable
                       (zero? value) :equal
                       :else :different)
          body (cond->
                {:artifact/schema "research-coordinate-interaction.v1"
                 :interaction/method :integer-difference-of-differences
                 :interaction/x-axis-semantics-root (:research-axis-semantics/root x-axis)
                 :interaction/y-axis-semantics-root (:research-axis-semantics/root y-axis)
                 :interaction/levels levels
                 :interaction/constituent-observation-roots (mapv :observation/root cells)
                 :interaction/research-analysis-roots
                 (vec (sort (distinct (map :research-analysis/root cells))))
                 :interaction/status status :interaction/value value
                 :interaction/reason reason :interaction/arithmetic :exact-integer
                 :research-closure/meaning
                 :does-not-establish-claim-proof-authority-or-incentive-qualification}
                 (nil? reason) (assoc :interaction/formula-values values))]
      (assoc body :research-coordinate-interaction/root
             (root :research-coordinate-interaction-v1 body)))))
