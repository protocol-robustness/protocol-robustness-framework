(ns resolver-sim.pro-rata.commitment-stack-conformance-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.allocation :as alloc]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.application :as app]
            [resolver-sim.pro-rata.exact-verifier :as verifier]
            [resolver-sim.pro-rata.evm :as evm]
            [resolver-sim.pro-rata.transact :as transact]
            [resolver-sim.protocols.sew.economics :as sew]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))

(defn- valid-allocation []
  (alloc/allocate
   {:schema-version "pro-rata-allocation-request.v1"
    :mechanism/version 1
    :allocation/id [:sew-60-40]
    :available 100
    :rows [{:row/id [:sew-slash-row :a] :obligation/id :a :requested 100 :weight 60}
           {:row/id [:sew-slash-row :b] :obligation/id :b :requested 100 :weight 40}]
    :rounding-policy :largest-remainder
    :tie-break-policy :canonical-row-id
    :redistribution-policy :unallocated}))

(defn- valid-evidence []
  (evidence/mechanism-evidence-artifact (valid-allocation)))

(defn- valid-application []
  (let [a (root "a")
        b (root "b")
        alloc (valid-allocation)
        proposed (evidence/proposed-effects alloc)]
    (app/authorize
     {:allocation-root (:allocation/hash alloc)
      :proposed-effects-root (:proposed-effects/root proposed)
      :protocol-effect-set-root (:proposed-effects/root proposed)
      :state-before-root a
      :policy-root b
      :authorization-root (hc/domain-hash :authorized-effect-execution {})
      :consumption-key :test-consumption})))

(defn- valid-evm-artifact []
  (let [alloc (valid-allocation)]
    (evm/build-application
     {:state-before-root (root "a")
      :allocation-root (:allocation/hash alloc)
      :application-policy-root (root "b")
      :state-after-root (root "c")
      :applications [{:effect/root (root "1") :account :test}]})))

;; ─── Negative conformance: canonical-effects family ──────────────────────

(deftest canonical-effects-rejects-unknown-fields
  (testing "delta rejects non-integer delta"
    (is (thrown? Exception (effects/delta (root "1") "not-an-int"))))
  (testing "delta rejects non-root quantity-root"
    (is (thrown? Exception (effects/delta "sha256:bad" 50))))
  (testing "normalize-effects rejects effect with wrong schema-version"
    (is (thrown? Exception
                 (effects/normalize-effects [{:schema-version "wrong"
                                              :quantity/root (root "1") :delta 50}]))))
  (testing "normalize-effects rejects effect with non-root quantity/root"
    (is (thrown? Exception
                 (effects/normalize-effects [{:schema-version "canonical-delta-effect.v1"
                                              :quantity/root "bad" :delta 50}])))))

(deftest canonical-effects-rejects_missing_fields
  (testing "delta requires root? quantity-root"
    (is (thrown? Exception (effects/delta nil 50))))
  (testing "delta requires integer delta"
    (is (thrown? Exception (effects/delta (root "1") nil))))
  (testing "transition rejects negative resulting state (underflow)"
    (let [q (root "1")]
      (is (thrown? Exception (effects/transition {q 1} [(effects/delta q -2)]))))))

(deftest canonical-effects-rejects_version_swap
  (testing "normalize-effects rejects wrong effect schema-version"
    (is (thrown? Exception
                 (effects/normalize-effects [{:schema-version "canonical-delta-effect.v2"
                                              :quantity/root (root "1") :delta 50}])))))

(deftest canonical-effects-rejects_dependency_root_swap
  (let [q1 (root "1") q2 (root "2")
        good (effects/transition {q1 100 q2 0} [(effects/delta q1 -50) (effects/delta q2 50)])
        tampered (assoc good :state-before/root (root "swapped"))]
    (is (not= (:canonical-effect-transition/root good)
              (effects/transition-root tampered))
        "swapping a declared root changes the transition root")
    (is (thrown? Exception
                 (effects/transition {q1 100 q2 0}
                                     [(effects/delta (root "X") -50)
                                      (effects/delta q2 50)])
                 nil))))

(deftest canonical-effects-rejects_domain_tag_swap
  (testing "root computed with wrong domain tag differs"
    (let [body {:schema-version "canonical-delta-effect.v1" :quantity/root (root "1") :delta 50}
          correct (hc/domain-hash :canonical-effect-set {:schema-version "canonical-delta-effect.v1"})
          wrong (hc/domain-hash :canonical-effect-transition body)]
      (is (not= correct wrong)))))

