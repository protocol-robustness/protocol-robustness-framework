(ns resolver-sim.privacy.resolution-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.privacy.resolution :as resolution]
            [resolver-sim.privacy.simulated-boundary :as simulated]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.support.ed25519 :as fx]))

(defn sha [ch] (str "sha256:" (apply str (repeat 64 ch))))
(def authority (fx/keypair :fixture-privacy-authority))
(def observer (fx/keypair :fixture-privacy-observer))
(def classifier (fx/keypair :fixture-privacy-classifier))
(def authority-trust (fx/trust-policy authority :privacy-policy-authority :active))
(def observer-trust (fx/trust-policy observer :privacy-head-observer :active))
(def classifier-trust (fx/trust-policy classifier :sensitivity-classifier :active))
(def report-body
  {:schema-version "sensitivity-report.v2" :run-id "fixture-run"
   :profile "internal" :decision "allowed" :run-level "public"
   :findings [] :scenario-count 1 :sensitive-scenario-count 0 :scenarios []})
(def report-hash
  (hc/hash-with-intent {:hash/intent :evidence-record} report-body))
(def sensitivity-report
  (assoc report-body :report-hash report-hash :report/semantic-hash report-hash
         :evaluated-at "fixture-time"))
(def policy-body
  (policy/build-policy
   {:privacy-policy/id "fixture-policy" :privacy-policy/version 1
    :privacy-policy/issuer "did:fixture:issuer" :privacy-policy/asset-root (sha "a")
    :privacy-policy/jurisdictions ["ZZ"] :privacy-policy/valid-from 100
    :privacy-policy/valid-until 200
    :privacy-policy/rules
    [{:rule/id "fixture-allow" :rule/effect :allow
      :rule/data-categories ["aggregate"] :rule/operations ["publish"]
      :rule/purposes ["reporting"] :rule/recipient-ids ["did:fixture:recipient"]
      :rule/recipient-classes ["public"] :rule/sinks [:encrypted-bundle]
      :rule/max-sensitivity :sensitivity/public :rule/max-retention-seconds 3600
      :rule/required-evidence []}]
    :privacy-policy/authority-ref "fixture-authority-reference"}))
(def request
  (policy/build-request
   {:request/id "fixture-request" :request/artifact-root (sha "c")
    :request/classification-report-root (str "sha256:" report-hash)
    :request/issuer "did:fixture:issuer" :request/asset-root (sha "a")
    :request/jurisdiction "ZZ" :request/at 150 :request/data-category "aggregate"
    :request/operation "publish" :request/purpose "reporting"
    :request/recipient-id "did:fixture:recipient" :request/recipient-class "public"
    :request/sink :encrypted-bundle :request/retention-seconds 30
    :request/evidence []}))

(def fixture-binding
  (let [body {:privacy-binding/schema resolution/binding-schema
              :privacy-binding/issuer "did:fixture:issuer"
              :privacy-binding/asset-root (sha "a")
              :privacy-binding/jurisdiction "ZZ"
              :privacy-binding/operation "publish"
              :privacy-binding/policy-root (:privacy-policy/root policy-body)
              :privacy-binding/authority-ref "fixture-authority-reference"}
        rooted (assoc body :privacy-binding/root (resolution/binding-root body))]
    (signed/sign-envelope rooted resolution/binding-domain
                          (:private-key authority) (:key/id authority))))

(def head-observation
  (let [body {:privacy-head-observation/schema resolution/observation-schema
              :privacy-head-observation/binding-root (:privacy-binding/root fixture-binding)
              :privacy-head-observation/policy-root (:privacy-policy/root policy-body)
              :privacy-head-observation/head-root (sha "e")
              :privacy-head-observation/sequence 5
              :privacy-head-observation/observed-at 145}
        rooted (assoc body :privacy-head-observation/root
                      (resolution/observation-root body))]
    (signed/sign-envelope rooted resolution/observation-domain
                          (:private-key observer) (:key/id observer))))

(defn resolution-input
  ([] (resolution-input {}))
  ([overrides]
   (merge {:binding fixture-binding :policy policy-body :request request
           :head-observation head-observation
           :authority-trust-policy authority-trust
           :observer-trust-policy observer-trust
           :now 150 :max-observation-age-seconds 10}
          overrides)))

(defn resolve-fixture
  ([] (resolve-fixture {}))
  ([overrides] (resolution/resolve-policy (resolution-input overrides))))

(defn evaluation-options []
  {:classification-report sensitivity-report
   :classification-attestation
   (signed/sign-envelope
    {:classification/schema "stablecoin-classification-attestation.v1"
     :classification/artifact-root (:request/artifact-root request)
     :classification/report-root (:request/classification-report-root request)
     :classification/level :sensitivity/public}
    policy/classification-domain (:private-key classifier) (:key/id classifier))
   :classification-trust-policy classifier-trust
   :evidence-attestations []
   :evidence-trust-policy {:trusted-keys []}})

