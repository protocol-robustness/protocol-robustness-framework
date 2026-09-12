(ns resolver-sim.io.fixtures-test
  "Tests for fixture-reference resolution and fixture loading."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.fixtures :as sut]
            [resolver-sim.util.deep-merge :as dm]))

;; ---------------------------------------------------------------------------
;; fixture-key->path
;; ---------------------------------------------------------------------------

(deftest fixture-key->path-protocol
  (is (= "data/fixtures/protocol/kleros.edn"
         (sut/fixture-key->path :protocol/kleros))))

(deftest fixture-key->path-traces
  (is (= "data/fixtures/traces/s18-kleros.trace.json"
         (sut/fixture-key->path :traces/s18-kleros))))

(deftest fixture-key->path-bare
  (is (= "data/fixtures/bare.edn"
         (sut/fixture-key->path :bare))))

;; ---------------------------------------------------------------------------
;; valid-fixture-ref?
;; ---------------------------------------------------------------------------

(deftest valid-fixture-ref-accepted
  (is (true? (sut/valid-fixture-ref? :protocol/kleros)))
  (is (true? (sut/valid-fixture-ref? :states/minimal-world)))
  (is (true? (sut/valid-fixture-ref? :actors/honest-buyer)))
  (is (true? (sut/valid-fixture-ref? :traces/s18))))

(deftest valid-fixture-ref-rejected
  (is (false? (sut/valid-fixture-ref? :unknown/foo)))
  (is (false? (sut/valid-fixture-ref? :protocol)))
  (is (false? (sut/valid-fixture-ref? "protocol/kleros"))))

;; ---------------------------------------------------------------------------
;; normalize-fixture-ref
;; ---------------------------------------------------------------------------

(deftest normalize-fixture-ref-from-keyword
  (is (= :protocol/kleros (sut/normalize-fixture-ref :protocol/kleros))))

(deftest normalize-fixture-ref-from-string
  (is (= :protocol/kleros (sut/normalize-fixture-ref "protocol/kleros"))))

(deftest normalize-fixture-ref-rejects-invalid
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/normalize-fixture-ref 42))))

;; ---------------------------------------------------------------------------
;; load-fixture
;; ---------------------------------------------------------------------------

(deftest load-fixture-kleros
  (let [fixture (sut/load-fixture :protocol/kleros)]
    (is (map? fixture))
    (is (= "0xkleros-proxy" (:resolution-module fixture)))
    (is (contains? fixture :escalation-resolvers))
    (is (= "0xl0" (get-in fixture [:escalation-resolvers :0])))))

(deftest load-fixture-unknown-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/load-fixture :protocol/nonexistent))))

;; ---------------------------------------------------------------------------
;; fixture-content-hash
;; ---------------------------------------------------------------------------

(deftest fixture-content-hash-known
  (let [hash (sut/fixture-content-hash "data/fixtures/protocol/kleros.edn")]
    (is (string? hash))
    (is (= 64 (count hash)) "SHA-256 hex is 64 chars")))

(deftest fixture-content-hash-missing
  (is (nil? (sut/fixture-content-hash "data/fixtures/protocol/nonexistent.edn"))))

;; ---------------------------------------------------------------------------
;; resolve-protocol-params-ref
;; ---------------------------------------------------------------------------

(deftest resolve-protocol-params-ref-adds-fixture
  (let [scenario {:scenario-id "test" :protocol-params-ref "protocol/kleros" :events []}
        result   (sut/resolve-protocol-params-ref scenario)]
    (is (contains? result :protocol-params))
    (is (contains? result :fixture-refs))
    (is (contains? result :effective-protocol-params))
    (is (not (contains? result :protocol-params-ref)))
    (is (= "0xkleros-proxy" (:resolution-module (:protocol-params result))))
    (is (= "0xl0" (get-in (:protocol-params result) [:escalation-resolvers :0])))))

(deftest resolve-protocol-params-ref-edn-keyword
  (let [scenario {:scenario-id "test" :protocol-params-ref :protocol/kleros :events []}
        result   (sut/resolve-protocol-params-ref scenario)]
    (is (contains? result :protocol-params))
    (is (= "0xkleros-proxy" (:resolution-module (:protocol-params result))))))

