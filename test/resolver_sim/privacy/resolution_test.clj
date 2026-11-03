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
(def authority-trust (assoc (fx/trust-policy authority :privacy-policy-authority :active)
                            :authority/ref "fixture-authority-reference"
                            :trust/id "fixture-authority-trust-v1"))
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

(defn signed-observation
  ([head-root sequence observed-at previous-root]
   (let [body {:privacy-head-observation/schema resolution/observation-schema
               :privacy-head-observation/binding-root (:privacy-binding/root fixture-binding)
               :privacy-head-observation/policy-root (:privacy-policy/root policy-body)
               :privacy-head-observation/head-root head-root
               :privacy-head-observation/previous-root previous-root
               :privacy-head-observation/sequence sequence
               :privacy-head-observation/observed-at observed-at}
         rooted (assoc body :privacy-head-observation/root
                       (resolution/observation-root body))]
     (signed/sign-envelope rooted resolution/observation-domain
                           (:private-key observer) (:key/id observer)))))

(def head-observation (signed-observation (sha "e") 5 145 nil))

(defn signed-binding [authority-ref]
  (let [body {:privacy-binding/schema resolution/binding-schema
              :privacy-binding/issuer "did:fixture:issuer"
              :privacy-binding/asset-root (sha "a")
              :privacy-binding/jurisdiction "ZZ"
              :privacy-binding/operation "publish"
              :privacy-binding/policy-root (:privacy-policy/root policy-body)
              :privacy-binding/authority-ref authority-ref}
        rooted (assoc body :privacy-binding/root (resolution/binding-root body))]
    (signed/sign-envelope rooted resolution/binding-domain
                          (:private-key authority) (:key/id authority))))

