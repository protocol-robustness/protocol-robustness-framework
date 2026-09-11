(ns resolver-sim.protocols.sew.claimable-classification-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.claimable-classification :as cc]
            [resolver-sim.protocols.sew.projection :as proj]))

(deftest build-document-taxonomy-only
  (is (= "taxonomy-only" (:observations_status (cc/build-document :observations-status "taxonomy-only"))))
  (is (nil? (:terminal_observations (cc/build-document :observations-status "taxonomy-only")))))

(deftest build-document-provenance
  (let [doc (cc/build-document :observations-status "taxonomy-only"
                               :provenance {:run_id "r1" :git_sha "abc" :producer "test"})]
    (is (= "r1" (get-in doc [:provenance :run_id])))
    (is (= "abc" (get-in doc [:provenance :git_sha])))))

(deftest scenario-id-derived-from-result-path
  (testing "from-result uses file stem when result JSON lacks scenario-id"
    (let [result-path "results/evidence/kleros-preemptive-escalation-rejected-l0.result.json"
          identity (cc/resolve-scenario-identity {} :result-path result-path)]
      (is (= "kleros-preemptive-escalation-rejected-l0" (:scenario-id identity)))
      (is (= "derived-from-result-path" (:scenario-id-status identity))))))

(deftest funds-ledger-view-no-duplicate-token-keys
  (testing "keyword and string token keys collapse to one USDC bucket"
    (let [world {:total-held {:USDC 100}
                 :total-released {"USDC" 50}
                 :escrow-transfers {0 {:token :USDC}}
                 :claimable {0 {"0x1" 0}}
                 :claimable-v2 {}}
          view (proj/funds-ledger-view world)]
      (is (= 1 (count (:by-token view))))
      (is (= 100 (get-in view [:by-token "USDC" :held])))
      (is (= 50 (get-in view [:by-token "USDC" :released]))))))
