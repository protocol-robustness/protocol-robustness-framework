(ns resolver-sim.privacy.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.signed-external-decision :as signed-decision]
            [resolver-sim.support.ed25519 :as fx]))

(def asset-root (str "sha256:" (apply str (repeat 64 "a"))))
(def artifact-root (str "sha256:" (apply str (repeat 64 "c"))))
(def consent-root (str "sha256:" (apply str (repeat 64 "d"))))
(def classifier-key (fx/keypair :fixture-classifier))
(def evidence-key (fx/keypair :fixture-evidence-verifier))
(def classifier-trust (fx/trust-policy classifier-key :sensitivity-classifier :active))
(def evidence-trust (fx/trust-policy evidence-key :privacy-evidence-verifier :active))

(def sensitivity-report-body
  {:schema-version "sensitivity-report.v2"
   :run-id "fixture-run"
   :profile "internal"
   :decision "allowed"
   :run-level "public"
   :findings []
   :scenario-count 1
   :sensitive-scenario-count 0
   :scenarios []})
(def sensitivity-report-hash
  (hc/hash-with-intent {:hash/intent :evidence-record} sensitivity-report-body))
(def sensitivity-report
  (assoc sensitivity-report-body
         :report-hash sensitivity-report-hash
         :report/semantic-hash sensitivity-report-hash
         :evaluated-at "fixture-time"))
(def sensitivity-report-root (str "sha256:" sensitivity-report-hash))

(defn rule [id effect]
  {:rule/id id :rule/effect effect
   :rule/data-categories ["aggregate"] :rule/operations ["publish"]
   :rule/purposes ["reporting"] :rule/recipient-ids ["did:fixture:recipient"]
   :rule/recipient-classes ["public"] :rule/sinks [:encrypted-bundle]
   :rule/max-sensitivity :sensitivity/public
   :rule/max-retention-seconds 3600 :rule/required-evidence []})

(defn fixture-policy [& rules]
  (policy/build-policy
   {:privacy-policy/id "fixture-policy" :privacy-policy/version 1
    :privacy-policy/issuer "did:fixture:issuer" :privacy-policy/asset-root asset-root
    :privacy-policy/jurisdictions ["ZZ"] :privacy-policy/valid-from 100
    :privacy-policy/valid-until 200 :privacy-policy/rules (vec rules)
    :privacy-policy/authority-ref "fixture-authority-reference"}))

(def request
  (policy/build-request
   {:request/id "fixture-request"
    :request/artifact-root artifact-root
    :request/classification-report-root sensitivity-report-root
    :request/issuer "did:fixture:issuer" :request/asset-root asset-root
    :request/jurisdiction "ZZ" :request/at 150 :request/data-category "aggregate"
    :request/operation "publish" :request/purpose "reporting"
    :request/recipient-id "did:fixture:recipient" :request/recipient-class "public"
    :request/sink :encrypted-bundle :request/retention-seconds 300
    :request/evidence []}))

(defn classification-attestation [req]
  (signed-decision/sign-envelope
   {:classification/schema "stablecoin-classification-attestation.v1"
    :classification/artifact-root (:request/artifact-root req)
    :classification/report-root (:request/classification-report-root req)
    :classification/level :sensitivity/public}
   policy/classification-domain (:private-key classifier-key) (:key/id classifier-key)))

(defn evidence-attestation [root evidence-type]
  (signed-decision/sign-envelope
   {:evidence/schema "stablecoin-evidence-attestation.v1"
    :evidence/root root :evidence/type evidence-type :evidence/status :verified}
   policy/evidence-domain (:private-key evidence-key) (:key/id evidence-key)))

(defn evaluation-options [req]
  {:classification-report sensitivity-report
   :classification-attestation (classification-attestation req)
   :classification-trust-policy classifier-trust
   :evidence-attestations []
   :evidence-trust-policy evidence-trust})

(deftest policy-contract
  (testing "policy roots are stable and closed shape is enforced"
    (let [p (fixture-policy (rule "allow-aggregate" :allow))]
      (is (:valid? (policy/validate-policy p)))
      (is (= (:privacy-policy/root p)
             (:privacy-policy/root (fixture-policy (rule "allow-aggregate" :allow)))))
      (is (false? (:valid? (policy/validate-policy (assoc p :unexpected true)))))
      (is (false? (:valid? (policy/validate-policy
                            (assoc p :privacy-policy/id "changed")))))))
  (testing "malformed non-vector rules fail closed without throwing"
    (let [p (fixture-policy (rule "allow-aggregate" :allow))
          malformed (assoc p :privacy-policy/rules 7)
          malformed (assoc malformed :privacy-policy/root (policy/policy-root malformed))]
      (is (false? (:valid? (policy/validate-policy malformed)))))))

(deftest request-is-rooted-and-recipient-fields-are-distinct
  (is (:valid? (policy/validate-request request)))
  (is (false? (:valid? (policy/validate-request (assoc request :request/purpose "other")))))
  (is (false? (:valid? (policy/validate-request (assoc request :request/unexpected true)))))
  (is (not= (:request/root request)
            (:request/root (policy/build-request
                            (dissoc (assoc request :request/recipient-class "internal")
                                    :request/schema :request/root))))))

