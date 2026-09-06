(ns resolver-sim.settings.semantic-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.settings.semantic :as sut]))

(def owner :example.application/settlement-v1)

(deftest owned-setting-is-rooted-and-valid
  (let [setting (sut/build-setting {:setting/id :example.setting/limit
                                    :setting/value {:limit 10}
                                    :setting/owner-id owner})]
    (is (:valid? (sut/validate-setting setting)))
    (is (= owner (:setting/owner-id setting)))))

(deftest missing-owner-is-rejected
  (let [setting {:setting/schema sut/schema
                 :setting/id :example.setting/limit
                 :setting/value 10
                 :setting/root "sha256:0000000000000000000000000000000000000000000000000000000000000000"}]
    (is (some #{:setting/missing-or-invalid-owner}
              (:errors (sut/validate-setting setting))))))

(deftest owner-substitution-changes-root
  (let [a (sut/build-setting {:setting/id :example.setting/limit
                              :setting/value 10
                              :setting/owner-id :example.application/a-v1})
        b (sut/build-setting {:setting/id :example.setting/limit
                              :setting/value 10
                              :setting/owner-id :example.application/b-v1})]
    (is (not= (:setting/root a) (:setting/root b)))
    (is (not (:valid? (sut/validate-setting (assoc a :setting/owner-id :example.application/b-v1)))))))

(deftest owner-must-be-bound-to-application-capability
  (let [setting (sut/build-setting {:setting/id :example.setting/limit
                                    :setting/value 10
                                    :setting/owner-id owner})]
    (is (sut/owner-bound-to-application?
         setting [{:capability/id owner}]))
    (is (not (sut/owner-bound-to-application?
              setting [{:capability/id :example.application/other-v1}])))))

(deftest runtime-only-settings-need-not-use-semantic-setting-contract
  (testing "Operational configuration remains outside semantic ownership."
    (is (not (sut/owner-bound-to-application?
              {:runtime/setting :framing/max-stream-bytes}
              [{:capability/id owner}])))))