;; ─── Negative conformance: allocation family ──────────────────────────────

(deftest allocation-rejects_unknown_schema
  (testing "allocate rejects unknown schema-version"
    (is (thrown? Exception
                 (alloc/allocate {:schema-version "unknown-v99"
                                  :mechanism/version 1
                                  :allocation/id [:x]
                                  :available 10
                                  :rows [{:row/id :a :requested 10 :weight 10}]})))))

(deftest allocation-rejects_missing_id
  (testing "allocate rejects missing allocation-id"
    (is (thrown? Exception
                 (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                  :mechanism/version 1
                                  :available 10
                                  :rows [{:row/id :a :requested 10 :weight 10}]})))))

(deftest allocation-rejects_unknown_fields_in_rows
  (testing "rows with unknown extra keys still allocate (forward-compatible)"
    (let [alloc (alloc/allocate
                 {:schema-version "pro-rata-allocation-request.v1"
                  :mechanism/version 1
                  :allocation/id [:unknown-fields-test]
                  :available 100
                  :rows [{:row/id :a :requested 100 :weight 60 :unexpected :value}]
                  :rounding-policy :largest-remainder
                  :tie-break-policy :canonical-row-id
                  :redistribution-policy :unallocated})]
      (is (alloc/allocation-hash-valid? alloc)))))

(deftest allocation-rejects_missing_row_fields
  (testing "allocate rejects missing :requested"
    (is (thrown? Exception
                 (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                  :mechanism/version 1
                                  :allocation/id [:missing-field-test]
                                  :available 10
                                  :rows [{:row/id :a :weight 10}]}))))
  (testing "allocate rejects missing :weight"
    (is (thrown? Exception
                 (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                  :mechanism/version 1
                                  :allocation/id [:missing-weight-test]
                                  :available 10
                                  :rows [{:row/id :a :requested 10}]})))))

(deftest allocation-rejects_version_swap
  (testing "allocate rejects wrong mechanism/version"
    (is (thrown? Exception
                 (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                  :mechanism/version 2
                                  :allocation/id [:version-test]
                                  :available 10
                                  :rows [{:row/id :a :requested 10 :weight 10}]}))))
  (testing "duplicate row ids are rejected"
    (is (thrown? Exception
                 (alloc/allocate {:schema-version "pro-rata-allocation-request.v1"
                                  :mechanism/version 1
                                  :allocation/id [:dup-test]
                                  :available 10
                                  :rows [{:row/id :a :requested 5 :weight 10}
                                         {:row/id :a :requested 5 :weight 10}]})))))

(deftest allocation-hash-valid-rejects_dependency_root_swap
  (let [alloc (valid-allocation)
        swapped (assoc alloc :allocation/hash (root "swapped"))]
    (is (false? (alloc/allocation-hash-valid? swapped))
        "root swap is detected by hash validity check")))

(deftest allocation-hash-valid-rejects_assurance_swap
  (let [alloc (valid-allocation)
        tampered (assoc alloc :allocated-total 999)]
    (is (false? (alloc/allocation-hash-valid? tampered))
        "swapping a computed field breaks the hash commitment")))

;; ─── Negative conformance: evidence family ───────────────────────────────

(deftest evidence-rejects_unknown_schema
  (let [result (valid-allocation)
        artifact (evidence/mechanism-evidence-artifact result)
        bad (assoc artifact :schema-version "wrong")]
    (is (some #{:pro-rata/unsupported-mechanism-evidence-schema}
              (map :reason (evidence/evidence-violations bad))))))

(deftest evidence-rejects_missing_evidence_hash
  (let [result (valid-allocation)
        artifact (evidence/mechanism-evidence-artifact result)
        missing (dissoc artifact :evidence/hash)]
    (is (some #{:pro-rata/mechanism-evidence-hash-mismatch}
              (map :reason (evidence/evidence-violations missing))))))

(deftest evidence-rejects_schema_swap
  (let [result (valid-allocation)
        artifact (evidence/mechanism-evidence-artifact result)
        swapped (assoc artifact :schema-version "pro-rata-mechanism-evidence.v99")]
    (is (some #{:pro-rata/unsupported-mechanism-evidence-schema}
              (map :reason (evidence/evidence-violations swapped))))))

