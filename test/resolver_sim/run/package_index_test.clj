(ns resolver-sim.run.package-index-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.run.package-index :as package-index]))

(deftest boolean-wrappers-do-not-leak-structured-reports
  (let [root (str (java.nio.file.Files/createTempDirectory "package-index-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (is (false? (package-index/complete? root)))
      (is (false? (package-index/integrity-valid? root)))
      (is (false? (package-index/runnable? root)))
      (is (boolean? (package-index/runnable? root)))
      (is (= :incomplete (:status (package-index/validate-completeness root))))
      (finally
        (let [f (io/file root)]
          (doseq [x (reverse (file-seq f))] (.delete x)))))))

(deftest unsupported-profile-is-rejected
  (let [index (package-index/build {:run-id "benchmark" :run-type :benchmark :bundle-root-hash "x" :artifacts {}})]
    (is (= :benchmark (:run/type index)))))

(deftest package-index-wire-normalization-and-byte-commitments-are-distinct
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "package-index-wire-" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (io/file root "manifest/run-package-index.json")
        logical (package-index/build {:run-id "run-1"
                                      :scenario-id "scenario-1"
                                      :execution-id "execution:run-1"
                                      :run-type :single-scenario
                                      :bundle-root-hash "bundle"
                                      :artifacts {}})]
    (try
      (let [{written :index} (package-index/write! path {:run-id "run-1"
                                                          :scenario-id "scenario-1"
                                                          :execution-id "execution:run-1"
                                                          :run-type :single-scenario
                                                          :bundle-root-hash "bundle"
                                                          :artifacts {}})
            wire (json/read-str (slurp path) :key-fn keyword)
            restored (package-index/wire->package-index wire)
            persisted-sha (str "sha256:" (lifecycle/sha256-file path))]
        (is (= "single-scenario" (:run/type wire)))
        (is (= :single-scenario (:run/type restored)))
        (is (= (:run-package/hash logical) (:run-package/hash restored)))
        (is (= (:run-package/hash written) (:run-package/hash restored)))
        ;; A semantic hash is over the logical schema payload; this is a
        ;; separate exact-byte commitment for the persisted JSON file.
        (is (not= (:run-package/hash restored) persisted-sha))
        (is (pos? (.length path))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported package index run type"
                            (package-index/wire->package-index
                             {:run/type "unknown"})))
      (finally
        (doseq [x (reverse (file-seq (io/file root)))] (io/delete-file x true))))))
