(ns scripts.scenarios.layering-lint
  "Static layering checker for resolver-sim namespaces.

   Enforces cross-layer import rules from CLAUDE.md (sim/io/db boundaries).
   Exit 0 when clean, 1 on violation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private allowlist
  #{})

(def ^:private rules
  [{:prefix "resolver-sim.sim."
    :forbidden ["resolver-sim.db." "resolver-sim.io."]}
   {:prefix "resolver-sim.stochastic."
    :forbidden ["resolver-sim.db." "resolver-sim.io." "resolver-sim.sim."]}
   {:prefix "resolver-sim.contract-model."
    :forbidden ["resolver-sim.db." "resolver-sim.io." "resolver-sim.sim."]}
   {:prefix "resolver-sim.protocols.sew."
    :forbidden ["resolver-sim.db." "resolver-sim.io." "resolver-sim.sim."]}
   {:prefix "resolver-sim.io."
    :forbidden ["resolver-sim.db."]}
   {:prefix "resolver-sim.db."
    :forbidden ["resolver-sim.sim."]}])

(defn- read-ns-form [file]
  (let [content (slurp file)
        rdr (java.io.PushbackReader. (java.io.StringReader. content))]
    (loop []
      (let [form (try (read rdr) (catch Exception _ nil))]
        (when form
          (if (and (list? form) (= 'ns (first form)))
            form
            (recur)))))))

(defn- requires [ns-form]
  (let [requires-clause (some #(when (= :require (first %)) (rest %)) (drop 2 ns-form))]
    (mapcat (fn [entry]
              (cond
                (symbol? entry) [entry]
                (and (vector? entry) (symbol? (first entry))) [(first entry)]
                :else []))
            (or requires-clause []))))

(defn- ns-name-str [sym]
  (if (namespace sym)
    (namespace sym)
    (name sym)))

(defn- src-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (filter #(.endsWith (.getName ^java.io.File %) ".clj"))
       (remove #(.endsWith (.getPath ^java.io.File %) "layering_lint.clj"))))

(defn- check-file [file]
  (when-let [ns-form (read-ns-form file)]
    (let [declared (name (second ns-form))]
      (when-not (contains? allowlist declared)
        (for [{:keys [prefix forbidden]} rules
              :when (str/starts-with? declared prefix)
              req (requires ns-form)
              :let [req-ns (ns-name-str req)]
              forbidden-prefix forbidden
              :when (str/starts-with? req-ns forbidden-prefix)]
          {:file (.getPath file)
           :ns declared
           :forbidden req-ns})))))

(defn -main [& _]
  (let [files (src-files "src/resolver_sim")
        violations (vec (mapcat check-file files))]
    (if (empty? violations)
      (do (println (format "Layering lint passed (%d namespaces checked)." (count files)))
          (System/exit 0))
      (do (println "Layering violations:")
          (doseq [{:keys [ns forbidden]} violations]
            (println (format "  %s must not require %s" ns forbidden)))
          (System/exit 1)))))