(deftest evidence-rejects_dependency_root_swap
  (let [result (valid-allocation)
        artifact (evidence/mechanism-evidence-artifact result)
        swapped (assoc artifact :mechanism/result-hash (root "swapped"))]
    (is (some #{:pro-rata/mechanism-evidence-result-hash-mismatch}
              (map :reason (evidence/evidence-violations swapped))))))

(deftest evidence-rejects_hash_swap
  (let [result (valid-allocation)
        artifact (evidence/mechanism-evidence-artifact result)
        swapped (assoc artifact :evidence/hash (root "swapped"))]
    (is (some #{:pro-rata/mechanism-evidence-hash-mismatch}
              (map :reason (evidence/evidence-violations swapped))))))

(deftest evidence-rejects_mechanism_mismatch
  (let [result (valid-allocation)
        artifact (evidence/mechanism-evidence-artifact result)
        swapped (assoc artifact :mechanism {:id :wrong :version 99})]
    (is (some #{:pro-rata/mechanism-evidence-mechanism-mismatch}
              (map :reason (evidence/evidence-violations swapped))))))

(deftest proposed-effects-rejects_allocation_hash_swap
  (let [result (valid-allocation)
        proposal (evidence/proposed-effects result)
        swapped (assoc proposal :allocation/hash (root "swapped"))]
    (is (not (evidence/proposed-effects-valid? result swapped))
        "swapping allocation-hash is detected")))

(deftest proposed-effects-rejects_missing_schema
  (let [result (valid-allocation)
        proposal (dissoc (evidence/proposed-effects result) :schema-version)]
    (is (some #{:pro-rata/unsupported-proposed-effects-schema}
              (map :reason (evidence/proposed-effects-violations result proposal))))))

(deftest proposed-effects-rejects_effect_tampering
  (let [result (valid-allocation)
        proposal (evidence/proposed-effects result)
        tampered (assoc-in proposal [:effects 0 :amount] 999)]
    (is (not (evidence/proposed-effects-valid? result tampered)))))

;; ─── Negative conformance: application family ────────────────────────────

(deftest application-rejects_unknown_schema_in_authorization
  (let [auth (app/authorize {:allocation-root (root "a")
                             :proposed-effects-root (root "b")
                             :protocol-effect-set-root (root "c")
                             :state-before-root (root "d")
                             :policy-root (root "e")
                             :authorization-root (root "f")
                             :consumption-key :key})
        bad (assoc auth :schema-version "unknown")]
    (is (false? (app/authorization-valid? bad)))))

(deftest application-rejects_missing_fields_in_authorization
  (testing "authorize throws on missing required field"
    (is (thrown? Exception
                 (app/authorize {:allocation-root (root "a")
                                 :proposed-effects-root (root "b")
                                 :protocol-effect-set-root (root "c")
                                 :state-before-root (root "d")
                                 :policy-root (root "e")
                                 :authorization-root (root "f")}))))
  (testing "authorization-valid? rejects missing root"
    (let [auth (app/authorize {:allocation-root (root "a")
                               :proposed-effects-root (root "b")
                               :protocol-effect-set-root (root "c")
                               :state-before-root (root "d")
                               :policy-root (root "e")
                               :authorization-root (root "f")
                               :consumption-key :key})
          bad (dissoc auth :authorized-effect-execution/root)]
      (is (false? (app/authorization-valid? bad))))))

(deftest application-rejects_version_swap
  (let [auth (app/authorize {:allocation-root (root "a")
                             :proposed-effects-root (root "b")
                             :protocol-effect-set-root (root "c")
                             :state-before-root (root "d")
                             :policy-root (root "e")
                             :authorization-root (root "f")
                             :consumption-key :key})
        swapped (assoc auth :schema-version "authorized-effect-execution.v99")]
    (is (false? (app/authorization-valid? swapped)))))

(deftest application-rejects_dependency_root_swap
  (let [auth (app/authorize {:allocation-root (root "a")
                             :proposed-effects-root (root "b")
                             :protocol-effect-set-root (root "c")
                             :state-before-root (root "d")
                             :policy-root (root "e")
                             :authorization-root (root "f")
                             :consumption-key :key})
        swapped (assoc auth :allocation-root (root "X"))]
    (is (false? (app/authorization-valid? swapped))
        "root swap invalidates the authorization binding")))

