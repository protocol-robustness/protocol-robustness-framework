(ns resolver-sim.commands.package-inspection-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.commands.declared-dependencies :as declared-deps]
            [resolver-sim.commands.result-root :as result-root]
            [resolver-sim.commands.root-hash :as root-hash]
            [resolver-sim.commands.semantic-equivalent :as semantic-equiv]
            [resolver-sim.compare.run-fixture :as fixture]))

(deftest root-hash-reports-package-roots
  (let [root (fixture/build-run-root! :bundle-root-hash "bundle-x")]
    (try
      (let [out (with-out-str
                  (let [{:keys [exit-code]} (root-hash/run {:run-root root})]
                    (is (zero? exit-code))))]
        (is (re-find #"bundle root:     bundle-x" out))
        (is (re-find #"package index:" out))
        (is (re-find #"completion seal:" out)))
      (finally (fixture/delete-tree! root)))))

(deftest root-hash-missing-run-root-usage
  (let [{:keys [exit-code]} (root-hash/run {})]
    (is (= 2 exit-code))))

(deftest result-root-reports-result-roots
  (let [root (fixture/build-run-root!
              :scenario-results [(fixture/scenario-result)]
              :bundle-root-hash "bundle-y")]
    (try
      (let [out (with-out-str
                  (let [{:keys [exit-code]} (result-root/run {:run-root root})]
                    (is (zero? exit-code))))]
        (is (re-find #"bundle root:           bundle-y" out))
        (is (re-find #"stable result hash:" out))
        (is (re-find #"sha256:aaaa" out)))
      (finally (fixture/delete-tree! root)))))

(deftest declared-dependencies-reports-surface
  (let [root (fixture/build-run-root!)]
    (try
      (let [out (with-out-str
                  (let [{:keys [exit-code]} (declared-deps/run {:run-root root})]
                    (is (zero? exit-code))))]
        (is (re-find #"declared evidence hashes:" out))
        (is (re-find #"sha256:aaa" out))
        (is (re-find #"scenario finalization hashes:" out)))
      (finally (fixture/delete-tree! root)))))

(deftest semantic-equivalent-command-exit-codes
  (let [a (fixture/build-run-root!
           :scenario-results [(fixture/scenario-result)] :semantic-outcome "pass")
        b-same (fixture/build-run-root!
                :scenario-results [(fixture/scenario-result)] :semantic-outcome "pass")
        b-diff (fixture/build-run-root!
                :scenario-results [(fixture/scenario-result :outcome "fail" :evidence-root "sha256:bbbb")]
                :semantic-outcome "pass")]
    (try
      (let [{:keys [exit-code]} (semantic-equiv/run {:package-a a :package-b b-same})]
        (is (zero? exit-code)))
      (let [{:keys [exit-code]} (semantic-equiv/run {:package-a a :package-b b-diff})]
        (is (= 1 exit-code)))
      (finally
        (fixture/delete-tree! a)
        (fixture/delete-tree! b-same)
        (fixture/delete-tree! b-diff)))))

(deftest semantic-equivalent-missing-args-usage
  (let [{:keys [exit-code]} (semantic-equiv/run {})]
    (is (= 2 exit-code))))
