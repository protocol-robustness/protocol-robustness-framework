(ns resolver-sim.cancellation.semantic
  "Minimal rooted semantic artifacts. These are derived evidence, never operations
   or dispatcher/commit artifacts."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def policy-schema "sew-party-cancellation-policy.v1")
(def evaluation-schema "sew-party-cancellation-evaluation.v1")
(def derived-effects-schema "sew-party-cancellation-derived-effects.v1")
(def execution-effects-schema "sew-party-cancellation-execution-effects.v1")
(defn- rooted [domain object key] (hash-ref/sha256-ref (hc/domain-hash domain (dissoc object key))))
(defn valid-policy? [p] (and (map? p) (= policy-schema (:policy/schema p)) (boolean? (:policy/can-cancel? p)) (boolean? (:policy/unilateral-cancel? p))))
(defn policy-root [p] (when-not (valid-policy? p) (throw (ex-info "invalid party cancellation policy" {:policy p}))) (rooted "SEW_PARTY_CANCELLATION_POLICY_V1" p :policy/root))
(defn policy-root-valid? [p] (and (valid-policy? p) (= (:policy/root p) (policy-root (dissoc p :policy/root)))))
(defn valid-derived-effects? [e] (and (map? e) (= derived-effects-schema (:effects/schema e)) (contains? #{:record-party-agreement :refund-sender} (:effects/kind e)) (contains? #{:sender :recipient} (:effects/by e))))
(defn derived-effects-root [e] (when-not (valid-derived-effects? e) (throw (ex-info "invalid derived effects" {:effects e}))) (rooted "SEW_PARTY_CANCELLATION_DERIVED_EFFECTS_V1" e :effects/root))
(defn derived-effects-root-valid? [e] (and (valid-derived-effects? e) (= (:effects/root e) (derived-effects-root (dissoc e :effects/root)))))
(defn valid-execution-effects? [e] (and (map? e) (= execution-effects-schema (:execution-effects/schema e)) (hash-ref/valid-sha256-ref? (:derived-effects/root e))))
(defn execution-effects-root [e] (when-not (valid-execution-effects? e) (throw (ex-info "invalid execution effects" {:effects e}))) (rooted "SEW_PARTY_CANCELLATION_EXECUTION_EFFECTS_V1" e :execution-effects/root))
(defn execution-effects-root-valid? [e] (and (valid-execution-effects? e) (= (:execution-effects/root e) (execution-effects-root (dissoc e :execution-effects/root)))))
(defn valid-evaluation? [e] (and (map? e) (= evaluation-schema (:evaluation/schema e)) (contains? #{:authorized :forbidden} (:decision/classification e)) (hash-ref/valid-sha256-ref? (:operation/root e)) (hash-ref/valid-sha256-ref? (:snapshot/root e)) (hash-ref/valid-sha256-ref? (:policy/root e))))
(defn evaluation-root [e] (when-not (valid-evaluation? e) (throw (ex-info "invalid party cancellation evaluation" {:evaluation e}))) (rooted "SEW_PARTY_CANCELLATION_EVALUATION_V1" e :evaluation/root))
(defn evaluation-root-valid? [e] (and (valid-evaluation? e) (= (:evaluation/root e) (evaluation-root (dissoc e :evaluation/root)))))
