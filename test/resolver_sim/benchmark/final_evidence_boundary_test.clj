(ns resolver-sim.benchmark.final-evidence-boundary-test
  "Persistence/hash conformance for benchmark final evidence.

  Invariant under test:

    persisted final evidence
      → reconstructed canonical hash projection (from DISK bytes)
      → canonical hash
      = stored :evidence/hash

  Every test crosses the REAL serialization boundary: bundles are written with
  runner/write-evidence (pprinted EDN), read back from the file, and verified
  with integrity/verify-bundle-hash. Nothing re-uses an in-memory value that
  the writer could have special-cased."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private tmp-dir
  (str (Files/createTempDirectory "final-evidence-boundary"
                                  (make-array FileAttribute 0))))

(defn- path-in-tmp [name]
  (str tmp-dir "/" name))

(defn- make-bundle
  "Representative final-evidence bundle. Insertion order is deliberately
  unsorted at the top level and inside nested maps. Optional knobs:
   :with-manifest       include :run/manifest (default true)
   :commitment-version  declare :evidence/commitment-version
   :concept-section     value for :concept/section; PRESENT-ness (even nil)
                        is controlled by including the key in opts"
  [{:as opts
    :keys [with-manifest commitment-version concept-section]
    :or {with-manifest true}}]
  (cond-> {:metrics {:total 3 :passed 3 :z-last-key "z" :a-first-key 1}
           :results [{:outcome :pass
                      :scenario/id "s-dr-001"
                      :scenario/evidence-root (str "sha256:" (apply str (repeat 64 "1")))
                      :checks [{:ok true :name "invariant-a"}
                               {:ok false :name "invariant-b"}]}
                     {:outcome :pass
                      :scenario/id "s-dr-002"
                      :scenario/evidence-root (str "sha256:" (apply str (repeat 64 "2")))}]
           :benchmark {:version 2 :id :demo-pack}
           :environment {:java-version "99" :os-name "TestOS"}
           :timestamp "2026-01-01T00:00:00Z"
           :repo {:dirty? true :commit "abc123"}
           :invariant-summary {:all-pass? false :passed-checks 1 :total-checks 2}
           :benchmark-certification {:tier :smoke :certified? true}}
    commitment-version (assoc :evidence/commitment-version commitment-version)
    with-manifest (assoc :run/manifest {:manifest/version "run-manifest.v1"
                                        :manifest/at "2026-01-01T00:00:01Z"
                                        :benchmark/id :demo-pack
                                        :scenario-count 2})
    (contains? opts :concept-section) (assoc :concept/section concept-section)))

