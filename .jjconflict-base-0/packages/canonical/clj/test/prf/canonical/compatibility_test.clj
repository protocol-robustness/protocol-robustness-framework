(ns prf.canonical.compatibility-test
  (:require [clojure.test :refer [deftest is]]
            [prf.canonical.hash :as hash]
            [prf.canonical.sequence :as sequence]))

(deftest keyword-domain-compatibility-is-module-supplied
  (is (= "RESEARCH_ANALYSIS_RESULT_V1"
         (hash/resolve-domain-tag :research-analysis-result
                                  {:research-analysis-result "RESEARCH_ANALYSIS_RESULT_V1"})))
  (is (= "RESEARCH_ANALYSIS_RESULT_V1"
         (hash/resolve-domain-tag "RESEARCH_ANALYSIS_RESULT_V1" {})))
  (is (= (hash/hash-with-domain "RESEARCH_ANALYSIS_RESULT_V1" {:result/value 42})
         (hash/domain-hash :research-analysis-result {:result/value 42}
                           {:research-analysis-result "RESEARCH_ANALYSIS_RESULT_V1"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (hash/resolve-domain-tag :unknown {}))))

(deftest generic-projections-preserve-v1-behavior
  (is (= {:items [1 2]}
         (hash/project-canonical-safe {:items #{2 1}})))
  (is (= {:ratio {:canonical/type "ratio"
                  :canonical/numerator 1
                  :canonical/denominator 3}
          :decimal {:canonical/type "big-decimal"
                    :canonical/value "1.20"}}
         (hash/project-committable-content {:ratio 1/3
                                            :decimal (bigdec "1.20")})))
  (is (= (hash/hash-with-domain "EVIDENCE_RECORD_V1" {:evidence/value 7})
         (hash/hash-with-intent {:hash/intent :evidence-record
                                 :intent/domain-tag "EVIDENCE_RECORD_V1"
                                 :intent/projection-fn hash/project-canonical-safe}
                                {:evidence/value 7}))))

(deftest intent-aware-equality-preserves-cross-intent-guard
  (is (hash/intent-hash= {:hash/intent :evidence-record :hash/hex "abc"}
                         {:hash/intent :evidence-record :hash/hex "abc"}))
  (is (not (hash/intent-hash= {:hash/intent :evidence-record :hash/hex "abc"}
                              {:hash/intent :world-state :hash/hex "abc"})))
  (is (hash/intent-hash= {:hash/intent :evidence-record :hash/hex "abc"}
                         {:hash/intent :world-state :hash/hex "abc"}
                         {:allow-cross-intent? true})))

(deftest sequence-commitments-match-fixed-v1-vectors
  (let [empty-value {:encoding-contract "canonical-value-sequence.v1"
                     :purpose :fixture :component-count 0 :components []}
        one-value {:encoding-contract "canonical-value-sequence.v1"
                   :purpose :fixture :component-count 1 :components [1]}]
    (is (= empty-value (sequence/bound-sequence {:purpose :fixture} [])))
    (is (= one-value (sequence/bound-sequence {:purpose :fixture} [1])))
    (is (= "5192be2ab0a4e3894cbc13cbd1408125e6427cc3e999eced1cb78349544e1286"
           (sequence/sequence-hash {:purpose :fixture} [])))
    (is (= "81e3d71596b050e1bbeabde20b760927b20e456a6ba06d216ad4899773561dfe"
           (sequence/sequence-hash {:purpose :fixture} [1])))
    (is (not= (sequence/sequence-hash {:purpose :fixture} [])
              (sequence/sequence-hash {:purpose :fixture} [1])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sequence/bound-sequence {:purpose :fixture} nil)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sequence/bound-sequence {:purpose :fixture
                                           :expected-component-count 2} [1])))))

(deftest sequence-verifier-classifies-valid-malformed-and-noncanonical-inputs
  (let [valid (sequence/canonical-sequence-bytes {:purpose :verify} [1 :x])
        truncated (byte-array [0x31 0x01 0x10])
        idx (loop [i 0]
              (if (and (= 0x10 (bit-and 0xff (aget ^bytes valid i)))
                       (= 0x02 (bit-and 0xff (aget ^bytes valid (inc i)))))
                i
                (recur (inc i))))
        noncanonical (byte-array (concat (take idx valid) [0x10 0x82 0x00]
                                         (drop (+ idx 2) valid)))
        valid-result (sequence/verify-sequence-commitment valid)
        malformed-result (sequence/verify-sequence-commitment truncated)
        noncanonical-result (sequence/verify-sequence-commitment noncanonical)
        trailing-result (sequence/verify-sequence-commitment
                         (byte-array (concat valid [0x00])))]
    (is (:valid? valid-result))
    (is (= {:encoding-contract sequence/sequence-contract
            :purpose :verify :component-count 2 :components [1N :x]}
           (:value valid-result)))
    (is (not (:valid? malformed-result)))
    (is (some #(= :truncated-length (:code %)) (:issues malformed-result)))
    (is (not-any? #(= :sequence/trailing-bytes (:code %)) (:issues malformed-result)))
    (is (not (:valid? noncanonical-result)))
    (is (some #(= :non-minimal-varint (:code %)) (:issues noncanonical-result)))
    (is (not (:valid? trailing-result)))
    (is (some #(= :sequence/trailing-bytes (:code %)) (:issues trailing-result))))
  (let [limited (sequence/verify-sequence-commitment
                 (sequence/canonical-sequence-bytes {:purpose :verify} [1])
                 {:limits {:max-stream-bytes 1}})]
    (is (not (:valid? limited)))
    (is (:resource-limit? limited))
    (is (= :max-stream-bytes (:resource-reason limited)))))
