(ns resolver-sim.commands.canonical-artifacts-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [prf.pro-rata.realized-statement :as owner-statement]
            [resolver-sim.commands.canonical-artifacts :as sut]
            [resolver-sim.hash.canonical :as hc]))

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(def ^:private request-example
  (read-edn "examples/canonical-artifacts/pro-rata-request.edn"))
(def ^:private bundle-example
  (read-edn "examples/canonical-artifacts/bundle.example.edn"))
(def ^:private statement-example
  (read-edn "examples/canonical-artifacts/statement-components.example.edn"))
(def ^:private vector-data
  (read-edn "resources/test-vectors/canonical-artifacts/artifact-bundle-v1.edn"))

(defn- error-reason [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))

(deftest derive-pro-rata-request-uses-frozen-owner-identity
  (let [derived (sut/derive-request request-example)]
    (is (= "pro-rata-allocation-request.v1" (:artifact/id derived)))
    (is (= "PROJECTION_ARTIFACT_V1" (:hash/domain derived)))
    (is (= "6c60df09a5ec1efffaae94da7c0ab8bdf177df9311db525650765fee33e26d85"
           (:commitment/root derived)))
    (is (= (:canonical/bytes derived)
           (hc/canonical-bytes-hex (:canonical/preimage derived))))
    (is (= :recomputed (:verification/status derived)))
    (is (= :not-performed (:authority/admission derived)))))

(deftest derive-rejects-roots-and-unknown-input-fields
  (is (= :unsupported-input-fields
         (error-reason #(sut/derive-request
                         (assoc request-example :request/hash "frozen-looking-root")))))
  (is (= :unsupported-input-fields
         (error-reason #(sut/derive-request (assoc request-example :surprise true)))))
  (is (= :unsupported-input-fields
         (error-reason #(sut/derive-request
                         (update request-example :rows
                                 conj {:row/id :row/c :obligation/id :ob/c
                                       :requested 1 :weight 1 :allocation/hash "no"}))))))

(deftest bundle-is-order-independent-and-distinguishes-member-validation
  (let [forward (sut/bundle-artifacts bundle-example)
        reverse (sut/bundle-artifacts (update bundle-example :members #(vec (reverse %))))
        substituted-id (sut/bundle-artifacts
                        (assoc-in bundle-example [:members 1 :artifact/id]
                                  "other-reserve-observation.v1"))
        substituted-domain (sut/bundle-artifacts
                            (assoc-in bundle-example [:members 1 :hash/domain]
                                      "OTHER_OBSERVATION_DOMAIN_V1"))
        expected (:bundle vector-data)]
    (is (= (:expected/root expected) (:bundle/root forward)))
    (is (= (:bundle/root forward) (:bundle/root reverse)))
    (is (not= (:bundle/root forward) (:bundle/root substituted-id)))
    (is (not= (:bundle/root forward) (:bundle/root substituted-domain)))
    (is (= (:canonical/bytes forward)
           (hc/canonical-bytes-hex (:bundle/preimage forward))))
    (is (= [:recomputed :opaque-supplied]
           (mapv :commitment/status (:members/verification forward))))
    (is (= [:passed :not-performed]
           (mapv :source-validation (:members/verification forward))))
    (is (= :structural-only (:semantic/interpretation forward)))
    (is (= :not-performed (:authority/admission forward)))))

(deftest bundle-rejects-ambiguous-or-unverifiable-members
  (doseq [[label expected f]
          [["duplicate role and identity" :duplicate-bundle-member
            #(sut/bundle-artifacts
              (update bundle-example :members conj (first (:members bundle-example))))]
           ["supplied root on recompute" :supplied-root-on-recompute
            #(sut/bundle-artifacts
              (update-in bundle-example [:members 0]
                         assoc :commitment/root (apply str (repeat 64 "a"))))]
           ["unsupported derivation" :unsupported-derivation
            #(sut/bundle-artifacts
              (update-in bundle-example [:members 0]
                         assoc :artifact/id "unknown.v1" :artifact/schema "unknown.v1"))]
           ["substituted domain" :artifact-domain-mismatch
            #(sut/bundle-artifacts
              (update-in bundle-example [:members 0] assoc :hash/domain "OTHER_DOMAIN"))]
           ["malformed opaque root" :invalid-commitment-root
            #(sut/bundle-artifacts
              (assoc-in bundle-example [:members 1 :commitment/root] "no"))]
           ["unsupported hash scheme" :unsupported-member-scheme
            #(sut/bundle-artifacts
              (assoc-in bundle-example [:members 1 :hash/scheme] "sha3"))]]]
    (testing label
      (is (= expected (error-reason f))))))

