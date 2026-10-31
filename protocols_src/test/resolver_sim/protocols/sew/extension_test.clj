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
                         :resolver-sim.protocols.sew.slash-evidence/pro-rata-slash))))
