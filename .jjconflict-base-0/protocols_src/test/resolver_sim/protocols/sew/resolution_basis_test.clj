(ns resolver-sim.protocols.sew.resolution-basis-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lifecycle]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
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

(defn- external-roots []
  {:dispute/question-root (root :frozen-question)
   :dispute/evidence-set-root (root :frozen-evidence-set)
   :authority/resolution-basis-root (root :governed-resolution-basis)
   :authority/context-root (root :authority-context)
   :configuration/root (root :configuration)})

(defn- disputed-world []
  (let [created (lifecycle/create-escrow
                 (t/empty-world 1000) "0xbuyer" "0xUSDC" "0xseller" 1000
                 (t/make-escrow-settings {})
                 (snap-fix/escrow-snapshot {:escrow-fee-bps 50
                                             :resolver-response-window 120}))]
    (-> (:world created)
        (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] "0xresolver")
        (assoc-in [:dispute-timestamps 0] 1000))))

(defn- reconstructed-root [world roots]
  (get-in (basis/reconstruct-live-resolution-basis world 0 roots)
          [:basis :sew-resolution-basis/root]))

(deftest live-reconstruction-binds-world-and-frozen-external-inputs
  (let [world (disputed-world)
        roots (external-roots)
        original (reconstructed-root world roots)]
    (is (some? original))
    (doseq [[label world' roots']
            [[:escrow (assoc-in world [:escrow-transfers 0 :to] "0xother") roots]
             [:state (assoc-in world [:params :resolution-test] :changed) roots]
             [:dispute-level (assoc-in world [:dispute-levels 0] 1) roots]
             [:resolver (assoc-in world [:escrow-transfers 0 :dispute-resolver] "0xother-resolver") roots]
             [:module-snapshot (assoc-in world [:module-snapshots 0 :resolution-module] "module-v2") roots]
             [:fallback-mode (assoc-in world [:context/time :block-ts] 1121) roots]
             [:question world (assoc roots :dispute/question-root (root :other-question))]
             [:evidence world (assoc roots :dispute/evidence-set-root (root :other-evidence))]
             [:authority world (assoc roots :authority/context-root (root :other-authority))]
             [:configuration world (assoc roots :configuration/root (root :other-configuration))]]]
      (is (not= original (reconstructed-root world' roots'))
          (str label " must change the reconstructed basis root")))))

(deftest live-reconstruction-reports-missing-external-frozen-inputs
  (let [result (basis/reconstruct-live-resolution-basis (disputed-world) 0 {})]
    (is (false? (:valid? result)))
    (is (= [:missing-external-live-root] (:errors result)))
    (is (= #{:dispute/question-root :dispute/evidence-set-root
             :authority/resolution-basis-root :authority/context-root :configuration/root}
           (set (:missing-roots result))))))

(deftest malformed-and-tampered-bases-fail-validation
  (let [artifact (basis/build-resolution-basis (valid-input))]
    (is (not (basis/valid-resolution-basis?
              (assoc artifact :resolver/authorization-root "not-a-root"))))
    (is (not (basis/valid-resolution-basis?
              (assoc artifact :dispute/root (root :tampered-dispute)))))
    (is (not (basis/valid-resolution-basis?
              (dissoc artifact :authority/context-root))))))
