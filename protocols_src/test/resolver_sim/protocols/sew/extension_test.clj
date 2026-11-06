(ns resolver-sim.protocols.sew.extension-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.registry :as protocols]
            [resolver-sim.protocols.sew.slash-evidence :as slash-evidence]))

(deftest protocol-registry-bootstraps-selected-sew-evidence-extension
  (is (some? (protocols/get-protocol "sew-v1")))
  (is (some? (get-method slash-evidence/build-prorata-slash-evidence
                         :resolver-sim.protocols.sew.slash-evidence/pro-rata-slash)))
  (is (some? (get-method slash-evidence/ensure-prorata-slash-evidence-loaded!
                         :resolver-sim.protocols.sew.slash-evidence/pro-rata-slash)))
  (is (some? (get-method slash-evidence/write-allocation-result-artifact!
                         :resolver-sim.protocols.sew.slash-evidence/pro-rata-slash)))
  (is (fn? (protocols/get-capability "sew-v1" :reference-validation/replay)))
  (let [checker (protocols/get-capability
                 "sew-v1" :reference-validation/invariant-checker)]
    (is (set? (:world-invariant-ids checker)))
    (is (fn? (:check-all-fn checker))))
  (is (fn? (protocols/get-capability
            "sew-v1" :runner/export-trace-fixture)))
  (is (fn? (protocols/get-capability
            "sew-v1" :replay/appeal-bond-transition-receipt)))
  (is (fn? (protocols/get-capability
            "sew-v1" :replay/finalize-appeal-bond)))
  (is (fn? (protocols/get-capability "sew-v1" :runner/replay)))
  (is (fn? (protocols/get-capability "sew-v1" :runner/summarise-batch)))
  (is (fn? (protocols/get-capability
            "sew-v1" :force-authorisation/validate-consensus)))
  (is (fn? (protocols/get-capability
            "sew-v1" :force-authorisation/build-reservation)))
  (is (fn? (protocols/get-capability "sew-v1" :analysis/trace-projection)))
  (is (fn? (protocols/get-capability "sew-v1" :analysis/yield-evidence)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/apply-settlement-policy)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/check-sew-invariants)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/init-world)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/apply-op)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/resolve-profile)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/resolve-module-id)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/apply-market-shock)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/check-expectations)))
  (is (set? (protocols/get-capability "sew-v1" :yield/liquidity-modes)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/partial-yield-shortfall?)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/apply-yield-op)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/position-identity)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/filled-total)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/validate-partial-fill-application!)))
  (is (fn? (protocols/get-capability "sew-v1" :yield/apply-partial-fill)))
  (is (map? (protocols/get-capability
             "sew-v1" :analysis/mechanism-property-validators))))