(deftest classification-and-evaluation-receipts-bind-the-exact-request
  (let [p (fixture-policy (rule "allow-aggregate" :allow))
        result (policy/evaluate p request (evaluation-options request))]
    (is (= :allow (:privacy-evaluation/decision result)))
    (is (= (:request/root request) (:privacy-evaluation/request-root result)))
    (is (= artifact-root (:privacy-evaluation/artifact-root result)))
    (is (= :verified (:privacy-evaluation/classification-status result)))
    (is (= :unverified (:privacy-evaluation/authority-status result)))
    (is (= :unknown (:privacy-evaluation/currentness result)))
    (is (= :not-executed (:privacy-evaluation/execution result)))
    (is (false? (:privacy-evaluation/usable? result)))
    (is (policy/verify-evaluation result))
    (is (false? (policy/verify-evaluation
                 (assoc result :privacy-evaluation/decision :deny)))))
  (testing "classification attestation cannot move to a different artifact"
    (let [p (fixture-policy (rule "allow-aggregate" :allow))
          changed (policy/build-request
                   (dissoc (assoc request :request/artifact-root consent-root)
                           :request/schema :request/root))
          result (policy/evaluate p changed (evaluation-options request))]
      (is (= :indeterminate (:privacy-evaluation/decision result)))
      (is (= :unverified (:privacy-evaluation/classification-status result)))))
  (testing "classification is unverified when report bytes or signature change"
    (let [p (fixture-policy (rule "allow-aggregate" :allow))
          opts (evaluation-options request)
          tampered-report (assoc sensitivity-report :run-level "private")
          tampered-attestation (assoc (:classification-attestation opts)
                                      :classification/level :sensitivity/private)
          wrong-schema (signed-decision/sign-envelope
                        {:classification/schema "wrong-schema"
                         :classification/artifact-root artifact-root
                         :classification/report-root sensitivity-report-root
                         :classification/level :sensitivity/public}
                        policy/classification-domain (:private-key classifier-key)
                        (:key/id classifier-key))
          result-report (policy/evaluate p request
                                         (assoc opts :classification-report tampered-report))
          result-signature (policy/evaluate p request
                                            (assoc opts :classification-attestation
                                                   tampered-attestation))
          result-schema (policy/evaluate p request
                                         (assoc opts :classification-attestation wrong-schema))]
      (is (= :indeterminate (:privacy-evaluation/decision result-report)))
      (is (= :unverified (:privacy-evaluation/classification-status result-report)))
      (is (= :unverified (:privacy-evaluation/sentinel-status result-report)))
      (is (= :indeterminate (:privacy-evaluation/decision result-signature)))
      (is (= :unverified (:privacy-evaluation/classification-status result-signature)))
      (is (= :indeterminate (:privacy-evaluation/decision result-schema)))
      (is (= :unverified (:privacy-evaluation/classification-status result-schema))))))

(deftest all-applicable-policies-must-permit
  (let [allow-a (fixture-policy (rule "allow-a" :allow))
        allow-b (policy/build-policy
                 (-> (fixture-policy (rule "allow-b" :allow))
                     (assoc :privacy-policy/id "policy-b")
                     (dissoc :privacy-policy/root)))
        deny (policy/build-policy
              (-> (fixture-policy (rule "deny" :deny))
                  (assoc :privacy-policy/id "policy-c")
                  (dissoc :privacy-policy/root)))
        opts (evaluation-options request)]
    (is (= :allow (:privacy-evaluation/decision
                   (policy/evaluate [allow-a allow-b] request opts))))
    (is (= :deny (:privacy-evaluation/decision
                  (policy/evaluate [allow-a deny] request opts))))
    (is (= :indeterminate (:privacy-evaluation/decision
                           (policy/evaluate [allow-a allow-a] request opts))))
    (is (= 2 (count (:privacy-evaluation/policy-roots
                     (policy/evaluate [allow-a allow-b] request opts)))))))

(deftest signed-evidence-roots-are-required-for-evidence-gated-rules
  (let [evidence {:evidence/type "consent" :evidence/root consent-root}
        req (policy/build-request
             (dissoc (assoc request :request/evidence [evidence])
                     :request/schema :request/root))
        p (fixture-policy (assoc (rule "allow-with-consent" :allow)
                                 :rule/required-evidence ["consent"]))
        opts (evaluation-options req)]
    (is (= :indeterminate (:privacy-evaluation/decision
                           (policy/evaluate p req opts))))
    (is (= :allow (:privacy-evaluation/decision
                   (policy/evaluate p req
                                   (assoc opts :evidence-attestations
                                          [(evidence-attestation consent-root "consent")])))))
    (is (= :indeterminate (:privacy-evaluation/decision
                           (policy/evaluate p req
                                            (assoc opts :evidence-attestations
                                                   [(evidence-attestation artifact-root "consent")])))))
    (is (= :indeterminate (:privacy-evaluation/decision
                           (policy/evaluate p req
                                            (assoc opts :evidence-attestations
                                                   :malformed)))))))

(deftest sentinel-and-context-constraints-remain-fail-closed
  (let [p (fixture-policy (rule "allow" :allow))
        public-request (policy/build-request
                        (dissoc (assoc request :request/sink :public-bundle)
                                :request/schema :request/root))
        result (policy/evaluate p public-request
                                (evaluation-options public-request))]
    (is (= :indeterminate (:privacy-evaluation/decision result)))
    (is (false? (:privacy-evaluation/usable? result))))
  (let [p (fixture-policy (rule "allow" :allow))
        changed-recipient (policy/build-request
                           (dissoc (assoc request :request/recipient-class "internal")
                                   :request/schema :request/root))]
    (is (= :deny (:privacy-evaluation/decision
                  (policy/evaluate p changed-recipient
                                   (evaluation-options changed-recipient)))))))