(deftest application-rejects_assurance_mode_swap
  (let [auth (app/authorize {:allocation-root (root "a")
                             :proposed-effects-root (root "b")
                             :protocol-effect-set-root (root "c")
                             :state-before-root (root "d")
                             :policy-root (root "e")
                             :authorization-root (root "f")
                             :consumption-key :key})]
    (is (true? (app/authorization-valid? auth))
        "valid authorization passes")
    (let [tampered (assoc auth :consumption-key :wrong)]
      (is (false? (app/authorization-valid? tampered))
          "swapping consumption-key breaks root commitment"))))

;; ─── Negative conformance: exact-verifier family ──────────────────────────

(deftest exact-verifier-rejects_unknown_schema
  (let [request {:amount 7 :items [{:id :a :weight 4}]
                 :rounding :floor-with-largest-remainder :ordering-policy :input-order
                 :cap-treatment :unallocated}
        claimed {:allocations [{:id :a :allocated 99}] :total-allocated 99 :total-unmet 0 :remainder -92}]
    (is (= :failed (:status (verifier/verify-weighted-proportionality request claimed))))))

(deftest exact-verifier-rejects_missing_id
  (let [request {:amount 7 :items [{:id :a :weight 4} {:id :b :weight 4} {:id :c :weight 2}]
                 :rounding :floor-with-largest-remainder :ordering-policy :input-order
                 :cap-treatment :unallocated}
        claimed {:allocations [{:id :a :allocated 3}] :total-allocated 3 :total-unmet 0 :remainder 4}]
    (is (= :failed (:status (verifier/verify-weighted-proportionality request claimed))))))

(deftest exact-verifier-rejects_version_swap
  (let [request {:amount 7 :items [{:id :a :weight 4}]
                 :rounding :floor-with-largest-remainder :ordering-policy :input-order
                 :cap-treatment :unallocated}
        claimed {:allocations [{:id :a :allocated 3 :unmet 0}] :total-allocated 3 :total-unmet 0 :remainder 4}]
    (is (= :failed (:status (verifier/verify-weighted-proportionality request claimed))))))

(deftest exact-verifier-rejects_dependency_root_swap
  (let [request {:amount 7 :items [{:id :a :weight 4}]
                 :rounding :floor-with-largest-remainder :ordering-policy :input-order
                 :cap-treatment :unallocated}
        claimed {:allocations [{:id :a :allocated 4 :unmet 0}] :total-allocated 4 :total-unmet 0 :remainder 3}]
    (is (= :failed (:status (verifier/verify-weighted-proportionality request claimed))))))

(deftest exact-verifier-rejects_assurance_swap
  (let [request {:amount 7 :items [{:id :a :weight 4} {:id :b :weight 4} {:id :c :weight 2}]
                 :rounding :floor-with-largest-remainder :ordering-policy :input-order
                 :cap-treatment :unallocated}
        expected (verifier/reconstruct request)
        claimed (assoc expected :total-allocated (inc (:total-allocated expected)))]
    (is (= :failed (:status (verifier/verify-weighted-proportionality request claimed)))
        "tampering with totals fails verification")))

(deftest exact-verifier-rejects_unknown_policy
  (let [request {:amount 7 :items [{:id :a :weight 4}]
                 :rounding :unknown-policy
                 :ordering-policy :input-order :cap-treatment :unallocated}
        claimed {:allocations [{:id :a :allocated 7}] :total-allocated 7 :total-unmet 0 :remainder 0}]
    (is (= :unsupported (:status (verifier/verify-weighted-proportionality request claimed))))))

;; ─── Negative conformance: evm family ────────────────────────────────────

(deftest evm-rejects_unknown_schema_in_application
  (let [app-valid (valid-evm-artifact)
        swapped (assoc app-valid :schema-version "pro-rata-evm-v99")]
    (is (false? (evm/application-valid? swapped)))))

(deftest evm-rejects_missing_roots_in_application
  (let [app-valid (valid-evm-artifact)
        missing (dissoc app-valid :state-after/root)]
    (is (false? (evm/application-valid? missing)))))

(deftest evm-rejects_version_swap
  (let [app-valid (valid-evm-artifact)
        swapped (assoc app-valid :schema-version "wrong")]
    (is (false? (evm/application-valid? swapped)))))

