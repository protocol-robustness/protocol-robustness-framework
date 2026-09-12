(ns resolver-sim.benchmark.cancellation-transition-observation
  "Canonical research subject and result projections for cancellation transitions.

   The subject contains existing authoritative roots only: prior state,
   authorised command, and transition definition. Effects, state-after, receipt,
   and invariant roots are independently reported as the result projection."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const subject-schema-version "cancellation-transition-subject.v1")
(def ^:const result-schema-version "cancellation-transition-result.v1")

(defn transition-subject
  "Commit the ordered transition basis. Ordering is semantic: changing the
   predecessor, command, or transition definition changes the subject root."
  [input]
  (let [body {:schema-version subject-schema-version
              :prior-state/root (:prior-state/root input)
              :authorised-command/root (:authorised-command/root input)
              :transition-definition/root (:transition-definition/root input)}]
    (assoc body :subject/root
           (hc/domain-hash "cancellation-transition-subject.v1" body))))

(defn transition-result
  "Commit exact independently inspected outputs for a subject. No output root
   is trusted: the result root is derived from this projection."
  [subject output]
  (let [body {:schema-version result-schema-version
              :subject/root (:subject/root subject)
              :state-after/root (:state-after/root output)
              :receipt/root (:receipt/root output)
              :effects/root (:effects/root output)
              :invariants/root (:invariants/root output)}]
    (assoc body :result/root
           (hc/domain-hash "cancellation-transition-result.v1" body))))

(defn valid-subject?
  [subject]
  (= (:subject/root subject)
     (hc/domain-hash "cancellation-transition-subject.v1"
                     (dissoc subject :subject/root))))

(defn valid-result?
  [result]
  (= (:result/root result)
     (hc/domain-hash "cancellation-transition-result.v1"
                     (dissoc result :result/root))))
