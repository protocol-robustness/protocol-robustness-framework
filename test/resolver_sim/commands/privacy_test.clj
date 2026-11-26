(ns resolver-sim.commands.privacy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.privacy :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.privacy.resolution-test :as resolution-fixture]
            [resolver-sim.signed-external-decision :as signed]
            [resolver-sim.support.ed25519 :as fx]))

(defn sha [ch] (str "sha256:" (apply str (repeat 64 ch))))
(def classifier (fx/keypair :privacy-command-classifier))
(def classifier-trust (fx/trust-policy classifier :sensitivity-classifier :active))
(def report-body
  {:schema-version "sensitivity-report.v2" :run-id "command-fixture"
   :profile "internal" :decision "allowed" :run-level "public"
   :findings [] :scenario-count 1 :sensitive-scenario-count 0 :scenarios []})
(def report-hash
  (hc/hash-with-intent {:hash/intent :evidence-record} report-body))
(def report
  (assoc report-body :report-hash report-hash :report/semantic-hash report-hash
         :evaluated-at "fixture-time"))
(def test-policy
  (policy/build-policy
   {:privacy-policy/id "command-fixture" :privacy-policy/version 1
    :privacy-policy/issuer "did:fixture:issuer" :privacy-policy/asset-root (sha "a")
    :privacy-policy/jurisdictions ["ZZ"] :privacy-policy/valid-from 100
    :privacy-policy/valid-until 200
    :privacy-policy/rules
    [{:rule/id "allow-aggregate" :rule/effect :allow
      :rule/data-categories ["aggregate"] :rule/operations ["publish"]
      :rule/purposes ["reporting"] :rule/recipient-ids ["did:fixture:recipient"]
      :rule/recipient-classes ["public"] :rule/sinks [:encrypted-bundle]
      :rule/max-sensitivity :sensitivity/public :rule/max-retention-seconds 3600
      :rule/required-evidence []}]
    :privacy-policy/authority-ref "fixture-only"}))
(def test-request
  (policy/build-request
   {:request/id "command-fixture" :request/artifact-root (sha "c")
    :request/classification-report-root (str "sha256:" report-hash)
    :request/issuer "did:fixture:issuer" :request/asset-root (sha "a")
    :request/jurisdiction "ZZ" :request/at 150 :request/data-category "aggregate"
    :request/operation "publish" :request/purpose "reporting"
    :request/recipient-id "did:fixture:recipient" :request/recipient-class "public"
    :request/sink :encrypted-bundle :request/retention-seconds 30
    :request/evidence []}))
(def classification
  (signed/sign-envelope
   {:classification/schema "stablecoin-classification-attestation.v1"
    :classification/artifact-root (:request/artifact-root test-request)
    :classification/report-root (:request/classification-report-root test-request)
    :classification/level :sensitivity/public}
   policy/classification-domain (:private-key classifier) (:key/id classifier)))
(def eval-input
  {:policies [test-policy]
   :request test-request
   :classification-report report
   :classification-attestation classification
   :classification-trust-policy classifier-trust
   :evidence-attestations []
   :evidence-trust-policy {:trusted-keys []}})

(defn invoke [handler value]
  (let [file (java.io.File/createTempFile "privacy-command-" ".edn")]
    (try
      (spit file (pr-str value))
      (let [result (atom nil)
            output (with-out-str
                     (reset! result (handler {:input (.getAbsolutePath file)})))]
        {:result @result :output output})
      (finally (.delete file)))))

(deftest inspect-evaluate-and-reconstruct-commands
  (testing "inspect preserves authority/currentness limits"
    (let [{:keys [output]} (invoke command/policy-inspect {:policy test-policy})]
      (is (.contains output ":valid? true"))
      (is (.contains output ":authority-status :unverified"))
      (is (.contains output ":currentness :unknown"))))
  (testing "evaluate returns a rooted offline allow"
    (let [{:keys [result output]} (invoke command/evaluate eval-input)
          evaluation (read-string output)]
      (is (= 0 (:exit-code result)))
      (is (= :allow (:privacy-evaluation/decision evaluation)))
      (is (false? (:privacy-evaluation/usable? evaluation)))))
  (testing "verify reconstructs and detects a claimed receipt change"
    (let [evaluation (policy/evaluate (:policies eval-input) (:request eval-input)
                                      (select-keys eval-input
                                                   [:classification-report
                                                    :classification-attestation
                                                    :classification-trust-policy
                                                    :evidence-attestations
                                                    :evidence-trust-policy]))
          valid-input (assoc eval-input :evaluation evaluation)
          tampered-input (assoc valid-input :evaluation
                                (assoc evaluation :privacy-evaluation/decision :deny))]
      (let [valid (invoke command/verify valid-input)
            tampered (invoke command/verify tampered-input)]
        (is (= 0 (:exit-code (:result valid))))
        (is (= 1 (:exit-code (:result tampered))))))))

(deftest stage-output-is-a-status-only-view-and-suppresses-diagnostics
  (let [success (invoke command/stage-output eval-input)
        success-output (:output success)
        projected (read-string success-output)
        secret-input (assoc eval-input :private-fixture-value "do-not-print")
        failed (invoke command/stage-output secret-input)
        failed-output (:output failed)
        unavailable-stage-output (read-string failed-output)
        invalid-request (assoc eval-input
                               :request (assoc test-request :request/at "150"))
        invalid-stage-output (read-string
                              (:output (invoke command/stage-output invalid-request)))]
    (is (= 0 (:exit-code (:result success))))
    (is (= :allow (:privacy-stage-output/decision projected)))
    (is (= "stablecoin-privacy-stage-output.v2"
           (:privacy-stage-output/schema projected)))
    (is (= [:request :classification :evidence :privacy-policy :sentinel
            :authority :currentness :execution]
           (mapv :stage (:privacy-stage-output/stages projected))))
    (is (= [:valid :verified :verified :permitted :local-gate-passed
            :unverified :unknown :not-executed]
           (mapv :status (:privacy-stage-output/stages projected))))
    (is (= :available (:privacy-stage-output/status projected)))
    (is (false? (:privacy-stage-output/receipt-included? projected)))
    (is (not (contains? projected :privacy-stage-output/receipt-root)))
    (is (not (.contains success-output "did:fixture:recipient")))
    (is (= 2 (:exit-code (:result failed))))
    (is (= :unavailable
           (:privacy-stage-output/status unavailable-stage-output)))
    (is (= (vec (repeat 8 :not-run))
           (mapv :status (:privacy-stage-output/stages unavailable-stage-output))))
    (is (= [:invalid :not-run :not-run :not-run :not-run :not-run :not-run :not-run]
           (mapv :status (:privacy-stage-output/stages invalid-stage-output))))
    (is (not (.contains failed-output "do-not-print")))))

(deftest policy-resolve-reports-a-retention-bound-with-caller-relative-currentness
  (let [{:keys [output result]}
        (invoke command/policy-resolve (resolution-fixture/resolution-input))
        projected (read-string output)]
    (is (= 0 (:exit-code result)))
    (is (= :available (:privacy-policy-query/status projected)))
    (is (true? (:privacy-policy-query/resolution-valid? projected)))
    (is (= :current-observed (:privacy-policy-query/currentness projected)))
    (is (= 3600 (:privacy-policy-query/retention-limit-seconds projected)))
    (is (= :caller-supplied-trust-policies
           (:privacy-policy-query/trust-provenance projected)))
    (is (true? (:privacy-policy-query/operation-not-authorized? projected)))))