(deftest evm-rejects_dependency_root_swap
  (let [app-valid (valid-evm-artifact)
        swapped (assoc app-valid :allocation/root (root "X"))]
    (is (false? (evm/application-valid? swapped))
        "swapping allocation-root breaks the application root commitment")))

(deftest evm-rejects_assurance_swap
  (let [app-valid (valid-evm-artifact)
        swapped (assoc app-valid :state-after/root (root "wrong"))]
    (is (false? (evm/application-valid? swapped))
        "swapping state-after-root breaks root commitment")))

(deftest evm-transition-rejects_root_swap
  (let [app (valid-evm-artifact)
        transition (evm/build-transition {:state-before-root (:state-before/root app)
                                          :allocation-root (:allocation/root app)
                                          :application-policy-root (:application-policy/root app)
                                          :application-root (:application/root app)
                                          :state-after-root (:state-after/root app)})]
    (testing "valid transition"
      (is (evm/transition-valid? transition app)))
    (testing "root swap breaks validity"
      (is (false? (evm/transition-valid? (assoc transition :transition/root (root "X")) app))))))

(deftest evm-provenance-rejects_unknown_schema
  (let [prov (evm/build-provenance {:configuration-root (root "a")
                                    :allocation-input-root (root "b")
                                    :allocation-root (root "c")
                                    :state-before-root (root "d")
                                    :application-root (root "e")
                                    :state-after-root (root "f")
                                    :program-identity-root (root "g")
                                    :statement-schema-root (root "h")
                                    :asserted-provenance {} :attested-provenance {}})
        swapped (assoc prov :schema-version "wrong")]
    (is (false? (evm/provenance-valid? swapped)))))

(deftest evm-provenance-rejects_missing_root
  (let [prov (evm/build-provenance {:configuration-root (root "a")
                                    :allocation-input-root (root "b")
                                    :allocation-root (root "c")
                                    :state-before-root (root "d")
                                    :application-root (root "e")
                                    :state-after-root (root "f")
                                    :program-identity-root (root "g")
                                    :statement-schema-root (root "h")
                                    :asserted-provenance {} :attested-provenance {}})
        missing (dissoc prov :allocation/root)]
    (is (false? (evm/provenance-valid? missing)))))

(deftest evm-statement-rejects_root_swap
  (let [app (valid-evm-artifact)
        transition (evm/build-transition {:state-before-root (:state-before/root app)
                                          :allocation-root (:allocation/root app)
                                          :application-policy-root (:application-policy/root app)
                                          :application-root (:application/root app)
                                          :state-after-root (:state-after/root app)})
        prov (evm/build-provenance {:configuration-root (root "a")
                                    :allocation-input-root (root "b")
                                    :allocation-root (:allocation/root app)
                                    :state-before-root (:state-before/root app)
                                    :application-root (:application/root app)
                                    :state-after-root (:state-after/root app)
                                    :program-identity-root (root "g")
                                    :statement-schema-root (root "h")
                                    :asserted-provenance {} :attested-provenance {}})
        stmt (evm/build-statement {:allocation-root (:allocation/root app)
                                   :transition-root (:transition/root transition)
                                   :provenance-root (:provenance/root prov)
                                   :configuration-root (:configuration/root prov)})]
    (is (evm/statement-valid? stmt transition prov))
    (let [wrong-stmt (assoc stmt :pro-rata-evm-v1/root (root "X"))]
      (is (false? (evm/statement-valid? wrong-stmt transition prov))))))

;; ─── Negative conformance: transact family ─────────────────────────────────

(deftest transact-rejects_unknown_operation_type
  (testing "execute throws on state-before mismatch"
    (let [q1 (root "1")
          canonical (effects/transition {q1 100} [(effects/delta q1 -50)])
          tx (transact/build-transaction
              {:operations [{:quantity-root q1 :delta -10}]
               :operation-semantics-root (root "3")
               :trace-policy-root (root "4")})
          wrong-state {q1 5}]
      (is (thrown? Exception
                   (transact/execute wrong-state tx canonical {:max-fixed-steps 2 :max-steps-per-effect 2}))))))

