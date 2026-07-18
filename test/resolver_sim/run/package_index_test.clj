(ns resolver-sim.run.package-index-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
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
