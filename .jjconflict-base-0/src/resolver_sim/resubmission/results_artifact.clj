(ns resolver-sim.resubmission.results-artifact
  "Closed content identity for results submitted with a resubmission attempt."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema "attempt-results-artifact.v1")
(def domain :prf-attempt-results-artifact-v1)

(defn projection [artifact]
  (select-keys artifact [:artifact/schema :results/verifier-id :results/evaluation-root
                         :results/certificate-root :results/execution-evidence-root]))

(defn root [artifact]
  (ref/sha256-ref (hc/domain-hash domain (projection artifact))))

(defn valid? [artifact]
  (and (map? artifact)
       (= #{:artifact/schema :results/verifier-id :results/evaluation-root
            :results/certificate-root :results/execution-evidence-root
            :attempt-results-artifact/root}
          (set (keys artifact)))
       (= schema (:artifact/schema artifact))
       (string? (:results/verifier-id artifact))
       (ref/valid-sha256-ref? (:results/evaluation-root artifact))
       (ref/valid-sha256-ref? (:results/certificate-root artifact))
       (ref/valid-sha256-ref? (:results/execution-evidence-root artifact))
       (= (:attempt-results-artifact/root artifact) (root artifact))))

(defn build [artifact]
  (let [built (assoc artifact :artifact/schema schema)]
    (assoc built :attempt-results-artifact/root (root built))))