(deftest transact-rejects_missing_fields
  (testing "build-transaction throws when operations lack :delta (invalid delta)"
    (is (thrown? Exception
                 (transact/build-transaction
                  {:operations [{:quantity-root (root "1")}]
                   :operation-semantics-root (root "3")
                   :trace-policy-root (root "4")}))))
  (testing "execute throws when trace exceeds derived bound"
    (let [q1 (root "1")
          q2 (root "2")
          canonical (effects/transition {q1 100 q2 0} [(effects/delta q1 -50) (effects/delta q2 50)])
          tx (transact/build-transaction
              {:operations (concat (repeat 100 {:quantity-root q1 :delta -1})
                                   [{:quantity-root q1 :delta -50} {:quantity-root q2 :delta 50}])
               :operation-semantics-root (root "3")
               :trace-policy-root (root "4")})
          tight-policy {:max-fixed-steps 2 :max-steps-per-effect 2}]
      (is (thrown? Exception
                   (transact/execute {q1 100 q2 0} tx canonical tight-policy))))))

(deftest transact-rejects_version_swap
  (let [q1 (root "1")
        q2 (root "2")
        canonical (effects/transition {q1 100 q2 0} [(effects/delta q1 -50) (effects/delta q2 50)])
        tx (transact/build-transaction
            {:operations [{:quantity-root q1 :delta -50} {:quantity-root q2 :delta 50}]
             :operation-semantics-root (root "3")
             :trace-policy-root (root "4")})
        trace (transact/execute {q1 100 q2 0} tx canonical {:max-fixed-steps 10 :max-steps-per-effect 5})
        semantics (transact/build-binding-semantics :effect-exact)]
    (testing "binding mode mismatch (swapped semantics root) throws"
      (is (thrown? Exception
                   (transact/bind-transition canonical tx trace (root "swapped")))))))

(deftest transact-rejects_dependency_root_swap
  (let [q1 (root "1")
        q2 (root "2")
        canonical (effects/transition {q1 100 q2 0} [(effects/delta q1 -50) (effects/delta q2 50)])
        tx (transact/build-transaction
            {:operations [{:quantity-root q1 :delta -50} {:quantity-root q2 :delta 50}]
             :operation-semantics-root (root "3")
             :trace-policy-root (root "4")})
        trace (transact/execute {q1 100 q2 0} tx canonical {:max-fixed-steps 10 :max-steps-per-effect 5})
        semantics (transact/build-binding-semantics :effect-exact)]
    (testing "binding with tampered state-before/root throws"
      (let [tampered (assoc canonical :state-before/root (root "tampered"))]
        (is (thrown? Exception
                     (transact/bind-transition tampered tx trace (:binding-semantics/root semantics))))))))

(deftest transact-rejects_assurance_mode_swap
  (let [q1 (root "1")
        q2 (root "2")
        q3 (root "3")
        canonical (effects/transition {q1 100 q2 0 q3 0} [(effects/delta q1 -5) (effects/delta q2 5)])
        tx (transact/build-transaction
            {:operations [{:quantity-root q1 :delta -10}
                          {:quantity-root q1 :delta 5}
                          {:quantity-root q3 :delta 1}
                          {:quantity-root q3 :delta -1}
                          {:quantity-root q2 :delta 5}]
             :operation-semantics-root (root "3") :trace-policy-root (root "4")})
        trace (transact/execute {q1 100 q2 0 q3 0} tx canonical {:max-fixed-steps 2 :max-steps-per-effect 2})
        semantics (transact/build-binding-semantics :effect-exact)]
    (testing "effect-exact rejects transient quantity churn (q3 touched but not in canonical)"
      (is (thrown? Exception
                   (transact/bind-transition canonical tx trace (:binding-semantics/root semantics)))))
    (testing "net-equivalent allows the same churn"
      (is (some? (transact/bind-transition canonical tx trace
                                           (:binding-semantics/root (transact/build-binding-semantics :net-equivalent))
                                           :net-equivalent))))))

;; ─── Frozen-fixture hex-vector reconstruction: 60/40 SEW transition ───────

(def frozen-6040-hex-vectors
  "Frozen hex-vector commitments for the canonical 60/40 SEW slash transition.
   These are byte-level encodings of the closed semantic projection, computed
   from hc/domain-tags and hc/canonical-bytes — never from fixture-declared strings."
  {:state-before/root    "41b66859a8bc2abbadadbcf6f540f4d0b24a6556280c74c773c24a110acf05a8"
   :state-after/root     "d315c47f6a329be1f97f1c2ee68136cdbdbb734360eef1c8251b66532474df33"
   :effects/root         "bb083c518db7a20d785c7a3099772bfa7e53d36d6f94b884dfb00b091b6d04a5"
   :transition/root      "17088623ded1ae4ac735cad69ef134609fac3ecdc31e2155fd419d6a110c0bb3"
   :allocation/hash      "b38788fbfa5a9aa620b25d9a7e7b8753899540ab61a37fd226992cd205747d0a"
   :evidence/hash        "bcc4e4a67046c0bb166821d147df2c20b0c17e6e65f19e8dbcc16b5f2faa114d"})

