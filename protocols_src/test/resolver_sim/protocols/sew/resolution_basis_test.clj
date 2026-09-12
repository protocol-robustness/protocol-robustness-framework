(ns resolver-sim.protocols.sew.resolution-basis-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.resolution-basis :as basis]))

(defn- root [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label})))

(defn- valid-input []
  {:workflow/id 0
   :escrow/root (root :escrow)
   :state-before/root (root :state-before)
   :dispute/question-root (root :question)
   :dispute/root (root :dispute)
   :dispute/evidence-set-root (root :evidence-set)
   :authority/resolution-basis-root (root :governed-basis)
   :authority/context-root (root :authority-context)
   :resolver/root (root :resolver)
   :resolver/kind :direct
   :resolver/authorization-root (root :authorization)
   :resolution-module/root (root :module)
   :resolution-module/snapshot-root (root :module-snapshot)
   :configuration/root (root :configuration)
   :resolution/authority-mode :primary})

(deftest basis-is-closed-outcome-free-and-rooted
  (let [artifact (basis/build-resolution-basis (valid-input))]
    (is (= basis/schema (:artifact/schema artifact)))
    (is (= basis/purpose (:resolution/purpose artifact)))
    (is (basis/valid-resolution-basis? artifact))
    (is (= (:sew-resolution-basis/root artifact)
           (basis/resolution-basis-root artifact)))
    (testing "outcomes belong to decisions, never authority bases"
      (let [with-outcome (assoc artifact :decision/outcome :release)]
        (is (not (basis/valid-resolution-basis? with-outcome)))
        (is (some #{:unknown-basis-field}
                  (:errors (basis/validate-resolution-basis with-outcome))))))))

(deftest basis-root-binds-dispute-authority-and-immutable-semantics
  (let [artifact (basis/build-resolution-basis (valid-input))
        reroot (fn [key value]
                 (basis/build-resolution-basis
                  (assoc (dissoc artifact :artifact/schema :resolution/purpose
                                 :sew-resolution-basis/root)
                         key value)))]
    (doseq [[key value] [[:workflow/id 1]
                         [:state-before/root (root :other-state)]
                         [:dispute/root (root :other-dispute)]
                         [:resolver/root (root :other-resolver)]
                         [:resolution-module/snapshot-root (root :other-module-snapshot)]
                         [:resolution/authority-mode :fallback]]]
      (is (not= (:sew-resolution-basis/root artifact)
                (:sew-resolution-basis/root (reroot key value)))
          (str key " must be committed by the basis root")))))

(deftest malformed-and-tampered-bases-fail-validation
  (let [artifact (basis/build-resolution-basis (valid-input))]
    (is (not (basis/valid-resolution-basis?
              (assoc artifact :resolver/authorization-root "not-a-root"))))
    (is (not (basis/valid-resolution-basis?
              (assoc artifact :dispute/root (root :tampered-dispute)))))
    (is (not (basis/valid-resolution-basis?
              (dissoc artifact :authority/context-root))))))
