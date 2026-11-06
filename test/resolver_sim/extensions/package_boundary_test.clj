(ns resolver-sim.extensions.package-boundary-test
  "Keep package runtime imports and project paths behind the versioned core API."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def ^:private packages
  [{:id :force-authorisation
    :root "extensions/force-authorisation"
    :source "extensions/force-authorisation/src"}
   {:id :held-custody
    :root "extensions/held-custody"
    :source "extensions/held-custody/src"}])

(def ^:private public-core-api
  #{'resolver-sim.extensions.api.v1})

(def ^:private api-implementation-dependencies
  #{'resolver-sim.assurance.force-authorisation
    'resolver-sim.assurance.held-admission
    'resolver-sim.configuration-head
    'resolver-sim.evidence.artifact
    'resolver-sim.hash.canonical
    'resolver-sim.hash.reference
    'resolver-sim.hash.sequence
    'resolver-sim.sensitivity.sentinel})

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(= ".clj" (subs (.getName %) (max 0 (- (count (.getName %)) 4)))))))

(defn- resolver-sim-requires [source]
  (->> (re-seq #"\[\s*(resolver-sim(?:\.[A-Za-z0-9_-]+)+)" source)
       (map (comp symbol second))
       set))

(deftest package-runtime-depends-only-on-the-versioned-core-api
  (doseq [{:keys [id source]} packages
          file (source-files source)]
    (let [contents (slurp file)
          requires (resolver-sim-requires contents)]
      (is (or (empty? requires) (= public-core-api requires))
          (str id " runtime source " (.getPath file)
               " may import only " public-core-api "; found " requires)))))

(deftest public-core-api-is-the-only-package-facing-core-namespace
  (let [requires (resolver-sim-requires
                  (slurp "src/resolver_sim/extensions/api/v1.clj"))]
    (is (= api-implementation-dependencies requires)
        "Review any change to the internal dependency closure of API v1")))

(deftest package-runtime-and-test-paths-are-separated
  (doseq [{:keys [id root]} packages]
    (let [{:keys [paths aliases]} (edn/read-string (slurp (str root "/deps.edn")))]
      (is (= ["src"] paths) (str id " runtime paths must contain source only"))
      (is (= ["test"] (get-in aliases [:test :extra-paths]))
          (str id " tests must require the explicit :test alias")))))

(deftest source-candidate-is-registered-without-claiming-release
  (let [catalog (edn/read-string (slurp "packages/module-boundaries.edn"))
        candidate (get-in catalog [:package-contracts :prf-extension-api-v1])
        manifest (edn/read-string
                  (slurp "packages/prf-extension-api-v1/DEPENDENCIES.edn"))]
    (is (= 'resolver-sim.extensions.api.v1 (:public-namespace candidate)))
    (is (= :in-tree-source-candidate (:status candidate)))
    (is (= "packages/prf-extension-api-v1/DEPENDENCIES.edn"
           (:dependency-manifest candidate)))
    (is (false? (get-in candidate [:qualification :released-artifact?])))
    (is (false? (get-in candidate [:qualification :immutable-coordinate?])))
    (is (= :in-tree-source-candidate (:package/status manifest)))
    (is (false? (get-in manifest [:source-layout :independently-released?])))
    (is (nil? (get-in manifest [:publication :dependency-coordinate])))))
