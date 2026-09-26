(ns resolver-sim.research.observed-run-proof-statement
  "Canonical SP1 statement projection for one observed assured execution.

   The caller supplies only the authoritative observed-run and V1 assurance
   artifacts. Every proved root is derived from those artifacts; this namespace
   accepts no independently reconstructed job/environment/state/action/result
   fields."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.observed-assurance :as observed]))

(def ^:const schema "research-observed-run-proof-statement.v1")
(def ^:const public-values-schema "observed-run-roots-384.v1")
(def public-value-order
  [:research-execution-job-assurance/root
   :research-observed-run-evidence/root
   :research-run-assurance-evidence/root
   :resolved-research-dependency-set/root
   :research-implementation-identity/root
   :research-execution-environment/root
   :research-execution-environment-observation/root
   :environment-before/root :executed-action/root :environment-after/root
   :semantic-result/root :research-observed-fixed-validation-receipt/root])

(defn- root [x]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-observed-run-proof-statement-v1
                   (dissoc x :research-observed-run-proof-statement/root))))

(defn derive-observed-run-proof-statement-v1
  [observed-run-evidence run-assurance-evidence]
  (when-not (and (observed/valid-observed-run-evidence? observed-run-evidence)
                 (assurance/valid-run-assurance-evidence? run-assurance-evidence)
                 (= (:research-observed-run-evidence/root observed-run-evidence)
                    (:research-run-evidence/root run-assurance-evidence))
                 (= (:resolved-research-dependency-set/root observed-run-evidence)
                    (:resolved-research-dependency-set/root run-assurance-evidence))
                 (= (:research-implementation-identity/root observed-run-evidence)
                    (:research-implementation-identity/root run-assurance-evidence))
                 (= (:runner/reference observed-run-evidence)
                    (:runner/reference run-assurance-evidence))
                 (= (:runner/site-id observed-run-evidence)
                    (:runner/site-id run-assurance-evidence))
                 (= (:semantic-result/root observed-run-evidence)
                    (:semantic-result/root run-assurance-evidence))
                 (= (:research-execution-job-assurance/root
                     (:assured-job (:proof/source-artifacts observed-run-evidence)))
                    (:research-execution-job-assurance/root run-assurance-evidence)))
    (throw (ex-info "Observed-run proof statement lineage mismatch"
                    {:cause :observed-run-proof-lineage-mismatch})))
  (let [body (assoc
              {:artifact/schema schema
               :research-execution-job-assurance/root
               (:research-execution-job-assurance/root run-assurance-evidence)
               :research-observed-run-evidence/root
               (:research-observed-run-evidence/root observed-run-evidence)
               :research-run-assurance-evidence/root
               (:research-run-assurance-evidence/root run-assurance-evidence)}
              ;; These fields are copied only from authoritative observed evidence.
              :resolved-research-dependency-set/root (:resolved-research-dependency-set/root observed-run-evidence)
              :research-implementation-identity/root (:research-implementation-identity/root observed-run-evidence)
              :research-execution-environment/root (:research-execution-environment/root observed-run-evidence)
              :research-execution-environment-observation/root (:research-execution-environment-observation/root observed-run-evidence)
              :environment-before/root (:environment-before/root observed-run-evidence)
              :executed-action/root (:executed-action/root observed-run-evidence)
              :environment-after/root (:environment-after/root observed-run-evidence)
              :semantic-result/root (:semantic-result/root observed-run-evidence)
              :research-observed-fixed-validation-receipt/root
              (:research-observed-fixed-validation-receipt/root observed-run-evidence)
              :proof/public-value-order public-value-order)
        roots (mapv body public-value-order)
        statement (assoc body :proof/public-value-roots roots)]
    (assoc statement :research-observed-run-proof-statement/root (root statement))))

(defn- wire-value [x]
  (cond
    (keyword? x) {"$kw" (if-let [ns (namespace x)] (str ns "/" (name x)) (name x))}
    (map? x) {"$map" (mapv (fn [[k v]] [(wire-value k) (wire-value v)]) x)}
    (vector? x) (mapv wire-value x)
    (integer? x) x
    (or (string? x) (boolean? x) (nil? x)) x
    :else (throw (ex-info "Value outside observed-run SP1 witness domain"
                          {:value-type (type x)}))))

(defn sp1-input
  "Derive the guest witness from the two authoritative artifacts. The guest
   receives their canonical source bodies and directly referenced artifacts;
   no independent public-root tuple is included in prover input."
  [observed-run-evidence run-assurance-evidence]
  (derive-observed-run-proof-statement-v1 observed-run-evidence run-assurance-evidence)
  {"schema" schema
   "observed_run" (wire-value observed-run-evidence)
   "run_assurance" (wire-value run-assurance-evidence)})
