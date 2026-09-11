(ns resolver-sim.scenario.theory-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.scenario.theory :as theory]
            [resolver-sim.scenario.theory-result :as theory-result]))

(def base-result
  {:metrics {:m1 10 :m2 20 :attack-successes 0}
   :protocol (sew/->SewProtocol)
   :terminal-world {:total-held {:USDC 1000}}
   :trace []
   :events []
   :states {}})

(deftest test-suite-theory-engine
  (testing "Result contains canonical context envelope"
    (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :< :value 5}]})]
      (is (= :not-falsified (theory-result/result-status res)))
      (is (every? #(contains? res %) theory-result/canonical-keys))
      (is (= :evaluated (get-in res [:diagnostics :claim-status])))
      (is (= :complete (get-in res [:diagnostics :evidence-completeness])))
      (is (some? (:evidence res)))
      (is (nil? (:telemetry-evidence res))
          "flat telemetry evidence is opt-in"))))

(testing "All metrics missing → inconclusive (regression default)"
  (let [res (theory/evaluate-theory {:metrics {:m1 10} :protocol (sew/->SewProtocol) :terminal-world {}}
                                    {:falsifies-if [{:metric :m99 :op :> :value 5}]})]
    (is (= :inconclusive (:status res)))
    (is (= :metrics-missing-in-trace (:reason res)))
    (is (= [:m99] (get-in res [:diagnostics :missing-metrics])))))

(testing "Partial missing metric → inconclusive under regression profile"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if [{:metric :attack-successes :op :> :value 0}
                                                    {:metric :coalition/net-profit :op :> :value 0}]})]
    (is (= :inconclusive (:status res)))
    (is (= :evaluated (get-in res [:diagnostics :claim-status])))
    (is (= :not-falsified (get-in res [:diagnostics :falsification-status])))
    (is (= :partial (get-in res [:diagnostics :evidence-completeness])))
    (is (= :partial-metrics-missing (:reason res)))
    (is (= [:coalition/net-profit] (get-in res [:diagnostics :missing-metrics]))
        "slash metrics use metric-key for lookup")))

(testing "Partial missing but falsification observed → still falsified"
  (let [res (theory/evaluate-theory (assoc-in base-result [:metrics :attack-successes] 1)
                                    {:falsifies-if [{:metric :attack-successes :op :> :value 0}
                                                    {:metric :coalition/net-profit :op :> :value 0}]})]
    (is (= :falsified (:status res)))
    (is (true? (:falsified? res)))))

(testing "Optimistic profile allows partial missing → not-falsified but not grounded"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if [{:metric :attack-successes :op :> :value 0}
                                                    {:metric :coalition/net-profit :op :> :value 0}]}
                                    {:theory-eval-profile :optimistic})]
    (is (= :not-falsified (:status res)))
    (is (false? (get-in res [:diagnostics :grounded?])))
    (is (= :optimistic (get-in res [:diagnostics :theory-eval-profile])))
    (is (some #(= :ungrounded-optimistic-result (:kind %))
              (get-in res [:diagnostics :warnings])))))

(testing "Legacy :exploratory profile alias → :optimistic"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if [{:metric :m1 :op :> :value 100}]}
                                    {:theory-eval-profile :exploratory})]
    (is (= :optimistic (get-in res [:diagnostics :theory-eval-profile])))))

(testing "Strict profile: partial missing → inconclusive, not falsified"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if [{:metric :attack-successes :op :> :value 0}
                                                    {:metric :coalition/net-profit :op :> :value 0}]}
                                    {:theory-eval-profile :strict})]
    (is (= :inconclusive (:status res)))
    (is (= :strict-missing-metrics (:reason res))
        "missing telemetry is not contradiction evidence")
    (is (false? (:falsified? res)))
    (is (false? (ose/theory-result-ok? res :regression {:require-conclusive? true}))
        "strict CI: inconclusive fails the suite gate")))

