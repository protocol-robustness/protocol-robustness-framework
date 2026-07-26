(ns resolver-sim.benchmark.outcome-manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.research-theorem-outcome :as rto]
            [resolver-sim.benchmark.research-conclusion :as rc]))

(def base-manifest
  {:benchmark/content-root "sha256:content"
   :benchmark/model-root "sha256:model"
   :benchmark/evaluation-policy-root "sha256:eval"
   :execution/status :completed
   :results/operational {:conservation :pass}})

(deftest build-manifest-minimal
  (let [manifest (om/build-manifest base-manifest)]
    (is (om/manifest-valid? manifest))
    (is (some? (:benchmark-outcome/hash manifest)))
    (is (= "sha256:model" (:benchmark/model-root manifest)))))

(deftest exact-replication-scope
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"))]
    (is (om/exact-replication-scope? a b))))

(deftest not-exact-replication-different-domains
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d1"
                  :execution/generated-case-set-root "sha256:c"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d2"
                  :execution/generated-case-set-root "sha256:c"))]
    (is (not (om/exact-replication-scope? a b)))))

(deftest sampling-comparison-scope
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c1"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c2"))]
    (is (om/sampling-comparison-scope? a b))))

(deftest sampling-comparison-rejects-same-cases
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c"))]
    (is (not (om/sampling-comparison-scope? a b)))))

(deftest related-model-scope
  (let [a (om/build-manifest (assoc base-manifest :benchmark/model-root "sha256:m"))
        b (om/build-manifest (assoc base-manifest :benchmark/model-root "sha256:m"
                                    :execution/parameter-domain-root "sha256:other"))]
    (is (om/related-model-scope? a b))))

(deftest not-related-model-scope
  (let [a (om/build-manifest (assoc base-manifest :benchmark/model-root "sha256:ma"))
        b (om/build-manifest (assoc base-manifest :benchmark/model-root "sha256:mb"))]
    (is (not (om/related-model-scope? a b)))))

(deftest compatible-outcomes-exact
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"))]
    (is (= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compatible-outcomes-sampling
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c1"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c2"))]
    (is (= :independent-sampling (om/classify-outcome-compatibility a b)))))

(deftest compatible-outcomes-model-corroboration
  (let [a (om/build-manifest
           (assoc base-manifest
                  :benchmark/model-root "sha256:m"
                  :execution/parameter-domain-root "sha256:d1"
                  :execution/realised-parameter-set-root "sha256:p1"
                  :execution/generated-case-set-root "sha256:c1"))
        b (om/build-manifest
           (assoc base-manifest
                  :benchmark/model-root "sha256:m"
                  :execution/parameter-domain-root "sha256:d2"
                  :execution/realised-parameter-set-root "sha256:p2"
                  :execution/generated-case-set-root "sha256:c2"))]
    (is (= :model-corroboration (om/classify-outcome-compatibility a b)))))

(deftest compatible-outcomes-incompatible
  (let [a (om/build-manifest
           (assoc base-manifest
                  :benchmark/content-root "sha256:ca"
                  :benchmark/model-root "sha256:ma"))
        b (om/build-manifest
           (assoc base-manifest
                  :benchmark/content-root "sha256:cb"
                  :benchmark/model-root "sha256:mb"))]
    (is (= :incompatible-scope (om/classify-outcome-compatibility a b)))))

(deftest compatibility-symmetric
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c1"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/generated-case-set-root "sha256:c2"))]
    (is (= (om/classify-outcome-compatibility a b) (om/classify-outcome-compatibility b a)))))

(deftest compatibility-symmetric-exact
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                   :execution/sampling-policy-root "sha256:s"
                   :execution/realised-parameter-set-root "sha256:p"
                   :execution/generated-case-set-root "sha256:c"))]
    (is (= (om/classify-outcome-compatibility a b) (om/classify-outcome-compatibility b a)))))

;; ── Hierarchical outcome manifest ─────────────────────────────────────────

(def ^:const sample-theorem-params
  {:theorem/id :theorem/quota-bounded
   :theorem/type :boundedness
   :theorem/statement
   {:if {:claim :partial-fill-calculated}
    :then {:claim :quota-bounded}}
   :theorem/scope {:model/root "sha256:model"}
   :theorem/conclusion {:status :established :claim-id :claim/quota-bounded}})

(def ^:const sample-conclusion-params
  {:conclusion/id :conclusion/partial-fill-correctness
   :conclusion/premise {:x "Allocations bounded by quota, state written back."}
   :conclusion/result {:y "Partial-fill preserves authoritative state."}})

(deftest build-manifest-with-hierarchical-outcomes
  (let [t1 (rto/build-theorem-outcome sample-theorem-params)
        t2 (rto/build-theorem-outcome
            (assoc sample-theorem-params
                   :theorem/id :theorem/current-amount-continuity
                   :theorem/type :state-transition))
        c1 (rc/build-conclusion sample-conclusion-params)
        manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/command-root "sha256:cmd"
                         :outcomes/operational-root "sha256:oper"
                         :outcomes/incentive-root "sha256:inc"
                         :outcomes/incentive-compatibility-root "sha256:ic"
                         :outcomes/theorems
                         [{:theorem/id :theorem/quota-bounded
                           :theorem/hash (:theorem/hash t1)
                           :status :established}
                          {:theorem/id :theorem/current-amount-continuity
                           :theorem/hash (:theorem/hash t2)
                           :status :established}]
                         :outcomes/conclusions
                         [{:conclusion/id :conclusion/partial-fill-correctness
                           :conclusion/hash (:conclusion/hash c1)}]))]
    (is (om/manifest-valid? manifest))
    (is (some? (:benchmark-outcome/hash manifest)))
    (is (= "sha256:cmd" (:execution/command-root manifest)))
    (is (contains? manifest :outcomes/operational-root))
    (is (contains? manifest :outcomes/incentive-root))
    (is (contains? manifest :outcomes/incentive-compatibility-root))
    (is (= 2 (count (:outcomes/theorems manifest))))
    (is (= 1 (count (:outcomes/conclusions manifest))))
    (is (contains? manifest :outcome-hashes))
    (is (some? (get-in manifest [:outcome-hashes :theorem-root])))
    (is (some? (get-in manifest [:outcome-hashes :conclusion-root])))))

(deftest build-manifest-without-hierarchical-is-backward-compatible
  (let [manifest (om/build-manifest base-manifest)]
    (is (om/manifest-valid? manifest))
    (is (not (contains? manifest :outcomes/operational-root)))
    (is (not (contains? manifest :outcome-hashes)))
    (is (not (contains? manifest :outcomes/theorems)))))

(deftest hierarchical-manifest-compares-with-exact-replication
  (let [t1 (rto/build-theorem-outcome sample-theorem-params)
        a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"
                  :outcomes/theorems
                  [{:theorem/id :theorem/quota-bounded
                    :theorem/hash (:theorem/hash t1)
                    :status :established}]))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root "sha256:d"
                  :execution/sampling-policy-root "sha256:s"
                  :execution/realised-parameter-set-root "sha256:p"
                  :execution/generated-case-set-root "sha256:c"
                  :outcomes/theorems
                  [{:theorem/id :theorem/quota-bounded
                    :theorem/hash (:theorem/hash t1)
                    :status :established}]))]
    (is (om/exact-replication-scope? a b))
    (is (om/compatible-outcomes? a b))))
