(ns resolver-sim.benchmark.distributed.executable-distribution-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.distributed.executable-distribution :as sut]
            [resolver-sim.benchmark.distributed.execution-binding :as binding]))

(defn- root [digit] (str "sha256:" (apply str (repeat 64 digit))))

(def base-options
  {:execution/claimant-parallelism 2
   :execution/claimant-parallel-threshold 3
   :execution/quiescence-timeout-seconds 10})

(def distribution
  (sut/build-distribution {:execution-plan/root (root "a")
                           :executable-artifact/root (root "b")
                           :semantic-claimant-options base-options}))

(deftest distribution-is-independent-of-plan-and-operational-placement
  (is (sut/verify-distribution distribution))
  (is (= (:executable-distribution/root distribution)
         (:executable-distribution/root
          (sut/build-distribution {:execution-plan/root (root "different")
                                   :executable-artifact/root (root "b")
                                   :semantic-claimant-options base-options
                                   :local-key/id "worker-b"
                                   :local-key/capacity 9
                                   :local-key/budget 7}))))
  (is (not= (:executable-distribution/root distribution)
            (:executable-distribution/root
             (sut/build-distribution {:execution-plan/root (root "a")
                                      :executable-artifact/root (root "c")
                                      :semantic-claimant-options base-options}))))
  (is (= (:executable-distribution/root distribution)
         (:executable-distribution/root
          (sut/build-distribution {:executable-artifact/root (root "b")
                                   :semantic-claimant-options
                                   (assoc base-options
                                          :execution/claimant-parallelism 4)})))
      "runtime claimant capacity must not change executable identity"))

(deftest semantic-options-defaults-normalize-and-unknown-options-reject
  (let [defaults (sut/build-distribution {:executable-artifact/root (root "b")})
        explicit (sut/build-distribution
                  {:executable-artifact/root (root "b")
                   :semantic-claimant-options
                   {:execution/claimant-parallelism 1
                    :execution/claimant-parallel-threshold 16}})]
    (is (= (:semantic-claimant-options defaults)
           (:semantic-claimant-options explicit)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-distribution
                  {:executable-artifact/root (root "b")
                   :semantic-claimant-options (assoc base-options :unknown 1)})))))

(deftest execution-binding-is-thin-and-rooted
  (let [a (binding/build-binding {:execution-plan/root (root "a")
                                  :executable-distribution/root (:executable-distribution/root distribution)})
        b (binding/build-binding {:execution-plan/root (root "b")
                                  :executable-distribution/root (:executable-distribution/root distribution)})]
    (is (= binding/schema (:execution-binding/schema a)))
    (is (binding/verify-binding a))
    (is (not= (:execution-binding/root a) (:execution-binding/root b)))
    (is (= (:executable-distribution/root a)
           (:executable-distribution/root b)))))

(deftest distribution-preflight-uses-distribution-terminology
  (is (= :verified
         (:distribution-preflight/status
          (sut/distribution-preflight
           {:expected (sut/expected-shape distribution)
            :observed distribution}))))
  (testing "unresolved observations fail closed"
    (is (= :unresolved
           (:distribution-preflight/status
            (sut/distribution-preflight {:expected nil :observed distribution})))))
  (is (= :mismatched
         (:distribution-preflight/status
          (sut/distribution-preflight
           {:expected (sut/expected-shape distribution)
            :observed (assoc distribution :executable-artifact/root (root "c"))})))))