(testing "Regression partial missing is grounded-false via inconclusive suite"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if [{:metric :attack-successes :op :> :value 0}
                                                    {:metric :coalition/net-profit :op :> :value 0}]})]
    (is (= :inconclusive (:status res)))
    (is (false? (get-in res [:diagnostics :grounded?])))))

(testing "Empty falsifies-if — no metric falsification claim"
  (let [res (theory/evaluate-theory base-result {:falsifies-if []})]
    (is (= :not-falsified (:status res)))
    (is (= :no-metric-falsification-claim (:reason res)))
    (is (= :not-applicable (get-in res [:diagnostics :claim-status])))))

(testing "Telemetry evidence only when requested"
  (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 5}]}
                                    {:include-telemetry-evidence? true})]
    (is (seq (:telemetry-evidence res)))
    (is (= :metric (:kind (first (:telemetry-evidence res)))))
    (is (= :m1 (:metric (first (:telemetry-evidence res)))))
    (is (= [:metrics :m1] (:source (first (:telemetry-evidence res)))))))

(testing "Declared assumptions are unchecked"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if [{:metric :m1 :op :> :value 5}]
                                     :assumptions [:honest-buyer]})]
    (is (= :unchecked (get-in res [:diagnostics :assumption-status])))
    (is (= [:honest-buyer] (get-in res [:diagnostics :declared-assumptions])))))

(testing "Human display labels (replay-local, not validation)"
  (let [falsified-res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 5}]})
        grounded-res  (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 100}]})
        optimistic    (theory/evaluate-theory base-result
                                              {:falsifies-if [{:metric :m1 :op :> :value 100}
                                                              {:metric :m99 :op :> :value 0}]}
                                              {:theory-eval-profile :optimistic})]
    (is (= "Falsified by this replay" (theory-result/result-display-label falsified-res)))
    (is (str/includes? (theory-result/result-display-label grounded-res) "Not falsified in this replay"))
    (is (str/includes? (theory-result/result-display-label optimistic) "optimistic"))
    (is (str/includes? (theory-result/result-display-label optimistic) "not audit-grade"))))

(testing "Always predicate"
  (let [trace [{:metrics {:m1 5}} {:metrics {:m1 10}}]
        res (theory/evaluate-theory (assoc base-result :trace trace)
                                    {:falsifies-if {:always {:metric :m1 :op :> :value 2}}})]
    (is (= :falsified (:status res)))))

(testing "Legacy flat list OR — triggered"
  (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 5}]})]
    (is (= :falsified (:status res)))))

(testing "Explicit {:and [...]} — not triggered"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if {:and [{:metric :m1 :op :> :value 100}
                                                          {:metric :m2 :op :> :value 100}]}})]
    (is (= :not-falsified (:status res)))))

(testing "Explicit {:or [...]} — one branch triggers"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if {:or [{:metric :m1 :op :> :value 5}
                                                         {:metric :m2 :op :> :value 100}]}})]
    (is (= :falsified (:status res)))))

(testing "Empty {:and []} → inconclusive"
  (let [res (theory/evaluate-theory base-result {:falsifies-if {:and []}})]
    (is (= :inconclusive (:status res)))
    (is (= :empty-logical-operator (:reason res)))))

(testing "Empty {:or []} → inconclusive"
  (let [res (theory/evaluate-theory base-result {:falsifies-if {:or []}})]
    (is (= :inconclusive (:status res)))
    (is (= :empty-logical-operator (:reason res)))))

(testing "State predicate"
  (let [res (theory/evaluate-theory base-result
                                    {:falsifies-if {:state {:query [:party/net-position {:party "buyer" :token :USDC}]
                                                            :op :>= :value 10}}})]
    (is (= :not-falsified (:status res)))))

(testing "Purpose theory-falsification expects falsified status"
  (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 5}]})]
    (is (= :falsified (:status res))
        "Positive falsification evidence regardless of scenario purpose field")))