(deftest compose-realized-statement-reuses-semantic-owner
  (let [composed (sut/compose-realized-statement statement-example)
        expected (:realized-allocation-statement vector-data)]
    (is (= (:expected/root expected) (:commitment/root composed)))
    (is (= (:commitment/root composed)
           (owner-statement/statement-root statement-example)))
    (is (= (:canonical/bytes composed)
           (hc/canonical-bytes-hex (:canonical/preimage composed))))
    (is (= :format-checked-only (:parent-roots/status composed)))
    (is (= [:parent-artifact-data-not-validated
            :source-authority-not-validated
            :proof-verification-not-performed]
           (:verification/limitations composed)))
    (is (= :not-performed (:authority/admission composed)))))

(deftest compose-rejects-incomplete-and-malformed-parent-roots
  (doseq [[expected input]
          [[:invalid-input-fields (dissoc statement-example :request-set-root)]
           [:invalid-commitment-root (assoc statement-example :request-set-root "sha256:bad")]
           [:invalid-input-fields
            (assoc statement-example :extra-root (apply str (repeat 64 "f")))]]]
    (is (= expected (error-reason #(sut/compose-realized-statement input))))))

(deftest verify-distinguishes-recomputation-mismatch-and-invalid-input
  (let [expected (:commitment/root (sut/derive-request request-example))
        matched (sut/verify-artifact
                 "pro-rata-allocation-request.v1"
                 (assoc request-example :verification/expected-root expected))
        mismatch (sut/verify-artifact
                  "pro-rata-allocation-request.v1"
                  (assoc request-example :verification/expected-root (apply str (repeat 64 "a"))))
        invalid (sut/verify-artifact
                 "pro-rata-allocation-request.v1"
                 (assoc request-example :verification/expected-root expected :unexpected true))]
    (is (= :commitment-matched (:verification/status matched)))
    (is (= :matched (:verification/commitment-check matched)))
    (is (= :validated-by-owner-adapter (:verification/source-data matched)))
    (is (= :not-established (:verification/statement-truth matched)))
    (is (= :not-performed (:authority/admission matched)))
    (is (= :commitment-mismatch (:verification/status mismatch)))
    (is (= :mismatched (:verification/commitment-check mismatch)))
    (is (= :statement-invalid (:verification/status invalid)))
    (is (= :invalid-input-fields
           (get-in invalid [:verification/error :reason])))))

(deftest verify-composition-and-bundle-report-their-limits
  (let [statement-root (:commitment/root (sut/compose-realized-statement statement-example))
        statement (sut/verify-artifact
                   "realized-allocation-statement.v1"
                   (assoc statement-example :verification/expected-root statement-root))
        bundle-root (:bundle/root (sut/bundle-artifacts bundle-example))
        bundle (sut/verify-artifact
                "artifact-bundle.v1"
                (assoc bundle-example :verification/expected-root bundle-root))]
    (is (= :commitment-matched (:verification/status statement)))
    (is (= :parent-roots-format-only (:verification/source-data statement)))
    (is (= :not-established (:verification/statement-truth statement)))
    (is (= :commitment-matched (:verification/status bundle)))
    (is (= :bundle-structure-recomputed (:verification/source-data bundle)))
    (is (= :not-established (:verification/statement-truth bundle)))))

(deftest verify-opaque-commitment-is-explicitly-unrecomputed
  (let [result (sut/verify-artifact
                "opaque-commitment.v1"
                {:artifact/id "external-reserve-observation.v1"
                 :artifact/schema "external-reserve-observation.v1"
                 :hash/scheme "canonical-hash-scheme.v1"
                 :hash/algorithm "sha256"
                 :hash/domain "EXTERNAL_RESERVE_OBSERVATION_V1"
                 :commitment/root (apply str (repeat 64 "a"))})]
    (is (= :opaque-unrecomputed (:verification/status result)))
    (is (= :not-performed (:verification/source-reconstruction result)))
    (is (= :not-established (:verification/statement-truth result)))
    (is (= :not-performed (:authority/admission result)))))
