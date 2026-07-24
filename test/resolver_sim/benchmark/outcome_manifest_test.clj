(ns resolver-sim.benchmark.outcome-manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.outcome-manifest :as om]))

(def base-manifest
  {:benchmark/content-root "sha256:content"
   :benchmark/model-root "sha256:model"
   :benchmark/evaluation-policy-root "sha256:eval"
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
