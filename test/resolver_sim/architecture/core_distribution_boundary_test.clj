(ns resolver-sim.architecture.core-distribution-boundary-test
  "Static boundary guard for namespaces that form the protocol-neutral PRF
   distribution surface. Protocol implementations may live elsewhere, but this
   surface must remain loadable without protocols_src or Sew resources."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def boundary-policy
  (edn/read-string (slurp "config/architecture/protocol-boundaries.edn")))

(def core-distribution-roots (:core/source-roots boundary-policy))
(def core-distribution-files (:core/source-files boundary-policy))
(def forbidden-prefixes (:forbidden/core-namespace-prefixes boundary-policy))

(defn- clojure-sources [root]
  (for [file (file-seq (io/file root))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
    file))

(defn- imports-forbidden-namespace? [file]
  (let [source (slurp file)]
    (some (fn [prefix]
            (re-find (re-pattern (str "\\[" (java.util.regex.Pattern/quote prefix))) source))
          forbidden-prefixes)))

(deftest core-distribution-namespaces-do-not-import-sew
  (testing "the protocol-neutral core surface has no direct Sew namespace dependency"
    (let [offenders (->> (concat (mapcat clojure-sources core-distribution-roots)
                                 (map io/file core-distribution-files))
                         (filter imports-forbidden-namespace?)
                         (map #(.getPath %))
                         sort
                         vec)]
      (is (empty? offenders)
          (str "Core distribution namespace(s) import Sew: " (str/join ", " offenders))))))
