(ns resolver-sim.protocols.sew.resolution-decision-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.resolution-basis :as basis]
            [resolver-sim.protocols.sew.resolution-decision :as decision]))

(defn- root [x] (str "sha256:" (hc/domain-hash :evidence-record {:x x})))
(defn- basis-artifact []
  (basis/build-resolution-basis
   {:workflow/id 0 :escrow/root (root :escrow) :state-before/root (root :state)
    :dispute/question-root (root :question) :dispute/root (root :dispute)
    :dispute/evidence-set-root (root :evidence-set)
    :authority/resolution-basis-root (root :governed) :authority/context-root (root :context)
    :resolver/root (root :resolver) :resolver/kind :direct :resolver/authorization-root (root :authorization)
    :resolution-module/root (root :module) :resolution-module/snapshot-root (root :snapshot)
    :configuration/root (root :configuration) :resolution/authority-mode :primary}))
(defn- input [b]
  {:resolution-basis/root (:sew-resolution-basis/root b) :workflow/id (:workflow/id b)
   :state-before/root (:state-before/root b) :decision/outcome :release
   :decision-inputs/root (root :inputs) :evidence/root (root :evidence)
   :resolver/root (:resolver/root b) :resolution-module/root (:resolution-module/root b)
   :resolution-module/snapshot-root (:resolution-module/snapshot-root b)})

(deftest decision-is-rooted-and-basis-bound
  (let [b (basis-artifact) d (decision/build-resolution-decision (input b))]
    (is (decision/valid-resolution-decision? d))
    (is (:valid? (decision/validate-decision-against-basis d b)))
    (is (not= (:sew-resolution-decision/root d)
              (:sew-resolution-decision/root (decision/build-resolution-decision (assoc (input b) :decision/outcome :refused)))))
    (is (not (:valid? (decision/validate-decision-against-basis (assoc d :workflow/id 1) b))))
    (is (not (:valid? (decision/validate-decision-against-basis (assoc d :resolver/root (root :other)) b))))))

(deftest decision-rejects-opaque-or-extra-outcome-data
  (let [d (decision/build-resolution-decision (input (basis-artifact)))]
    (is (not (decision/valid-resolution-decision? (assoc d :decision/outcome :arbitrary))))
    (is (not (decision/valid-resolution-decision? (assoc d :decision/freeform "opaque"))))))