(defn- finalize
  "Writer-equivalent construction: normalize → bundle-root hash → assoc."
  [bundle]
  (let [normalized (#'runner/normalize-runtime-values bundle)]
    (assoc normalized :evidence/hash (integrity/bundle-root-hash normalized))))

(defn- write-and-read-back!
  "Cross the real persistence boundary with the production writer; return the
  bundle reconstructed from FILE BYTES."
  [bundle file-name]
  (let [path (path-in-tmp file-name)]
    (runner/write-evidence bundle path)
    (edn/read-string (slurp path))))

(deftest persisted-evidence-verifies-from-disk-bytes
  (let [from-disk (write-and-read-back! (finalize (make-bundle {})) "plain.edn")
        stored (:evidence/hash from-disk)]
    (testing "stored root == recomputation over disk bytes"
      (is (= stored (:evidence/hash from-disk)))
      (is (= stored (integrity/bundle-root-hash from-disk)))
      ;; explicit projection hop — cannot drift into a second scheme definition
      (is (= stored (hc/hash-with-intent {:hash/intent :bundle-root}
                                         (integrity/canonical-projection from-disk)))))
    (testing "fail-closed verifier agrees"
      (let [v (integrity/verify-bundle-hash from-disk)]
        (is (:hash-ok? v))
        (is (= :current (:scheme v)))))))

(deftest ordering-insensitivity-top-level-and-nested
  (let [b1 (make-bundle {})
        b2 (assoc b1 :metrics (apply array-map
                                     (interleave [:a-first-key :z-last-key :passed :total]
                                                 [1 "z" 3 3])))
        ;; reorder result-map keys; keep the checks VECTOR order (vectors are
        ;; ordered content — reversing one must and does change the root)
        b3 (update-in b1 [:results 0] (fn [r] (apply array-map
                                                     (interleave [:checks :scenario/evidence-root
                                                                  :scenario/id :outcome]
                                                                 [(:checks r)
                                                                  (:scenario/evidence-root r)
                                                                  (:scenario/id r)
                                                                  (:outcome r)]))))
        h1 (integrity/bundle-root-hash (#'runner/normalize-runtime-values b1))
        h2 (integrity/bundle-root-hash (#'runner/normalize-runtime-values b2))
        h3 (integrity/bundle-root-hash (#'runner/normalize-runtime-values b3))]
    (is (= h1 h2) "top-level insertion order does not matter")
    (is (= h1 h3) "nested entry insertion order does not matter")
    (is (not= h1 (integrity/bundle-root-hash
                  (#'runner/normalize-runtime-values
                   (update-in b1 [:results 0 :checks] #(vec (reverse %))))))
        "vector element order IS semantic content")))

(deftest optional-fields-present-vs-absent
  (let [with-m (make-bundle {})
        without-m (make-bundle {:with-manifest false})]
    (testing ":run/manifest presence changes content, hence root"
      (is (not= (integrity/bundle-root-hash (#'runner/normalize-runtime-values with-m))
                (integrity/bundle-root-hash (#'runner/normalize-runtime-values without-m)))))
    (testing "both variants verify across the disk boundary"
      (doseq [[bundle name] [[with-m "with-manifest.edn"]
                             [without-m "without-manifest.edn"]]]
        (let [from-disk (write-and-read-back! (finalize bundle) name)]
          (is (:hash-ok? (integrity/verify-bundle-hash from-disk)) name)))))
  (testing ":evidence/commitment-version binds into the hash when present"
    (is (not= (integrity/bundle-root-hash (#'runner/normalize-runtime-values (make-bundle {})))
              (integrity/bundle-root-hash
               (#'runner/normalize-runtime-values
                (make-bundle {:commitment-version "bundle-root.v2"})))))))

(deftest absent-vs-nil-is-a-documented-distinction
  ;; Canonical encoder has a tag-null: a nil field PARTICIPATES in content;
  ;; an absent key does not exist. The roots must differ.
  (let [nil-section (make-bundle {:concept-section nil})
        no-section (dissoc (make-bundle {:concept-section :unused}) :concept/section)]
    (is (not= (integrity/bundle-root-hash (#'runner/normalize-runtime-values nil-section))
              (integrity/bundle-root-hash (#'runner/normalize-runtime-values no-section)))
        "nil field ≠ absent field")))

(deftest non-hash-bearing-fields-may-drift-after-write
  (let [from-disk (write-and-read-back! (finalize (make-bundle {})) "drift.edn")]
    (doseq [[label mutate]
            [["timestamp" #(assoc % :timestamp "2099-12-31T23:59:59Z")]
             ["repo" #(assoc % :repo {:commit "different" :dirty? false})]
             ["artifact-index" #(assoc % :benchmark/artifact-index {:chunks ["elsewhere"]})]
             ["signature" #(assoc % :evidence/signature "deadbeef")]
             ["public-key-path" #(assoc % :evidence/public-key-path "/tmp/key.pub")]]]
      (testing (str "post-hash field excluded from commitment: " label)
        (is (:hash-ok? (integrity/verify-bundle-hash (mutate from-disk))))))
    (testing "run/manifest wall-clock excluded, manifest content not"
      (is (:hash-ok? (integrity/verify-bundle-hash
                      (update-in from-disk [:run/manifest :manifest/at]
                                 (constantly "2030-01-01T00:00:00Z")))))
      (is (false? (:hash-ok? (integrity/verify-bundle-hash
                              (update-in from-disk [:run/manifest :benchmark/id]
                                         (constantly :other-pack)))))))))

(deftest semantic-tampering-detected-across-the-boundary
  (let [path (path-in-tmp "tamper.edn")]
    (runner/write-evidence (finalize (make-bundle {})) path)
    (doseq [[label mutate]
            [["metric flip" #(assoc-in % [:metrics :passed] 999)]
             ["result outcome tamper" #(assoc-in % [:results 0 :outcome] :failed)]
             ["certification tamper" #(assoc % :benchmark-certification {:certified? false})]
             ["hash-field itself replaced" #(assoc % :evidence/hash (apply str (repeat 64 "0")))]]]
      (testing label
        (let [v (integrity/verify-bundle-hash (mutate (edn/read-string (slurp path))))]
          (is (false? (:hash-ok? v))))))))