(deftest test-three-way-model
  (testing "No theory → not evaluated"
    (let [res (theory/evaluate-theory base-result nil)]
      (is (= :not-evaluated (:status res)))
      (is (= :not-evaluated (get-in res [:diagnostics :claim-status])))
      (is (= :absent (get-in res [:diagnostics :evidence-completeness])))))

  (testing "Empty falsifies-if → not-required evidence, claim not-applicable"
    (let [res (theory/evaluate-theory base-result {:falsifies-if []})]
      (is (= :not-applicable (get-in res [:diagnostics :claim-status])))
      (is (= :not-required (get-in res [:diagnostics :evidence-completeness])))
      (is (= :not-applicable (get-in res [:diagnostics :falsification-status])))))

  (testing "Complete evidence not-falsified is grounded"
    (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 100}]})]
      (is (= :not-falsified (:status res)))
      (is (true? (get-in res [:diagnostics :grounded?])))))

  (testing "summarize defaults to thin canonical shape"
    (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 5}]})
          s   (theory-result/summarize res)]
      (is (every? #(contains? s %) [:status :reason :falsified? :diagnostics]))
      (is (not (contains? s :claim-status)))))

  (testing "summarize can include derived flat fields"
    (let [res (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 5}]})
          s   (theory-result/summarize res {:include-derived-statuses? true})]
      (is (contains? s :claim-status))))

  (testing "default omits legacy flat derived fields"
    (let [thin (theory/evaluate-theory base-result {:falsifies-if [{:metric :m1 :op :> :value 100}]})]
      (is (= :not-falsified (:status thin)))
      (is (nil? (:claim-status thin)))
      (is (= :evaluated (get-in thin [:diagnostics :claim-status]))))))

(deftest test-try-number-decimal
  (testing "try-number parses decimal strings"
    (is (= (double 0.5) (theory/try-number "0.5")))
    (is (= (double -3.14) (theory/try-number "-3.14"))))

  (testing "try-number parses integers"
    (is (= 100 (theory/try-number "100")))
    (is (= 0 (theory/try-number "0"))))

  (testing "try-number returns nil for non-numeric strings"
    (is (nil? (theory/try-number "abc")))
    (is (nil? (theory/try-number ""))))

  (testing "try-number passes through numbers unchanged"
    (is (= 42 (theory/try-number 42)))
    (is (= 1/2 (theory/try-number 1/2)))))

(deftest test-try-number-error-handling
  (testing "try-number handles null"
    (is (nil? (theory/try-number nil))))

  (testing "try-number handles empty string"
    (is (nil? (theory/try-number "")))))

(deftest test-metric-key-multi-segment
  (testing "metric-key handles multi-segment paths"
    (is (= :coalition/net-profit (theory/metric-key "coalition/net-profit"))))

  (testing "metric-key preserves standard namespaced keys"
    (is (= :coalition/net-profit (theory/metric-key "coalition/net-profit"))))

  (testing "metric-key handles keywords directly"
    (is (= :coalition/net-profit (theory/metric-key :coalition/net-profit)))))

(deftest test-after-trace-scope
  (testing ":after temporal operator scopes inner :always to post-event window"
    (let [trace [{:action "release" :metrics {:x 0}}
                 {:action "step1"   :metrics {:x 5}}
                 {:action "step2"   :metrics {:x 10}}]
          ;; :after should only check events AFTER "release": step1 (x=5), step2 (x=10)
          ;; :always {:metric :x :op :> :value 8} = (5>8 false, 10>8 true) = false
          ;; For comparison, if it incorrectly used the full trace, it would include
          ;; the release event itself (x=0 > 8) = false, same result.
          ;; Use :> :value 4 to test the true path: (5>4 true, 10>4 true) = true
          ;; falsifies-if is TRUE → theory IS falsified.
          result (theory/evaluate-theory (assoc base-result :trace trace)
                                         {:falsifies-if
                                          {:after {:event "release"
                                                   :predicate {:always {:metric :x :op :> :value 4}}}}})]
      (is (= :falsified (:status result))
          "Should falsify: after release, all x values > 4"))))