(deftest resolves-only-under-matching-signed-scope-and-fresh-observation
  (let [result (resolve-fixture)]
    (is (:privacy-resolution/valid? result))
    (is (= :verified-relative-to-supplied-trust
           (:privacy-resolution/authority-status result)))
    (is (= :verified-relative-to-supplied-trust
           (:privacy-resolution/observer-status result)))
    (is (= :current-observed (:privacy-resolution/currentness result)))
    (is (= :caller-supplied-trust-policies
           (:privacy-resolution/trust-provenance result)))
    (is (resolution/verify-resolution result (resolution-input))))
  (testing "a stale, future, or mismatched observation is not current"
    (is (= :unknown (:privacy-resolution/currentness
                     (resolve-fixture {:now 180 :max-observation-age-seconds 10}))))
    (is (= :unknown (:privacy-resolution/currentness
                     (resolve-fixture {:now 140 :max-observation-age-seconds 10}))))
    (is (= :unknown (:privacy-resolution/currentness
                     (resolve-fixture
                      {:head-observation
                       (assoc head-observation
                              :privacy-head-observation/policy-root (sha "f"))}))))))

(deftest binding-scope-policy-validity-and-trust-are-required
  (is (= :unknown (:privacy-resolution/currentness
                   (resolve-fixture {:authority-trust-policy observer-trust}))))
  (is (= :unknown (:privacy-resolution/currentness
                   (resolve-fixture {:observer-trust-policy authority-trust}))))
  (is (= :unknown (:privacy-resolution/currentness
                   (resolve-fixture
                    {:request (policy/build-request
                               (dissoc (assoc request :request/operation "redeem")
                                       :request/schema :request/root))}))))
  (is (= :unknown (:privacy-resolution/currentness
                   (resolve-fixture {:now 200}))))
  (is (false? (resolution/verify-resolution
               (assoc (resolve-fixture) :privacy-resolution/head-root (sha "0"))
               (resolution-input)))))

(deftest simulated-effect-is-head-fenced-and-never-real-authorization
  (let [boundary (simulated/new-boundary
                  (:privacy-head-observation/head-root head-observation)
                  (:privacy-head-observation/sequence head-observation))
        command {:boundary boundary :binding fixture-binding :policy policy-body
                 :request request :head-observation head-observation
                 :authority-trust-policy authority-trust
                 :observer-trust-policy observer-trust
                 :evaluation-options (evaluation-options)
                 :now 150 :max-observation-age-seconds 10
                 :effect {:operation :publish-aggregate :output-root (sha "9")}}
        applied (simulated/apply-simulated! command)]
    (is (= :simulated (:status applied)))
    (is (:simulation-only? applied))
    (is (false? (:real-operation-authorized? applied)))
    (is (= 1 (count (:effects (simulated/state boundary)))))
    (let [replayed (simulated/apply-simulated! command)]
      (is (= :blocked (:status replayed)))
      (is (= :request-already-applied (:reason replayed))))
    (simulated/advance-head! boundary (sha "f"))
    (let [stale (simulated/apply-simulated! command)]
      (is (= :blocked (:status stale)))
      (is (= :head-changed (:reason stale)))
      (is (= 1 (count (:effects (simulated/state boundary)))))))
  (testing "a context change fails closed and produces no simulated effect"
    (let [boundary (simulated/new-boundary
                    (:privacy-head-observation/head-root head-observation)
                    (:privacy-head-observation/sequence head-observation))
          changed-request (policy/build-request
                           (dissoc (assoc request :request/recipient-class "private")
                                   :request/schema :request/root))
          command {:boundary boundary :binding fixture-binding :policy policy-body
                   :request changed-request :head-observation head-observation
                   :authority-trust-policy authority-trust
                   :observer-trust-policy observer-trust
                   :evaluation-options (evaluation-options)
                   :now 150 :max-observation-age-seconds 10
                   :effect {:operation :publish-aggregate}}
          result (simulated/apply-simulated! command)]
      (is (= :blocked (:status result)))
      (is (= :privacy-policy-not-allow (:reason result)))
      (is (empty? (:effects (simulated/state boundary))))))
  (testing "an unsigned classification cannot produce an effect"
    (let [boundary (simulated/new-boundary
                    (:privacy-head-observation/head-root head-observation)
                    (:privacy-head-observation/sequence head-observation))
          command {:boundary boundary :binding fixture-binding :policy policy-body
                   :request request :head-observation head-observation
                   :authority-trust-policy authority-trust
                   :observer-trust-policy observer-trust
                   :evaluation-options (dissoc (evaluation-options)
                                               :classification-attestation)
                   :now 150 :max-observation-age-seconds 10
                   :effect {:operation :publish-aggregate}}
          result (simulated/apply-simulated! command)]
      (is (= :blocked (:status result)))
      (is (= :privacy-policy-not-allow (:reason result)))
      (is (empty? (:effects (simulated/state boundary)))))))
