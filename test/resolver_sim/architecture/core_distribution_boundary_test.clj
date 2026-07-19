(ns resolver-sim.architecture.core-distribution-boundary-test
  "Static boundary guard for namespaces that form the protocol-neutral PRF
   distribution surface. Protocol implementations may live elsewhere, but this
   surface must remain loadable without protocols_src or Sew resources."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def core-distribution-roots
  ["src/resolver_sim/cli"
   "src/resolver_sim/core"
   "src/resolver_sim/evidence"
   "src/resolver_sim/reference"
   "src/resolver_sim/run"])

(def core-distribution-files
  ["src/resolver_sim/sim/reference_validation.clj"
   "src/resolver_sim/sim/reference_validation_evidence.clj"])

(defn- clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(deftest core-distribution-namespaces-do-not-import-sew
  (testing "the protocol-neutral core surface has no direct Sew namespace dependency"
    (let [offenders (->> (concat (mapcat clojure-sources core-distribution-roots)
                                 (map io/file core-distribution-files))
                         (filter #(re-find #"\[resolver-sim\.protocols\.sew" (slurp %)))
                         (map #(.getPath %))
                         sort
                         vec)]
      (is (empty? offenders)
          (str "Core distribution namespace(s) import Sew: " (str/join ", " offenders))))))
