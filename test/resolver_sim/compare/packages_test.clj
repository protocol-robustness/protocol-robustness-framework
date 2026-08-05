(ns resolver-sim.compare.packages-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.compare.packages :as packages]
            [resolver-sim.compare.run-fixture :as fixture]))

(defn- with-run-root
  "Build one run root with opts, call f on it, and delete it afterwards."
  [opts f]
  (let [root (apply fixture/build-run-root! opts)]
    (try
      (f root)
      (finally (fixture/delete-tree! root)))))

(defn- with-two-roots
  "Build two run roots with distinct run-ids, call f on both, and delete them."
  [opts-a opts-b f]
  (let [root-a (apply fixture/build-run-root! (concat opts-a [:run-id "run-a"]))
        root-b (apply fixture/build-run-root! (concat opts-b [:run-id "run-b"]))]
    (try
      (f root-a root-b)
      (finally
        (fixture/delete-tree! root-a)
        (fixture/delete-tree! root-b)))))

(deftest package-roots-report-structural-roots
  (with-run-root
    [:bundle-root-hash "bundle-root"]
    (fn [root]
      (let [report (packages/package-roots root)]
        (is (:valid? report))
        (is (= "run-id" (:run-id report)))
        (is (= :single-scenario (:run-type report)))
        (is (= 64 (count (:package-index-hash report))))
        (is (= "bundle-root" (:bundle-root-hash report)))
        (is (= "sha256:" (subs (:sha256 (:completion-seal report)) 0 7)))
        (is (pos? (:bytes (:completion-seal report))))))))

(deftest package-roots-invalid-without-index
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "prf-empty" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [report (packages/package-roots root)]
        (is (not (:valid? report)))
        (is (some? (:reason report))))
      (finally (fixture/delete-tree! root)))))

(deftest result-roots-report-stable-hash-and-evidence-roots
  (with-run-root
    [:scenario-results [(fixture/scenario-result)]]
    (fn [root]
      (let [report (packages/result-roots root)]
        (is (:valid? report))
        (is (= 64 (count (:stable-result-hash report))))
        (is (= :stable-projection-v0 (:stable-comparison-policy report)))
        (is (= 1 (:scenario-count report)))
        (is (= ["sha256:aaaa"] (:scenario-evidence-roots report)))))))

(deftest declared-dependencies-report-package-surface
  (with-run-root
    []
    (fn [root]
      (let [report (packages/declared-dependencies root)]
        (is (:valid? report))
        (is (= [:input-snapshot :run-finalization] (:package/artifacts report)))
        (is (= ["evidence/finalizations/run/evidence-finalization.json"
                "input/input.edn"]
               (:package/artifact-paths report)))
        (is (= ["sha256:aaa" "sha256:bbb"] (:evidence/declared-evidence-hashes report)))
        (is (= ["sha256:2222"] (:evidence/scenario-finalization-hashes report)))
        (is (= ["scenario-1"] (:evidence/scenario-ids report)))))))

(deftest semantic-equivalent-true-for-identical-results
  (with-two-roots
    [:scenario-results [(fixture/scenario-result)] :semantic-outcome "pass"]
    [:scenario-results [(fixture/scenario-result)] :semantic-outcome "pass"]
    (fn [root-a root-b]
      (let [report (packages/semantic-equivalent root-a root-b)]
        (is (:stable-equivalent? report))
        (is (:outcome-equivalent? report))
        (is (:equivalent? report))))))

(deftest semantic-equivalent-false-for-different-results
  (with-two-roots
    [:scenario-results [(fixture/scenario-result)]]
    [:scenario-results [(fixture/scenario-result :outcome "fail" :invariant-status "fail"
                                                 :evidence-root "sha256:bbbb")]]
    (fn [root-a root-b]
      (let [report (packages/semantic-equivalent root-a root-b)]
        (is (not (:stable-equivalent? report)))
        (is (not (:equivalent? report)))))))

(deftest semantic-equivalent-false-when-no-results
  (with-two-roots
    []
    []
    (fn [root-a root-b]
      (is (not (:equivalent? (packages/semantic-equivalent root-a root-b)))))))
