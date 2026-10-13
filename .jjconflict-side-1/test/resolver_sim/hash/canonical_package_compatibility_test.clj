(ns resolver-sim.hash.canonical-package-compatibility-test
  "Cross-check the independent canonical package against legacy V1 behavior."
  (:require [clojure.test :refer [deftest is]]
            [prf.canonical.codec :as package-codec]
            [prf.canonical.hash :as package-hash]
            [prf.canonical.reference :as package-reference]
            [prf.canonical.sequence :as package-sequence]
            [resolver-sim.hash.canonical :as legacy]
            [resolver-sim.hash.reference :as legacy-reference]
            [resolver-sim.hash.sequence :as legacy-sequence])
  (:import [java.util Arrays]))

(deftest generic-projections-match-legacy
  (doseq [value [nil {:items #{:b :a}} [1 '(2 3)]
                 {:ratio 1/3 :double 0.1 :decimal (bigdec "2.500")}]]
    (is (= (legacy/project-canonical-safe value)
           (package-hash/project-canonical-safe value)))
    (is (= (legacy/project-committable-content value)
           (package-hash/project-committable-content value)))))

(deftest c1-research-canonical-v1-vectors-match
  (let [values [nil true false 0 -1 42 "research" :claim/example
                {:researcher/id "anon-01"
                 :research-claim/status :verified
                 :roots ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                         "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}]
        domains [[:research-analysis-result {:result/value 42}]
                 [:researcher-reward-policy {:reward/currency "TEST"}]
                 [:research-command {:command/type :claim-evaluation}]]
        hex (apply str (repeat 64 "a"))
        ref (str "sha256:" hex)]
    (doseq [value values]
      (is (Arrays/equals ^bytes (legacy/canonical-bytes value)
                         ^bytes (package-codec/canonical-bytes value))))
    (doseq [[domain body] domains]
      (is (= (legacy/domain-hash domain body)
             (package-hash/hash-with-domain (get legacy/domain-tags domain) body))))
    (is (= (legacy-reference/sha256-ref hex)
           (package-reference/sha256-ref hex)))
    (is (= (legacy-reference/valid-sha256-ref? ref)
           (package-reference/valid-sha256-ref? ref)))
    (is (= (legacy-reference/parse-sha256-ref ref)
           (package-reference/parse-sha256-ref ref)))))

(deftest keyword-domains-match-legacy-roots
  (doseq [[domain value] [[:research-analysis-comparison-v1 {:root "a"}]
                          [:research-analysis-composition-v1 {:root "b"}]
                          [:evidence-record {:id 7 :result :pass}]]]
    (is (= (legacy/domain-hash domain value)
           (package-hash/domain-hash domain value legacy/domain-tags)))))

(deftest owner-defined-intents-match-legacy-roots
  (doseq [[intent value] [[:research-analysis-comparison-v1 {:artifact/schema "v1"}]
                          [:research-analysis-composition-v1 {:artifact/schema "v1"}]
                          [:evidence-record {:attribution {:researcher "r1"}
                                             :result {:status :verified}}]]]
    (let [contract (legacy/resolve-intent intent)
          supplied (select-keys contract [:intent/domain-tag :intent/projection-fn])]
      (is (= (legacy/hash-with-intent {:hash/intent intent} value)
             (package-hash/hash-with-intent
              (assoc supplied :hash/intent intent)
              value)))))
  (is (package-hash/intent-hash=
       {:hash/intent :evidence-record :hash/hex "abc"}
       {:hash/intent :evidence-record :hash/hex "abc"}))
  (is (not (package-hash/intent-hash=
            {:hash/intent :evidence-record :hash/hex "abc"}
            {:hash/intent :world-state :hash/hex "abc"}))))

(deftest sequence-hashes-match-legacy-v1
  (doseq [[options components] [[{:purpose :research-command} []]
                                [{:purpose :research-command} [1 :x {:root "r"}]]
                                [{:purpose :evidence-content} [nil false ""]]]]
    (is (= (legacy-sequence/sequence-hash options components)
           (package-sequence/sequence-hash options components)))
    (is (= (seq (legacy-sequence/canonical-sequence-bytes options components))
           (seq (package-sequence/canonical-sequence-bytes options components))))))

(deftest sequence-verification-matches-legacy-classifications
  (let [valid (legacy-sequence/canonical-sequence-bytes {:purpose :conformance} [1 :x])
        truncated (byte-array [0x31 0x01 0x10])
        idx (loop [i 0]
              (if (and (= 0x10 (bit-and 0xff (aget ^bytes valid i)))
                       (= 0x02 (bit-and 0xff (aget ^bytes valid (inc i)))))
                i
                (recur (inc i))))
        noncanonical (byte-array (concat (take idx valid) [0x10 0x82 0x00]
                                         (drop (+ idx 2) valid)))
        trailing (byte-array (concat valid [0x00]))]
    (doseq [bytes [valid truncated noncanonical trailing]]
      (let [legacy-result (legacy-sequence/verify-sequence-commitment bytes)
            package-result (package-sequence/verify-sequence-commitment bytes)]
        (is (= (select-keys legacy-result [:valid? :value :resource-limit?
                                           :resource-reason])
               (select-keys package-result [:valid? :value :resource-limit?
                                            :resource-reason])))
        (is (= (mapv :code (:issues legacy-result))
               (mapv :code (:issues package-result))))))))