(deftest resolve-protocol-params-ref-override
  (let [scenario {:scenario-id "test"
                  :protocol-params-ref "protocol/kleros"
                  :protocol-params {:max-dispute-level 5}
                  :events []}
        result   (sut/resolve-protocol-params-ref scenario)]
    (is (= 5 (:max-dispute-level (:protocol-params result))) "inline wins")
    (is (= "0xkleros-proxy" (:resolution-module (:protocol-params result))) "fixture provides rest")))

(deftest resolve-protocol-params-ref-deep-merge
  (let [scenario {:scenario-id "test"
                  :protocol-params-ref "protocol/kleros"
                  :protocol-params {:escalation-resolvers {:0 "0xoverride"}}
                  :events []}
        result   (sut/resolve-protocol-params-ref scenario)]
    (is (= "0xoverride" (get-in (:protocol-params result) [:escalation-resolvers :0])) "inline overrides fixture at level 0")
    (is (= "0xl1" (get-in (:protocol-params result) [:escalation-resolvers :1])) "fixture provides level 1")
    (is (= "0xl2" (get-in (:protocol-params result) [:escalation-resolvers :2])) "fixture provides level 2")))

(deftest resolve-protocol-params-ref-fixture-refs-structure
  (let [scenario {:scenario-id "test" :protocol-params-ref "protocol/kleros" :events []}
        result   (sut/resolve-protocol-params-ref scenario)
        refs     (:fixture-refs result)]
    (is (= 1 (count refs)))
    (let [entry (first refs)]
      (is (= :protocol-params (:slot entry)))
      (is (= :protocol/kleros (:ref entry)))
      (is (string? (:path entry)))
      (is (string? (:content-hash entry)))
      (is (= 64 (count (:content-hash entry)))))))

(deftest resolve-protocol-params-ref-no-ref-passthrough
  (let [scenario {:scenario-id "test" :protocol-params {:foo 1}}
        result   (sut/resolve-protocol-params-ref scenario)]
    (is (= scenario result))))

(deftest resolve-protocol-params-ref-invalid-namespace
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/resolve-protocol-params-ref
                {:scenario-id "test" :protocol-params-ref "invalid/foo" :events []}))))

;; ---------------------------------------------------------------------------
;; fixture-exists?
;; ---------------------------------------------------------------------------

(deftest fixture-exists-known
  (is (true? (sut/fixture-exists? :protocol/kleros)))
  (is (true? (sut/fixture-exists? :traces/eq-v1-budget-balance-pass))))

(deftest fixture-exists-missing
  (is (false? (sut/fixture-exists? :protocol/nonexistent))))

(deftest fixture-exists-invalid-namespace
  (is (false? (sut/fixture-exists? :unknown/foo))))

(deftest fixture-exists-bare-keyword
  (is (false? (sut/fixture-exists? :protocol))))

;; ---------------------------------------------------------------------------
;; valid-fixture-reference? (full check: namespace + file existence)
;; ---------------------------------------------------------------------------

(deftest valid-fixture-reference-known
  (is (true? (sut/valid-fixture-reference? :protocol/kleros)))
  (is (true? (sut/valid-fixture-reference? :protocol/baseline)))
  (is (true? (sut/valid-fixture-reference? :traces/eq-v1-budget-balance-pass))))

(deftest valid-fixture-reference-missing-file
  (is (false? (sut/valid-fixture-reference? :protocol/nonexistent))))

(deftest valid-fixture-reference-invalid-namespace
  (is (false? (sut/valid-fixture-reference? :unknown/foo))))

(deftest valid-fixture-reference-bare-keyword
  (is (false? (sut/valid-fixture-reference? :protocol))))

(deftest valid-fixture-reference-string-rejected
  (is (false? (sut/valid-fixture-reference? "protocol/kleros"))))

;; ---------------------------------------------------------------------------
;; deep-merge (from util namespace)
;; ---------------------------------------------------------------------------

(deftest deep-merge-scalar-right-wins
  (is (= {:a 2} (dm/deep-merge {:a 1} {:a 2}))))

(deftest deep-merge-nested
  (is (= {:a {:b 1 :c 2}} (dm/deep-merge {:a {:b 1}} {:a {:c 2}}))))

(deftest deep-merge-multi-level
  (is (= {:x {:y {:z 3}}} (dm/deep-merge {:x {:y {:z 1}}} {:x {:y {:z 3}}}))))

(deftest deep-merge-nil-right
  (is (= {:a 1} (dm/deep-merge {:a 1} nil))))

(deftest deep-merge-nil-left
  (is (= {:a 1} (dm/deep-merge nil {:a 1}))))
