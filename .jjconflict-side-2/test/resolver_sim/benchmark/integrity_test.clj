(ns resolver-sim.benchmark.integrity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.hash.canonical :as hc]))

(defn- temp-path
  [prefix]
  (str (doto (java.io.File/createTempFile prefix ".edn") .deleteOnExit)))

(defn- committed-bundle
  "Mirror the runner's commitment: hash the normalized + hashable representation
   exactly as run-benchmark does, so the persisted artifact recomputes."
  [evidence]
  (assoc evidence :evidence/hash
         (hc/hash-with-intent {:hash/intent :bundle-root}
                              (integrity/hashable-evidence
                               (runner/normalize-runtime-values
                                (dissoc evidence :timestamp))))))

(deftest runner-committed-hash-recomputes-from-persisted-bundle
  (testing "a bundle whose hash covers the normalized representation round-trips
            through write-evidence and verifies, even with runtime Instants"
    (let [evidence {:benchmark {:benchmark/id :benchmark/test}
                    :results [{:scenario/id "s1"
                               :outcome :pass
                               :halt-reason nil
                               :ts (java.time.Instant/parse "2026-01-01T00:00:00Z")}]
                    :metrics {:total 1 :passed 1}}
          committed (committed-bundle evidence)
          path (temp-path "bundle-")]
      (runner/write-evidence committed path)
      (let [reloaded (integrity/read-evidence-bundle path)
            result (:hash-ok? (integrity/verify-bundle-hash reloaded))]
        (is (= true result) "committed bundle-root hash must recompute from the file")
        (is (string? (get-in reloaded [:results 0 :ts]))
            "runtime Instant must be persisted as a portable ISO-8601 string, not #object[...]")
        (is (not-any? integrity/legacy-object? (tree-seq coll? seq reloaded))
            "persisted bundle must contain no legacy runtime-object sentinels")))))

(deftest verify-bundle-hash-rejects-tampered-metrics
  (testing "mutating a supplied aggregate value breaks the committed commitment"
    (let [committed (committed-bundle {:benchmark {:benchmark/id :benchmark/test}
                                       :metrics {:total 1 :passed 1}})
          tampered (assoc-in committed [:metrics :passed] 0)]
      (is (= true (:hash-ok? (integrity/verify-bundle-hash committed))))
      (is (= false (:hash-ok? (integrity/verify-bundle-hash tampered)))))))

(deftest verify-bundle-hash-verifies-current-scheme
  (testing "a bundle committed over the full current-scheme content verifies as :current"
    (let [committed (committed-bundle {:benchmark {:benchmark/id :benchmark/test}
                                       :metrics {:total 1 :passed 1}
                                       :run/manifest {:manifest/version "run-manifest.v1"}
                                       :benchmark-certification {:certification-hash "x"}})]
      (is (= :current (:scheme (integrity/verify-bundle-hash committed)))))))

(deftest verify-evidence-bundle!-throws-on-missing-hash
  (testing "a bundle with no committed :evidence/hash cannot pass the fail-closed gate"
    (is (thrown-with-msg? Exception #"integrity"
                          (integrity/verify-evidence-bundle!
                           {:benchmark {:benchmark/id :benchmark/test}
                            :metrics {:total 1 :passed 1}})))))

(deftest verify-evidence-bundle!-throws-on-tampered-bundle
  (testing "a bundle whose committed hash does not recompute cannot pass the fail-closed gate"
    (let [committed (committed-bundle {:benchmark {:benchmark/id :benchmark/test}
                                       :metrics {:total 1 :passed 1}})
          tampered (assoc-in committed [:metrics :passed] 0)]
      (is (thrown-with-msg? Exception #"integrity"
                            (integrity/verify-evidence-bundle! tampered))))))

(deftest tolerant-reader-detects-legacy-objects
  (testing "legacy #object[...] tagged literals are read as inert legacy sentinels"
    (let [path (temp-path "legacy-")]
      (spit path "{:module #object[java.time.Instant \"2026-01-01T00:00:00Z\"]}")
      (let [m (integrity/read-evidence-bundle path)]
        (is (integrity/legacy-object? (:module m)))))))

(deftest scanner-findings-do-not-alter-bundle-root
  (testing "findings are not part of the canonical evidence object"
    (let [base-evidence {:benchmark {:benchmark/id :benchmark/test}
                         :metrics {:total 1 :passed 1}
                         :claim-results []}
          base-hash (:evidence/hash (committed-bundle base-evidence))
          base-projection (integrity/hashable-evidence base-evidence)]
      (is (not (contains? base-projection :findings))
          "canonical evidence must not carry a :findings field")
      (let [tampered (assoc base-evidence
                            :findings
                            [{:type :scanner/sensitivity
                              :severity :high
                              :message "test finding"}])
            tampered-hash (:evidence/hash (committed-bundle tampered))]
        (is (not= base-hash tampered-hash)
            "adding :findings to evidence silently changes the bundle-root hash, confirming the boundary: findings must never enter the evidence object")))))

(deftest version-strict-commitment-has-no-fallback
  (testing "an unknown commitment version fails closed"
    (let [evidence (assoc (committed-bundle {:benchmark {:benchmark/id :benchmark/test}
                                             :metrics {:total 1 :passed 1}})
                          :evidence/commitment-version "bundle-root.v3")
          result (integrity/verify-bundle-hash evidence)]
      (is (= false (:hash-ok? result)))
      (is (= :unsupported-commitment-version (:reason result))))))

(deftest creation-provenance-excluded-from-bundle-root
  (testing "changing :creation/provenance does not alter the bundle-root hash"
    (let [base-evidence {:benchmark {:benchmark/id :benchmark/test}
                         :metrics {:total 1 :passed 1}}
          in-band-hash (get-in (committed-bundle (assoc base-evidence :creation/provenance :in-band))
                               [:evidence/hash])
          out-of-band-hash (get-in (committed-bundle (assoc base-evidence :creation/provenance :out-of-band))
                                   [:evidence/hash])]
      (is (= in-band-hash out-of-band-hash)
          "creation provenance must not contaminate semantic bundle root identity")))
  (testing "hashable-evidence projection drops :creation/provenance"
    (let [evidence {:benchmark {:benchmark/id :benchmark/test}
                    :creation/provenance :out-of-band}
          projection (integrity/hashable-evidence evidence)]
      (is (not (contains? projection :creation/provenance))
          ":creation/provenance must be absent from hashable evidence"))))
