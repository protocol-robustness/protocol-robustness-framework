(ns resolver-sim.cancellation.transition-join
  "7B join between cancellation-operation.v1 and command-lineage termination.
   This is a verification projection, not a second cancellation state model."
  (:require [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.composition.command-lineage :as lineage]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "cancellation-transition-join.v1")

(defn subject
  "Build the minimal research subject from existing authoritative roots."
  [input]
  {:schema-version schema-version
   :transition-definition/root (:transition-definition/root input)
   :state-before/root (:state-before/root input)
   :authorization/root (:authorization/root input)
   :preconditions/root (:preconditions/root input)})

(defn subject-root [s]
  (hash-ref/sha256-ref
   (hc/domain-hash "cancellation-transition-subject.v1" s)))

(defn result
  "Bind derived effects, state-after, and terminal receipt to a subject."
  [s output]
  (let [body {:schema-version schema-version
              :subject/root (subject-root s)
              :effects/root (:effects/root output)
              :state-after/root (:state-after/root output)
              :receipt/root (:receipt/root output)}]
    (assoc body :result/root
           (hash-ref/sha256-ref
            (hc/domain-hash "cancellation-transition-result.v1" body)))))

(defn verify-join
  "Verify operation/terminator/result binding. Caller-supplied derived roots
   cannot replace the roots already committed by operation or receipt."
  [operation-map terminator head receipt subject result]
  (let [issues (cond-> []
                 (not (operation/operation-root-valid? operation-map))
                 (conj :operation-root-invalid)
                 (not (:valid? (lineage/verify-command terminator)))
                 (conj :terminator-invalid)
                 (not (:valid? (lineage/verify-termination-receipt receipt head terminator)))
                 (conj :receipt-invalid)
                 (not= (:command/root head) (:termination/predecessor-root receipt))
                 (conj :receipt-command-mismatch)
                 (not= (get-in operation-map [:target :state-before-root])
                       (:state-before/root subject))
                 (conj :state-before-mismatch)
                 (not= (get-in operation-map [:authorization :root])
                       (:authorization/root subject))
                 (conj :authorization-mismatch)
                 (not= (get-in operation-map [:preconditions/root])
                       (:preconditions/root subject))
                 (conj :preconditions-mismatch)
                 (not (hash-ref/valid-sha256-ref?
                       (:transition-definition/root operation-map)))
                 (conj :transition-definition-missing-or-invalid)
                 (not= (:transition-definition/root operation-map)
                       (:transition-definition/root subject))
                 (conj :transition-definition-mismatch)
                 (not= (subject-root subject)
                       (:subject/root result))
                 (conj :result-subject-mismatch)
                 (not= (get-in operation-map [:execution :effects-root])
                       (:effects/root result))
                 (conj :effects-mismatch)
                 (not= (get-in operation-map [:execution :state-after-root])
                       (:state-after/root result))
                 (conj :state-after-mismatch)
                 (not= (:termination/root receipt) (:receipt/root result))
                 (conj :receipt-mismatch))]
    {:valid? (empty? issues) :issues issues
     :subject/root (subject-root subject)
     :result/root (:result/root result)}))
