(ns scripts.audit-prf-core-candidate
  "Print the extension-core source closure and its direct in-repository consumers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.parse :as ns-parse]))

(def source-roots
  ["src" "test" "protocols_src/resolver_sim" "protocols_src/test/resolver_sim"
   "extensions/force-authorisation/src"
   "extensions/force-authorisation/test"
   "extensions/held-custody/src"
   "extensions/held-custody/test"
   "packages"])

(def package-roots
  ["extensions/force-authorisation/src"
   "extensions/force-authorisation/test"
   "extensions/held-custody/src"
   "extensions/held-custody/test"])

(defn- namespace-forms [roots]
  (mapcat #(ns-find/find-ns-decls-in-dir (io/file %)) roots))

(defn- declaration-name [form]
  (ns-parse/name-from-ns-decl form))

(defn- declaration-dependencies [form]
  (ns-parse/deps-from-ns-decl form))

(defn- candidate-closure [graph]
  (let [package-namespaces (mapcat #(ns-find/find-namespaces-in-dir (io/file %))
                                   package-roots)
        roots (cons 'resolver-sim.extensions.api.v1 package-namespaces)]
    (loop [pending roots seen #{}]
      (if-let [namespace (first pending)]
        (if (contains? seen namespace)
          (recur (rest pending) seen)
          (recur (concat (get graph namespace []) (rest pending))
                 (conj seen namespace)))
        (set (filter #(and (symbol? %)
                           (str/starts-with? (str %) "resolver-sim."))
                      seen))))))

(defn -main [& _]
  (let [forms (namespace-forms (filter #(.isDirectory (io/file %)) source-roots))
        declarations (group-by declaration-name forms)
        graph (into {} (map (fn [[namespace forms]]
                              [namespace (apply set/union #{}
                                                (map declaration-dependencies forms))]))
                    declarations)
        actual (candidate-closure graph)
        manifest (edn/read-string
                  (slurp "packages/prf-extension-api-v1/DEPENDENCIES.edn"))
        expected (set (:source-closure manifest))
        consumers (for [[namespace dependencies] graph
                        :when (not (contains? actual namespace))
                        dependency dependencies
                        :when (contains? actual dependency)]
                    [dependency namespace])
        consumer-counts (frequencies (map first consumers))]
    (when-not (= expected actual)
      (binding [*out* *err*]
        (println "Candidate source closure differs from its dependency manifest.")
        (println "Missing from manifest:" (str/join ", " (sort-by str (set/difference actual expected))))
        (println "No longer required:" (str/join ", " (sort-by str (set/difference expected actual)))))
      (System/exit 1))
    (println "Candidate closure namespaces:" (count actual))
    (println "Direct consumer counts outside candidate (source and test namespaces):")
    (doseq [namespace (sort-by str actual)]
      (println (format "%-58s %d" namespace (get consumer-counts namespace 0))))))

(apply -main *command-line-args*)
