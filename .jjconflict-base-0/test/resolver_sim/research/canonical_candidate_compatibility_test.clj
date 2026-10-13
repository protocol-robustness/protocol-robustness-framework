(ns resolver-sim.research.canonical-candidate-compatibility-test
  "Research-side compatibility checks for the C2 canonical package candidate.

   This test compares the candidate with the still-authoritative legacy
   compatibility API; it does not migrate callers or declare a release."
  (:require [clojure.test :refer [deftest is]]
            [prf.canonical.codec :as codec]
            [prf.canonical.hash :as package-hash]
            [prf.canonical.reference :as package-reference]
            [resolver-sim.hash.canonical :as legacy]
            [resolver-sim.hash.reference :as legacy-reference])
  (:import [java.util Arrays]))

(def ^:private research-v1-values
  [nil true false 0 -1 42 "research" :claim/example
   {:researcher/id "anon-01"
    :research-claim/status :verified
    :roots ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}])

(deftest canonical-package-codec-preserves-research-v1-bytes-and-roots
  (doseq [value research-v1-values]
    (is (Arrays/equals ^bytes (legacy/canonical-bytes value)
                       ^bytes (codec/canonical-bytes value))))
  (doseq [[domain body] [[:research-analysis-result {:result/value 42}]
                         [:researcher-reward-policy {:reward/currency "TEST"}]
                         [:research-command {:command/type :claim-evaluation}]]]
    (is (= (legacy/domain-hash domain body)
           (package-hash/hash-with-domain (get legacy/domain-tags domain) body)))))

(deftest canonical-package-reference-api-preserves-root-format
  (let [hex (apply str (repeat 64 "a"))
        ref (str "sha256:" hex)]
    (is (= (legacy-reference/sha256-ref hex)
           (package-reference/sha256-ref hex)))
    (is (= (legacy-reference/valid-sha256-ref? ref)
           (package-reference/valid-sha256-ref? ref)))
    (is (= (legacy-reference/parse-sha256-ref ref)
           (package-reference/parse-sha256-ref ref)))))
