(ns resolver-sim.research.coordinate-interaction
  "Exact finite-grid interaction derivation with explicit axis semantics.

   Coordinates are scoped members, tuples are observations, and an interaction
   is a downstream four-observation contrast. Merely placing axes beside one
   another establishes no interaction or metric structure."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const axis-semantics-schema "research-axis-semantics.v1")
(def ^:const interaction-schema "research-coordinate-interaction.v1")
(def observation-statuses #{:equal :different :missing :not-comparable})
(def data-kinds #{:nominal :ordered :integer})

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))
(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn axis-semantics-root [artifact]
  (root :research-axis-semantics-v1
        (dissoc artifact :research-axis-semantics/root)))

(defn build-axis-semantics
  [{:keys [axis-root axis-kind data-kind ordered? integer? metric? additive?
           interaction-eligible?]}]
  (when-not (and (root? axis-root) (keyword? axis-kind)
                 (contains? data-kinds data-kind)
                 (every? boolean? [ordered? integer? metric? additive?
                                   interaction-eligible?])
                 (= integer? (= :integer data-kind))
                 (or (not metric?) ordered?)
                 (or (not additive?) integer?)
                 (or (not= :researcher axis-kind)
                     (and (= :nominal data-kind) (not ordered?) (not integer?)
                          (not metric?) (not additive?)
                          (not interaction-eligible?))))
    (throw (ex-info "Invalid research axis semantics"
                    {:axis/kind axis-kind :data-kind data-kind})))
  (let [body {:artifact/schema axis-semantics-schema
              :axis/root axis-root :axis/kind axis-kind
              :axis/data-kind data-kind :axis/ordered? ordered?
              :axis/integer? integer? :axis/metric? metric?
              :axis/additive? additive?
              :axis/interaction-eligible? interaction-eligible?
              :axis/semantic-warning
              :integer-does-not-imply-metric-additive-or-linear}]
    (assoc body :research-axis-semantics/root (axis-semantics-root body))))

(defn interaction-root [artifact]
  (root :research-coordinate-interaction-v1
        (dissoc artifact :research-coordinate-interaction/root)))

(defn- valid-observation? [observation]
  (and (map? observation)
       (contains? observation-statuses (:observation/status observation))
       (root? (:observation/root observation))
       (root? (:research-analysis/root observation))
       (root? (:accounting-policy/root observation))
       (qualified-keyword? (:experiment/domain observation))
       (qualified-keyword? (:measure/id observation))
       (if (contains? #{:equal :different} (:observation/status observation))
         (integer? (:observation/value observation))
         (nil? (:observation/value observation)))
       (if (contains? #{:missing :not-comparable} (:observation/status observation))
         (keyword? (:observation/reason observation))
         (nil? (:observation/reason observation)))))

(defn- comparability [observations]
  (cond
    (some #(= :not-comparable (:observation/status %)) observations)
    {:status :not-comparable :reason :constituent-not-comparable}

    (not= 1 (count (set (map :accounting-policy/root observations))))
    {:status :not-comparable :reason :different-accounting-policies}

    (not= 1 (count (set (map :experiment/domain observations))))
    {:status :not-comparable :reason :different-experiment-domains}

    (not= 1 (count (set (map :measure/id observations))))
    {:status :not-comparable :reason :incompatible-outcome-measures}

    (some #(= :missing (:observation/status %)) observations)
    {:status :missing :reason :constituent-missing}

    :else {:status :comparable}))

(defn difference-of-differences
  "Derive the exact contrast f(x2,y2)-f(x2,y1)-f(x1,y2)+f(x1,y1).

   `observations` must contain keys :x1-y1, :x1-y2, :x2-y1, :x2-y2.
   Axis levels may be explicit categorical contrasts, but each axis must opt in.
   Researcher axes can never opt in. Missing and incompatible observations do
   not become numeric zero."
  [x-axis y-axis levels observations]
  (let [ordered-keys [:x1-y1 :x1-y2 :x2-y1 :x2-y2]
        constituents (mapv observations ordered-keys)]
    (when-not (and (= axis-semantics-schema (:artifact/schema x-axis))
                   (= axis-semantics-schema (:artifact/schema y-axis))
                   (= (:research-axis-semantics/root x-axis)
                      (axis-semantics-root x-axis))
                   (= (:research-axis-semantics/root y-axis)
                      (axis-semantics-root y-axis))
                   (:axis/interaction-eligible? x-axis)
                   (:axis/interaction-eligible? y-axis)
                   (not-any? #(= :researcher (:axis/kind %)) [x-axis y-axis])
                   (= #{:x1 :x2 :y1 :y2} (set (keys levels)))
                   (every? valid-observation? constituents))
      (throw (ex-info "Invalid coordinate interaction input" {})))
    (let [{:keys [status reason]} (comparability constituents)
          values (into {} (map vector ordered-keys
                               (map :observation/value constituents)))
          interaction-value (when (= :comparable status)
                              (+ (values :x2-y2)
                                 (- (values :x2-y1))
                                 (- (values :x1-y2))
                                 (values :x1-y1)))
          outcome (if (= :comparable status)
                    (if (zero? interaction-value) :equal :different)
                    status)
          body (cond->
                {:artifact/schema interaction-schema
                 :interaction/method :integer-difference-of-differences
                 :interaction/x-axis-semantics-root
                 (:research-axis-semantics/root x-axis)
                 :interaction/y-axis-semantics-root
                 (:research-axis-semantics/root y-axis)
                 :interaction/levels levels
                 :interaction/constituent-observation-roots
                 (mapv :observation/root constituents)
                 :interaction/research-analysis-roots
                 (vec (sort (set (map :research-analysis/root constituents))))
                 :interaction/status outcome
                 :interaction/value interaction-value
                 :interaction/reason reason
                 :interaction/arithmetic :exact-integer
                 :research-closure/meaning
                 :does-not-establish-claim-proof-authority-or-incentive-qualification}
                 (= :comparable status)
                 (assoc :interaction/formula-values values))]
      (assoc body :research-coordinate-interaction/root
             (interaction-root body)))))
