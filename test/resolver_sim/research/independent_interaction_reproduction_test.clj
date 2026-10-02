(ns resolver-sim.research.independent-interaction-reproduction-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.coordinate-interaction :as original]
            [resolver-sim.research.interaction-reproduction :as replay]
            [resolver-sim.research.interaction-reproduction-test :as fixture]
            [resolver-sim.research.reproduction-execution-work :as work]
            [resolver-sim.research.reproduction-runner-test :as runner-fixture]
            [resolver-sim.research.runner-transport :as transport]))

(deftest separately-written-engine-reproduces-with-distinct-execution-identity
  (let [{:keys [input implementation original]} (fixture/fixture)
        second-impl (replay/contrast-implementation
                     (:resolved-research-dependency-set/root implementation) "1.0.0")
        first-run (replay/execute input implementation)
        second-run (replay/execute input second-impl)
        evidence (replay/compare-execution second-run original)]
    (is (not= (:implementation/artifact-root implementation)
              (:implementation/artifact-root second-impl)))
    (is (not= (:research-implementation-identity/root implementation)
              (:research-implementation-identity/root second-impl)))
    (is (not= (:reproduction-execution/root first-run)
              (:reproduction-execution/root second-run)))
    (is (= (:execution/fresh-result first-run) (:execution/fresh-result second-run)))
    (is (= :reproduced (:reproduction/status evidence)))
    (is (= second-run (replay/execute input second-impl)))
    ;; Exercise actual alternative code even when the original entry point is
    ;; unavailable. A version-only relabeling cannot pass this test.
    (is (= second-run
           (with-redefs [original/difference-of-differences
                         (fn [& _] (throw (ex-info "Original disabled" {})))]
             (replay/execute input second-impl))))
    (is (thrown? clojure.lang.ArityException
                 (apply replay/execute [input second-impl original])))
    (is (false? (replay/valid-implementation-for-input?
                 input (assoc second-impl :implementation/artifact-root
                              (:implementation/artifact-root implementation)))))
    (is (map? (replay/evidence-node second-run original evidence)))))

(deftest alternative-engine-conforms-over-signed-exact-values-and-statuses
  (let [{:keys [input implementation]} (fixture/fixture)
        second-impl (replay/contrast-implementation
                     (:resolved-research-dependency-set/root implementation) "1.0.0")
        base (:computation/input input)
        coordinates [:x1-y1 :x1-y2 :x2-y1 :x2-y2]
        numeric-inputs
        (for [a [-3 0 7 100000000000000000000N] b [-5 0 9] c [-1 0 4] d [-7 0 11]]
          (reduce (fn [args [coordinate value]]
                    (assoc-in args [:observations coordinate :observation/value] value))
                  base (map vector coordinates [a b c d])))
        unavailable-inputs
        [(-> base
             (assoc-in [:observations :x1-y1 :observation/status] :missing)
             (assoc-in [:observations :x1-y1 :observation/value] nil)
             (assoc-in [:observations :x1-y1 :observation/reason] :absent))
         (-> base
             (assoc-in [:observations :x2-y2 :observation/status] :not-comparable)
             (assoc-in [:observations :x2-y2 :observation/value] nil)
             (assoc-in [:observations :x2-y2 :observation/reason] :incompatible))
         (assoc-in base [:observations :x2-y1 :measure/id] :measure/other)
         (assoc-in base [:observations :x2-y1 :experiment/domain] :experiment/other)
         (assoc-in base [:observations :x2-y1 :accounting-policy/root]
                   (apply str "sha256:" (repeat 64 "f")))]]
    (doseq [args (concat numeric-inputs unavailable-inputs)]
      (let [replay-input (replay/build-input args)]
        (is (= (:execution/fresh-result (replay/execute replay-input implementation))
               (:execution/fresh-result (replay/execute replay-input second-impl))))))))

(deftest alternative-disagreement-and-invalid-axis-remain-visible
  (let [{:keys [input implementation original]} (fixture/fixture)
        second-impl (replay/contrast-implementation
                     (:resolved-research-dependency-set/root implementation) "1.0.0")
        execution (replay/execute input second-impl)
        wrong (assoc original :interaction/value 999)
        wrong (assoc wrong :research-coordinate-interaction/root
                     (original/interaction-root wrong))
        conflict (replay/compare-execution execution wrong)
        invalid (replay/build-input
                 (assoc-in (:computation/input input) [:x-axis :axis/kind] :researcher))]
    (is (= :counterexample-found (:reproduction/status conflict)))
    (is (= (:execution/fresh-result execution) (:reproduction/counterexample-result conflict)))
    (is (= execution (replay/execute input second-impl)))
    (is (= :failed (:execution/outcome (replay/execute invalid second-impl))))
    (is (= :not-comparable
           (:reproduction/status (replay/compare-execution execution {:artifact/schema "other"}))))))

(deftest runner-dispatch-executes-the-selected-independent-implementation
  (let [{:keys [job assignment input implementation participant original]} (runner-fixture/setup-artifacts)
        second-impl (replay/contrast-implementation
                     (:resolved-research-dependency-set/root implementation) "1.0.0")
        bundle (work/build-work {:job job :assignment assignment
                                 :runner-participation participant
                                 :reproduction-input input :implementation second-impl})
        coordinator (runner-fixture/start-coordinator assignment nil)
        runner (runner-fixture/start-runner
                {:participant participant}
                (transport/coordinator-endpoint coordinator runner-fixture/secret) nil)]
    (try
      (let [returned (transport/dispatch-reproduction!
                      coordinator {:endpoint (transport/coordinator-endpoint runner runner-fixture/secret 30000)
                                   :job job :assignment assignment :reproduction-work bundle
                                   :authoritative-result original})]
        (is (= (replay/execute input second-impl) (:reproduction-execution returned)))
        (is (= :reproduced (:reproduction/status (:reproduction-evidence returned))))
        (is (= 1 (count (:reproduction-results (transport/coordinator-state coordinator))))))
      (finally
        (transport/stop-runner! runner)
        (transport/stop-coordinator! coordinator)))))