(deftest test-after-trace-scope-correction
  (testing ":after correctly excludes pre-event events"
    (let [trace [{:action "pre" :metrics {:x 0}}
                 {:action "release" :metrics {:x 0}}
                 {:action "step1" :metrics {:x 5}}]
          result (theory/evaluate-theory (assoc base-result :trace trace)
                                         {:falsifies-if
                                          {:after {:event "release"
                                                   :predicate {:always {:metric :x :op :> :value 3}}}}})]
      (is (= :falsified (:status result))
          "Scope correctly excludes pre-release event: x=5 > 3 after release"))))

(deftest test-before-trace-scope
  (testing ":before temporal operator scopes inner :eventually to pre-event window"
    (let [trace [{:action "step1" :metrics {:x 5}}
                 {:action "step2" :metrics {:x 10}}
                 {:action "release" :metrics {:x 0}}]
          result (theory/evaluate-theory (assoc base-result :trace trace)
                                         {:falsifies-if
                                          {:before {:event "release"
                                                    :predicate {:eventually {:metric :x :op :> :value 8}}}}})]
      (is (= :falsified (:status result))
          "Should falsify: before release, step2 has x=10 > 8"))))

(deftest test-before-trace-scope-correction
  (testing ":before correctly excludes post-event events"
    (let [trace [{:action "step1" :metrics {:x 100}}
                 {:action "release" :metrics {:x 0}}
                 {:action "step2" :metrics {:x 10}}]
          result (theory/evaluate-theory (assoc base-result :trace trace)
                                         {:falsifies-if
                                          {:before {:event "release"
                                                    :predicate {:always {:metric :x :op :> :value 50}}}}})]
      (is (= :falsified (:status result))
          "Scope correctly excludes post-release events: x=100 > 50 before release"))))

(deftest test-before-trace-scope
  (testing ":before temporal operator scopes inner :eventually to pre-event window"
    (let [trace [{:action "step1"   :metrics {:x 5}}
                 {:action "step2"   :metrics {:x 10}}
                 {:action "release" :metrics {:x 0}}]
          ;; :before should only check events BEFORE "release": step1 (x=5), step2 (x=10)
          ;; :eventually {:metric :x :op :> :value 8} should find step2 (10>8) → true
          ;; falsifies-if is TRUE → theory IS falsified.
          result (theory/evaluate-theory (assoc base-result :trace trace)
                                         {:falsifies-if
                                          {:before {:event "release"
                                                    :predicate {:eventually {:metric :x :op :> :value 8}}}}})
          eval-tree (first (:evidence result))]
      (is (= :falsified (:status result))
          "Should falsify: before release, step2 has x=10 > 8"))
    ;; Test scope correctness:
    (let [trace [{:action "step1"   :metrics {:x 100}}
                 {:action "release" :metrics {:x 0}}
                 {:action "step2"   :metrics {:x 10}}]
          result (theory/evaluate-theory (assoc base-result :trace trace)
                                         {:falsifies-if
                                          {:before {:event "release"
                                                    :predicate {:always {:metric :x :op :> :value 50}}}}})]
      (is (= :falsified (:status result))
          (str "Should falsify: before release, step1 x=100 > 50."
               " If... would cause :always to fail.")))))

(deftest test-state-predicate-missing-query
  (testing "State predicate with nil projection flags missing-query-result?"
    (let [result (theory/evaluate-theory base-result
                                         {:falsifies-if {:state {:query [:party/net-position {:party "nonexistent" :token :USDC}]
                                                                 :op :>= :value 10}}})]
      (is (= :not-falsified (:status result)))
      (is (some? (:evidence result)))
      (let [leaf (first (get-in result [:diagnostics :evaluated-predicates]))]
        (when leaf
          (is (contains? leaf :missing-query-result?)))))))

