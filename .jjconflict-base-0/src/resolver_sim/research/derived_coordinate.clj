(ns resolver-sim.research.derived-coordinate
  "One explicit operator contract around the existing interaction engine.
   Structural diffs, semantic comparisons and numeric derivations stay distinct."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.research.coordinate-interaction :as interaction]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(def schema "coordinate-derived-result.v1")
(def operator-contract
  {:operator/id :integer-difference-of-differences.v1
   :operator/operand-domain :exact-integer-observation-values
   :operator/axis-use :selectors-only
   :operator/compatibility :same-accounting-policy-domain-and-measure
   :operator/arithmetic :clojure-checked-integer
   :operator/unavailable :propagate-without-zero
   :operator/conflict :reject-unsupported-conflicting-observations
   :operator/forbidden-axis-kinds [:implementation :researcher :runner]})

(defn relation-family
  "Classify a supported schema, not validate it or grant authority."
  [artifact]
  (case (:artifact/schema artifact)
    "research-definition-diff.v1" :structural-diff
    "research-analysis-comparison.v1" :semantic-comparison
    "research-coordinate-interaction.v1" :numeric-differential
    "coordinate-derived-result.v1" :numeric-differential
    "reproduction-evidence.v1" :reproduction-evidence
    (throw (ex-info "Unsupported relation schema" {}))))

(defn result-root [artifact]
  (reference/sha256-ref
   (canonical/domain-hash :coordinate-derived-result-v1
                          (dissoc artifact :coordinate-derived-result/root))))

(defn derive-interaction [x-axis y-axis levels observations]
  (when (some #(contains? (set (:operator/forbidden-axis-kinds operator-contract)) (:axis/kind %))
              [x-axis y-axis])
    (throw (ex-info "Nominal participant/implementation axis cannot be an arithmetic operand"
                    {:reason :forbidden-axis})))
  ;; The established engine remains authoritative; this wrapper does not change
  ;; its result identity or subtract coordinate keys/axis labels.
  (let [result (interaction/difference-of-differences x-axis y-axis levels observations)
        body {:artifact/schema schema :derived/operator operator-contract
              :derived/axis-roots [(:research-axis-semantics/root x-axis)
                                   (:research-axis-semantics/root y-axis)]
              :derived/constituent-roots (:interaction/constituent-observation-roots result)
              :derived/result-root (:research-coordinate-interaction/root result)
              :derived/status (:interaction/status result)
              :derived/value (:interaction/value result)
              :derived/reason (:interaction/reason result)
              :research-closure/meaning :does-not-establish-claim-proof-authority-or-incentive-qualification}]
    (assoc body :coordinate-derived-result/root (result-root body))))

(defn validate-derived-result [artifact x-axis y-axis levels observations]
  (when-not (= artifact (derive-interaction x-axis y-axis levels observations))
    (throw (ex-info "Detached derived coordinate result" {})))
  artifact)

(defn evidence-node [artifact x-axis y-axis levels observations]
  (validate-derived-result artifact x-axis y-axis levels observations)
  (forensic/build-forensic-evidence-node
   {:subject {:subject/kind :research/derived-coordinate
              :subject/root (:coordinate-derived-result/root artifact)}
    :role :derived :visibility :public :consequence? true
    :causal-parent-roots (vec (distinct (concat (:derived/axis-roots artifact)
                                                (:derived/constituent-roots artifact)
                                                [(:derived/result-root artifact)])))}))

(defn link-reproduction
  "Compose existing evidence with a numeric derivation, without claim promotion.
   Source-projection artifacts cannot stand in for fresh execution."
  [derived input execution original evidence]
  (let [{:keys [x-axis y-axis levels observations]} (:computation/input input)]
    (validate-derived-result derived x-axis y-axis levels observations))
  (when-not (and (reproduction/valid-input? input)
                 (= (:reproduction-input/root input) (:reproduction-input/root execution))
                 (= (:derived/result-root derived) (:research-coordinate-interaction/root original))
                 (= evidence (reproduction/compare-execution execution original)))
    (throw (ex-info "Detached reproduction/derived-coordinate relation" {})))
  (forensic/build-forensic-evidence-node
   {:subject {:subject/kind :research/reproduction-evidence
              :subject/root (:reproduction-evidence/root evidence)}
    :role :derived :visibility :public
    :causal-parent-roots (vec (distinct [(:coordinate-derived-result/root derived)
                                         (:reproduction-input/root input)
                                         (:reproduction-execution/root execution)
                                         (:derived/result-root derived)]))}))
