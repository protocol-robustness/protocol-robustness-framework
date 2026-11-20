(ns resolver-sim.protocols.sew.yield-capabilities-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.registry :as protocol-registry]
            [resolver-sim.protocols.sew.yield-capabilities :as yield-capabilities]))

(deftest no-yield-model-has-empty-state-invariant-results
  (with-redefs [protocol-registry/get-capability (fn [_ _] nil)]
    (is (= {:profile-id nil :archetype nil :module-id nil}
           (yield-capabilities/resolve-profile nil)))
    (is (false? (yield-capabilities/partial-yield-shortfall? nil nil)))
    (is (= 0 (yield-capabilities/sum-recognized-losses {} :USDC)))
    (is (= {:holds? true :violations []}
           (yield-capabilities/check-position-consistency {})))
    (is (= {:sew/yield-exposure {:holds? true :violations []}}
           (yield-capabilities/check-sew-invariants {})))))

(deftest yield-state-requires-the-selected-integration
  (with-redefs [protocol-registry/get-capability (fn [_ _] nil)]
    (testing "configured yield profiles fail closed"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires the yield integration"
                            (yield-capabilities/resolve-profile :aave-v3))))
    (testing "existing yield state is not silently ignored"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires the yield integration"
                            (yield-capabilities/sum-recognized-losses
                             {:yield/positions {:owner {:realized-yield 1}}}
                             :USDC)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires the yield integration"
                            (yield-capabilities/check-position-consistency
                             {:yield/positions {:owner {:status :active}}})))
      (is (false? (get-in (yield-capabilities/check-sew-invariants
                           {:yield/positions {:owner {:status :active}}})
                          [:sew/yield-exposure :holds?]))))))
