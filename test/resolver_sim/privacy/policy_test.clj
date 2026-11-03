(ns resolver-sim.privacy.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.privacy.policy :as policy]))

(def asset-root (str "sha256:" (apply str (repeat 64 "a"))))
(defn rule [id effect]
  {:rule/id id :rule/effect effect
   :rule/data-categories ["aggregate"] :rule/operations ["publish"]
   :rule/purposes ["reporting"] :rule/recipients ["public"]
   :rule/sinks [:encrypted-bundle] :rule/max-sensitivity :sensitivity/public
   :rule/max-retention-seconds 3600 :rule/required-evidence []})
(defn fixture-policy [& rules]
  (policy/build-policy
   {:privacy-policy/id "fixture-policy" :privacy-policy/version 1
    :privacy-policy/issuer "did:fixture:issuer" :privacy-policy/asset-root asset-root
    :privacy-policy/jurisdictions ["ZZ"] :privacy-policy/valid-from 100
    :privacy-policy/valid-until 200 :privacy-policy/rules (vec rules)
    :privacy-policy/authority-ref "fixture-authority-root"}))
(def request
  {:request/issuer "did:fixture:issuer" :request/asset-root asset-root
   :request/jurisdiction "ZZ" :request/at 150 :request/data-category "aggregate"
   :request/operation "publish" :request/purpose "reporting"
   :request/recipient "public" :request/sink :encrypted-bundle
   :request/sensitivity :sensitivity/public :request/retention-seconds 300
   :request/verified-evidence []})

(deftest policy-contract
  (testing "canonical root validates and is stable"
    (let [p (fixture-policy (rule "allow-aggregate" :allow))]
      (is (:valid? (policy/validate-policy p)))
      (is (= (:privacy-policy/root p) (:privacy-policy/root (fixture-policy (rule "allow-aggregate" :allow)))))))
  (testing "unknown keys, invalid retention, and tampering fail"
    (let [p (fixture-policy (rule "allow-aggregate" :allow))]
      (is (false? (:valid? (policy/validate-policy (assoc p :unexpected true)))))
      (is (thrown? Exception (fixture-policy (assoc (rule "bad" :allow) :rule/max-retention-seconds -1))))
      (is (false? (:valid? (policy/validate-policy (assoc p :privacy-policy/id "changed")))))
      (testing "malformed non-vector rules fail closed without throwing"
        (let [malformed (assoc p :privacy-policy/rules 7)
              malformed (assoc malformed :privacy-policy/root (policy/policy-root malformed))]
          (is (false? (:valid? (policy/validate-policy malformed)))))))))

(deftest offline-evaluation
  (testing "matching policy permits but does not assert authority or execution"
    (let [result (policy/evaluate (fixture-policy (rule "allow-aggregate" :allow)) request)]
      (is (= :allow (:privacy-evaluation/decision result)))
      (is (= :unverified (:privacy-evaluation/authority-status result)))
      (is (= :unknown (:privacy-evaluation/currentness result)))
      (is (= :not-executed (:privacy-evaluation/execution result)))
      (is (false? (:privacy-evaluation/usable? result)))))
  (testing "deny overrides allow and request context is bound"
    (is (= :deny (:privacy-evaluation/decision
                  (policy/evaluate (fixture-policy (rule "allow" :allow) (rule "deny" :deny)) request))))
    (is (= :deny (:privacy-evaluation/decision
                           (policy/evaluate (fixture-policy (rule "allow" :allow))
                                            (assoc request :request/recipient "other"))))))
  (testing "retention and required evidence fail closed"
    (let [r (assoc (rule "allow" :allow) :rule/max-retention-seconds 20
                   :rule/required-evidence ["consent"])]
      (is (= :deny (:privacy-evaluation/decision
                    (policy/evaluate (fixture-policy r) request))))))
  (testing "public sink requires a signed remote sentinel decision"
    (let [p (fixture-policy (assoc (rule "allow" :allow) :rule/sinks [:public-bundle]))
          result (policy/evaluate p (assoc request :request/sink :public-bundle))]
      (is (= :indeterminate (:privacy-evaluation/decision result)))
      (is (= :remote-decision-required (:privacy-evaluation/sentinel-decision result)))
      (is (false? (:privacy-evaluation/usable? result)))))
  (testing "a mismatched remote decision is rejected"
    (let [p (fixture-policy (assoc (rule "allow" :allow) :rule/sinks [:public-bundle]))
          req (assoc request :request/sink :public-bundle :request/id "fixture-request"
                     :request/artifact {:artifact/kind "redacted-view"})
          result (policy/evaluate p req {:sentinel-decision {:artifact/kind :forged}
                                         :sentinel-trust-policy {}})]
      (is (= :indeterminate (:privacy-evaluation/decision result)))
      (is (= :unexpected-decision-kind (:privacy-evaluation/sentinel-decision result)))
      (is (false? (:privacy-evaluation/usable? result))))))