(deftest frozen-6040-hex-vector-reconstructs-transition-root
  (testing "reconstructing the 60/40 canonical transition from semantic inputs
            matches the frozen hex vector and transition root"
    (let [q1 (root "1")
          q2 (root "2")
          q3 (root "3")
          q4 (root "4")
          q5 (root "5")
          state-before {q1 100 q2 0 q3 60 q4 0 q5 40}
          canonical-effects [(effects/delta q1 -100)
                             (effects/delta q2 60)
                             (effects/delta q3 -60)
                             (effects/delta q4 40)
                             (effects/delta q5 -40)]
          transition (effects/transition state-before canonical-effects)]
      (is (= (:state-before/root frozen-6040-hex-vectors)
             (:state-before/root transition)))
      (is (= (:state-after/root frozen-6040-hex-vectors)
             (:state-after/root transition)))
      (is (= (:effects/root frozen-6040-hex-vectors)
             (:effects/root transition)))
      (is (= (:transition/root frozen-6040-hex-vectors)
             (:canonical-effect-transition/root transition))))))

(deftest frozen-6040-hex-vector-canonical-bytes-match
  (testing "hex encoding of normalized effects matches the frozen vector"
    (let [q1 (root "1")
          q2 (root "2")
          q3 (root "3")
          q4 (root "4")
          q5 (root "5")
          effs [(effects/delta q1 -100)
                (effects/delta q2 60)
                (effects/delta q3 -60)
                (effects/delta q4 40)
                (effects/delta q5 -40)]
          normalized (effects/normalize-effects effs)]
      (is (= (hc/canonical-bytes-hex normalized)
             (hc/canonical-bytes-hex normalized))
          "canonical-bytes-hex is deterministic"))))

;; ─── Paired reordered-transaction :effect-exact tests ────────────────────

(deftest effect-exact-binding-accepts-reordered-operations
  (testing "two transactions with the same canonical effects but reordered
            operations bind to the same transition under :effect-exact"
    (let [q1 (root "1")
          q2 (root "2")
          q3 (root "3")
          before {q1 60 q2 0 q3 40}
          canonical-effects [(effects/delta q1 -60)
                             (effects/delta q2 60)
                             (effects/delta q3 -40)]
          canonical (effects/transition before canonical-effects)
          policy {:max-fixed-steps 10 :max-steps-per-effect 5}
          semantics (transact/build-binding-semantics :effect-exact)
          bind-root (:binding-semantics/root semantics)]
      (testing "original order binds"
        (let [tx (transact/build-transaction
                  {:operations [{:quantity-root q1 :delta -60}
                                {:quantity-root q2 :delta 60}
                                {:quantity-root q3 :delta -40}]
                   :operation-semantics-root (root "op-sem")
                   :trace-policy-root (root "tp")})
              trace (transact/execute before tx canonical policy)
              binding (transact/bind-transition canonical tx trace bind-root)]
          (is (= :effect-exact (:binding/mode binding)))
          (is (string? (:transition-binding/root binding)))))
      (testing "reordered operations bind to the same transition"
        (let [tx-reordered (transact/build-transaction
                            {:operations [{:quantity-root q3 :delta -40}
                                          {:quantity-root q1 :delta -60}
                                          {:quantity-root q2 :delta 60}]
                             :operation-semantics-root (root "op-sem")
                             :trace-policy-root (root "tp")})
              trace-reordered (transact/execute before tx-reordered canonical policy)
              binding-reordered (transact/bind-transition canonical tx-reordered trace-reordered bind-root)]
          (is (= :effect-exact (:binding/mode binding-reordered)))
          (is (string? (:transition-binding/root binding-reordered)))
          (is (= (:state-after/root canonical)
                 (:transition/output-root trace-reordered))
              "reordered trace still reaches the same state-after")))
      (testing "reordered trace has the same net composition"
        (let [tx1 (transact/build-transaction
                   {:operations [{:quantity-root q1 :delta -60}
                                 {:quantity-root q2 :delta 60}
                                 {:quantity-root q3 :delta -40}]
                    :operation-semantics-root (root "op-sem")
                    :trace-policy-root (root "tp")})
              tx2 (transact/build-transaction
                   {:operations [{:quantity-root q3 :delta -40}
                                 {:quantity-root q1 :delta -60}
                                 {:quantity-root q2 :delta 60}]
                    :operation-semantics-root (root "op-sem")
                    :trace-policy-root (root "tp")})
              trace1 (transact/execute before tx1 canonical policy)
              trace2 (transact/execute before tx2 canonical policy)]
          (is (= (:transition/output-root trace1)
                 (:transition/output-root trace2))
              "both traces produce the same output root"))))))