(defn resolution-input
  ([] (resolution-input {}))
  ([overrides]
   (merge {:binding fixture-binding :policy policy-body :request request
           :head-observation head-observation
           :head-checkpoint {:sequence 5 :root (sha "e") :conflicts []}
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
    (is (= :idempotent (:privacy-resolution/head-status result)))
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

(deftest exact-authority-reference-and-explicit-caller-trust-are-required
  (let [substituted (resolve-fixture {:binding (signed-binding "substituted-authority")})
        missing-authority (resolve-fixture
                           {:authority-trust-policy
                            (dissoc authority-trust :authority/ref)})
        valid (resolve-fixture)]
    (is (false? (:privacy-resolution/valid? substituted)))
    (is (some #{:authority-reference-mismatch}
              (:privacy-resolution/errors substituted)))
    (is (false? (:privacy-resolution/valid? missing-authority)))
    (is (some #{:signing-authority-mismatch}
              (:privacy-resolution/errors missing-authority)))
    (is (= :caller-supplied-trust-policies
           (:privacy-resolution/trust-provenance valid)))
    (is (not (contains? valid :privacy-resolution/authority-authenticated?))
        "a caller-provided root is not an external trust assertion")
    (is (= (resolution/trust-policy-root authority-trust)
           (:privacy-resolution/authority-trust-policy-root valid)))
    (is (= :verified-relative-to-caller-supplied-policy
           (get-in valid [:privacy-resolution/observation-provenance :trust-status])))))

(deftest head-history-rejects-equivocation-regression-gaps-and-bad-continuity
  (let [equivocation (resolve-fixture
                      {:head-observation (signed-observation (sha "d") 5 145 nil)})
        regression (resolve-fixture
                    {:head-observation (signed-observation (sha "d") 4 145 nil)})
        gap (resolve-fixture
             {:head-observation (signed-observation (sha "d") 7 145 (sha "e"))})
        bad-link (resolve-fixture
                  {:head-checkpoint {:sequence 4 :root (sha "f") :conflicts []}
                   :head-observation (signed-observation (sha "d") 5 145 (sha "e"))})
        advance (resolve-fixture
                 {:head-checkpoint {:sequence 4 :root (sha "f") :conflicts []}
                  :head-observation (signed-observation (sha "d") 5 145 (sha "f"))})
        conflict-retained (resolve-fixture
                           {:head-checkpoint {:sequence 5 :root (sha "e")
                                              :conflicts [{:sequence 5}]}})]
    (is (= :equivocation (:privacy-resolution/head-status equivocation)))
    (is (= :stale-sequence (:privacy-resolution/head-status regression)))
    (is (= :sequence-gap (:privacy-resolution/head-status gap)))
    (is (= :continuity-mismatch (:privacy-resolution/head-status bad-link)))
    (is (= :advanced (:privacy-resolution/head-status advance)))
    (is (:privacy-resolution/valid? advance))
    (is (= :retained-equivocation
           (:privacy-resolution/head-status conflict-retained)))
    (is (not (:privacy-resolution/valid? equivocation)))))

(deftest malformed-freshness-values-return-structured-denials
  (doseq [bad-now [nil "150" -1 9223372036854775808N 1.5]
          :let [result (resolve-fixture {:now bad-now})]]
    (is (false? (:privacy-resolution/valid? result)))
    (is (some #{:invalid-freshness-input} (:privacy-resolution/errors result))))
  (doseq [bad-age [nil "10" -1 9223372036854775808N 2.5]
          :let [result (resolve-fixture {:max-observation-age-seconds bad-age})]]
    (is (false? (:privacy-resolution/valid? result)))
    (is (some #{:invalid-freshness-input} (:privacy-resolution/errors result))))
  (let [invalid-observation (resolve-fixture {:head-observation nil})]
    (is (= :invalid-observation
           (:privacy-resolution/head-status invalid-observation)))
    (is (false? (:privacy-resolution/valid? invalid-observation)))))

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

(deftest simulation-retains-equivocation-and-documents-boundary-local-replay
  (let [boundary (simulated/new-boundary (sha "e") 5)
        command {:boundary boundary :binding fixture-binding :policy policy-body
                 :request request :head-observation (signed-observation (sha "d") 5 145 nil)
                 :authority-trust-policy authority-trust :observer-trust-policy observer-trust
                 :evaluation-options (evaluation-options) :now 150
                 :max-observation-age-seconds 10 :effect {:operation :publish-aggregate}}
        blocked (simulated/apply-simulated! command)]
    (is (= :blocked (:status blocked)))
    (is (= :equivocation
           (get-in blocked [:resolution :privacy-resolution/head-status])))
    (is (= 1 (count (:head/conflicts (simulated/state boundary)))))
    (is (= :retained-equivocation
           (:privacy-resolution/head-status
            (resolve-fixture
             {:head-checkpoint {:sequence 5 :root (sha "e")
                                :conflicts (:head/conflicts (simulated/state boundary))}})))))
  (let [command {:binding fixture-binding :policy policy-body :request request
                 :head-observation head-observation
                 :authority-trust-policy authority-trust :observer-trust-policy observer-trust
                 :evaluation-options (evaluation-options) :now 150
                 :max-observation-age-seconds 10 :effect {:operation :publish-aggregate}}
        first-boundary (simulated/new-boundary (sha "e") 5)
        second-boundary (simulated/new-boundary (sha "e") 5)
        first-result (simulated/apply-simulated! (assoc command :boundary first-boundary))
        restarted-result (simulated/apply-simulated! (assoc command :boundary second-boundary))]
    (is (= :simulated (:status first-result)))
    (is (= :simulated (:status restarted-result)))
    (is (= 1 (count (:effects (simulated/state first-boundary)))))
    (is (= 1 (count (:effects (simulated/state second-boundary)))))
    (is (= :blocked
           (:status (simulated/apply-simulated!
                     (assoc command :boundary first-boundary :now "150")))))
    (is (= :invalid-simulation-input
           (:reason (simulated/apply-simulated!
                     (assoc command :boundary second-boundary :now "150")))))))
