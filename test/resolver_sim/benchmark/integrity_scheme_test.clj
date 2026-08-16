(ns resolver-sim.benchmark.integrity-scheme-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.hash.canonical :as hc]))

(defn- hashable-for
  ([e] (integrity/hashable-evidence e))
  ([e scheme]
   (let [h (integrity/hashable-evidence e)]
     (if (= scheme :legacy-v1)
       (dissoc h :run/manifest :benchmark-certification)
       h))))

(defn- commit-as
  "Mirror the writer's projection using the SAME projection the verifier uses,
  so this round-trips :evidence/hash faithfully."
  [scheme e]
  (assoc e :evidence/hash
         (hc/hash-with-intent {:hash/intent :bundle-root}
                              (into (sorted-map) (hashable-for e scheme)))))

(deftest current-scheme-verifies-when-version-declared-v2
  (testing "a bundle declaring bundle-root.v2 verifies as :current"
    (let [committed (commit-as :current
                               {:benchmark {:benchmark/id :b}
                                :metrics {:total 1 :passed 1}
                                :evidence/commitment-version "bundle-root.v2"})
          r (integrity/verify-bundle-hash committed)]
      (is (true? (:hash-ok? r)))
      (is (= :current (:scheme r))))))

(deftest versionless-current-defaults-to-current
  (testing "a version-less current bundle verifies as :current (no fallback)"
    (let [committed (commit-as :current {:benchmark {:benchmark/id :b}
                                         :metrics {:total 1 :passed 1}})
          r (integrity/verify-bundle-hash committed)]
      (is (true? (:hash-ok? r)))
      (is (= :current (:scheme r))))))

(deftest legacy-v1-declared-only-verifies-as-legacy-v1
  (testing "a bundle declaring bundle-root.v1 verifies with the legacy scheme only"
    (let [committed (commit-as :legacy-v1
                               {:benchmark {:benchmark/id :b}
                                :metrics {:total 1 :passed 1}
                                :run/manifest {:manifest/version "run-manifest.v1"}
                                :benchmark-certification {:certification-hash "x"}
                                :evidence/commitment-version "bundle-root.v1"})
          r (integrity/verify-bundle-hash committed)]
      (is (true? (:hash-ok? r)))
      (is (= :legacy-v1 (:scheme r))))))

(deftest versionless-legacy-bundle-is-rejected
  (testing "a version-less legacy-hash bundle is rejected (default current fails; no fallback)"
    (let [committed (commit-as :legacy-v1
                               {:benchmark {:benchmark/id :b}
                                :metrics {:total 1 :passed 1}
                                :run/manifest {:manifest/version "run-manifest.v1"}
                                :benchmark-certification {:certification-hash "x"}})
          r (integrity/verify-bundle-hash committed)]
      (is (false? (:hash-ok? r))))))

(deftest declared-version-does-not-mask-the-other-scheme
  (testing "declaring bundle-root.v1 on a current-hash bundle is rejected, not re-verified as current"
    (let [committed (-> (commit-as :current
                                   {:benchmark {:benchmark/id :b}
                                    :metrics {:total 1 :passed 1}
                                    :run/manifest {:manifest/version "run-manifest.v1"}
                                    :benchmark-certification {:certification-hash "x"}})
                        (assoc :evidence/commitment-version "bundle-root.v1"))
          r (integrity/verify-bundle-hash committed)]
      (is (false? (:hash-ok? r)))
      (is (= :computed-hash-mismatch (:reason r))))))

(deftest unsupported-commitment-version-fails-closed
  (testing "an unknown commitment version fails closed"
    (let [committed (commit-as :current
                               {:benchmark {:benchmark/id :b}
                                :metrics {:total 1 :passed 1}
                                :evidence/commitment-version "bundle-root.v9"})
          r (integrity/verify-bundle-hash committed)]
      (is (false? (:hash-ok? r)))
      (is (= :unsupported-commitment-version (:reason r)))
      (is (nil? (:scheme r))))))

(deftest tampered-metrics-rejected
  (testing "mutating an aggregate value breaks the committed hash"
    (let [committed (commit-as :current {:benchmark {:benchmark/id :b}
                                         :metrics {:total 1 :passed 1}})
          r (integrity/verify-bundle-hash (assoc-in committed [:metrics :passed] 0))]
      (is (false? (:hash-ok? r))))))

(deftest verify-evidence-bundle!-contract
  (testing "fail-closed gate returns the bundle only when it verifies"
    (let [committed (commit-as :current {:benchmark {:benchmark/id :b}
                                         :metrics {:total 1 :passed 1}})]
      (is (identical? committed (integrity/verify-evidence-bundle! committed)))
      (is (thrown-with-msg? Exception #"integrity"
                            (integrity/verify-evidence-bundle!
                             (assoc-in committed [:metrics :passed] 0))))
      (is (thrown-with-msg? Exception #"integrity"
                            (integrity/verify-evidence-bundle!
                             (assoc committed :evidence/hash "garbage")))))))

(deftest commitment-version-is-bound-into-hash
  (testing ":evidence/commitment-version is committed into the hash (scheme is bound)"
    (let [base {:benchmark {:benchmark/id :b} :metrics {:total 1 :passed 1}}
          with-v (commit-as :current (assoc base :evidence/commitment-version "bundle-root.v2"))
          without-v (commit-as :current base)]
      (is (not= (:evidence/hash with-v) (:evidence/hash without-v))
          "a v2 label must alter the bundle-root hash"))))

(deftest stripping-bound-version-invalidates-commitment
  (testing "removing the bound version from a v2-committed bundle fails verification"
    (let [committed (commit-as :current
                               {:benchmark {:benchmark/id :b}
                                :metrics {:total 1 :passed 1}
                                :evidence/commitment-version "bundle-root.v2"})
          stripped (dissoc committed :evidence/commitment-version)
          r (integrity/verify-bundle-hash stripped)]
      (is (false? (:hash-ok? r))))))

(deftest version-tag-cannot-be-repurposed
  (testing "a v2-committed hash is not reinterpretable under a v1 tag"
    (let [committed (-> (commit-as :current
                                   {:benchmark {:benchmark/id :b}
                                    :metrics {:total 1 :passed 1}
                                    :run/manifest {:manifest/version "run-manifest.v1"}
                                    :benchmark-certification {:certification-hash "x"}
                                    :evidence/commitment-version "bundle-root.v2"})
                        (dissoc :evidence/commitment-version)
                        (assoc :evidence/commitment-version "bundle-root.v1"))
          r (integrity/verify-bundle-hash committed)]
      (is (false? (:hash-ok? r))))))