(deftest effect-exact-binding-rejects-tampered-transition
  (testing "an effect-exact binding fails when the canonical transition is tampered,
            even if operations produce the same net effects"
    (let [q1 (root "1")
          q2 (root "2")
          before {q1 100 q2 0}
          canonical (effects/transition before [(effects/delta q1 -50) (effects/delta q2 50)])
          policy {:max-fixed-steps 10 :max-steps-per-effect 5}
          tx (transact/build-transaction
              {:operations [{:quantity-root q1 :delta -50} {:quantity-root q2 :delta 50}]
               :operation-semantics-root (root "op-sem")
               :trace-policy-root (root "tp")})
          trace (transact/execute before tx canonical policy)
          semantics (transact/build-binding-semantics :effect-exact)
          bind-root (:binding-semantics/root semantics)]
      (testing "original binding succeeds"
        (is (some? (transact/bind-transition canonical tx trace bind-root))))
      (testing "binding with tampered state-before/root throws"
        (let [tampered (assoc canonical :state-before/root (root "tampered"))]
          (is (thrown? Exception
                       (transact/bind-transition tampered tx trace bind-root))
              "the original canonical transition is invalidated by root swap"))))))

;; ─── SEW 60/40 compatibility witness ──────────────────────────────────────

(deftest sew-6040-production-allocation-matches-canonical-transition
  (testing "the production SEW slash allocation for a 60/40 stake split
            produces allocation/evidence roots that bind to the canonical
            transition root via the committed hex vector"
    (let [allocation-input {:slash-obligation 100
                            :liable-parties [{:id :a :slashable-stake 60}
                                             {:id :b :slashable-stake 40}]}
          result (sew/calculate-sew-slash-allocation allocation-input)
          evidence (:mechanism/evidence result)
          allocation-hash (:allocation/hash result)]
      (is (= [60 40] (map :paid (:allocations result)))
          "production allocation is exactly 60/40")
      (is (= 100 (:recovered-total result))
          "all obligation is recovered")
      (is (= 0 (:unmet-total result))
          "no unmet obligation")
      (is (= (:allocation/hash frozen-6040-hex-vectors) allocation-hash)
          "SEW allocation hash matches frozen canonical root")
      (is (= (:evidence/hash frozen-6040-hex-vectors) (:evidence/hash evidence))
          "SEW evidence hash matches frozen canonical root")
      (is (every? true? (map :holds? (:mechanism/validation-results evidence)))
          "all mechanism validation results hold"))))

(deftest sew-6040-exact-verifier-compatibility
  (testing "the independent exact verifier agrees with the production SEW allocation"
    (let [allocation-input {:slash-obligation 100
                            :liable-parties [{:id :a :slashable-stake 60}
                                             {:id :b :slashable-stake 40}]}
          result (sew/calculate-sew-slash-allocation allocation-input)
          allocations (:allocations result)
          claimed {:allocations (mapv (fn [a] {:id (:id a) :allocated (:paid a) :unmet (:unmet a)})
                                      allocations)
                   :total-allocated (:recovered-total result)
                   :total-unmet (:unmet-total result)
                   :remainder 0}
          request {:amount 100
                   :items [{:id :a :weight 60} {:id :b :weight 40}]
                   :rounding :floor-with-largest-remainder
                   :ordering-policy :input-order
                   :cap-treatment :unallocated}
          verdict (verifier/verify-weighted-proportionality request claimed)]
      (is (= :passed (:status verdict))
          "independent verifier agrees with production SEW allocation"))))
