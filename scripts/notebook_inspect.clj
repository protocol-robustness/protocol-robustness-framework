(ns scripts.notebook-inspect
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def ^:private alias-table
  "Maps short notebook aliases to their full namespaces.
   Add entries here as needed — pull requests welcome."
  {"sew-econ"   "resolver-sim.protocols.sew.economics"
   "sew"        "resolver-sim.protocols.sew"
   "slashing"   "resolver-sim.protocols.sew.evidence.slashing"
   "hc"         "resolver-sim.hash.canonical"
   "payoffs"    "resolver-sim.economics.payoffs"
   "types"      "resolver-sim.protocols.sew.types"
   "pro-rata-evidence" "resolver-sim.pro-rata.evidence"
   "att"        "resolver-sim.evidence.attestation"
   "adag"       "resolver-sim.evidence.attestation-dag"
   "ar"         "resolver-sim.evidence.attestation-registry"
   "ars"        "resolver-sim.evidence.attestation-resolver"
   "batch"      "resolver-sim.sim.batch"
   "rng"        "resolver-sim.stochastic.rng"
   "common"     "resolver-sim.notebook-support.common"
   "story"      "resolver-sim.notebook-support.speds.story"
   "data"       "resolver-sim.notebook-support.speds.data"
   "config"     "resolver-sim.notebook-support.speds.config"
   "views"      "resolver-sim.notebook-support.views"
   "nav"        "resolver-sim.notebook-support.nav"
   "checks"     "resolver-sim.notebook-support.checks"
   "io-params"  "resolver-sim.io.params"})

(defn- resolve-ns
  "Try to load a namespace by its short alias or full name.
   Returns the resolved ns symbol on success, nil otherwise."
  [short-ns]
  (let [candidates (distinct
                    [(symbol short-ns)
                     (when-let [full (get alias-table (name short-ns))]
                       (symbol full))])]
    (some (fn [c]
            (try (require c :reload) c (catch Exception _ nil)))
          candidates)))

(defn- describe-type [v]
  (cond
    (nil? v) "nil"
    (instance? Boolean v) "Boolean"
    (instance? Long v) "Long"
    (instance? Double v) "Double"
    (instance? String v) "String"
    (instance? clojure.lang.Keyword v) "Keyword"
    (instance? clojure.lang.IPersistentMap v) "Map"
    (instance? clojure.lang.IPersistentVector v) (str "Vector of " (if (seq v) (describe-type (first v)) "?"))
    (instance? clojure.lang.IPersistentSet v) "Set"
    (instance? clojure.lang.ISeq v) "Seq"
    (instance? java.util.regex.Pattern v) "Regex"
    (symbol? v) "Symbol"
    :else (.getName (class v))))

(defn- inspect-shape
  ([val] (inspect-shape val ""))
  ([val indent]
   (let [typ (describe-type val)]
     (println (str indent "  type: " typ))
     (when (instance? clojure.lang.IPersistentMap val)
       (doseq [k (sort (keys val))]
         (print (str indent "  " (pr-str k) " → "))
         (let [v (get val k)]
           (println (describe-type v))
           (when (or (instance? clojure.lang.IPersistentMap v)
                     (and (instance? clojure.lang.IPersistentVector v)
                          (seq v)
                          (instance? clojure.lang.IPersistentMap (first v))))
             (let [sample (if (instance? clojure.lang.IPersistentMap v) v (first v))]
               (inspect-shape sample (str indent "    "))))))))))

(defn -main [& args]
  (when (< (count args) 1)
    (println "Usage: bb notebook:inspect <ns/fn> [edn-args]")
    (println)
    (println "Examples:")
    (println "  bb notebook:inspect sew-econ/calculate-sew-slash-allocation")
    (println "  bb notebook:inspect payoffs/evaluate-pro-rata-allocation '{:allocation/id :d :unit :USDC :amount 100 :participants [{:id \"A\" :weight 100 :cap 100}] :policy {:rounding :floor-with-largest-remainder :tie-break :input-order :algorithm :weighted-pro-rata :cap-treatment :redistribute} :source {:type :demo}}'")
    (println "  bb notebook:inspect sew-econ/calculate-sew-slash-allocation '{:slash-obligation 300 :liable-parties [{:id \"A\" :slashable-stake 1000 :available-slashable 1000}]}'")
    (println)
    (println "Note: quote EDN maps with single quotes to prevent shell expansion of {}.")
    (System/exit 1))
  (let [[fn-spec & raw-args] args
        fn-sym (symbol fn-spec)
        short-ns (symbol (namespace fn-sym))
        fn-name (symbol (name fn-sym))
        edn-args (when (seq raw-args)
                   (mapv edn/read-string raw-args))
        arg-str (or (first raw-args) "(none)")]
    (println "─── notebook:inspect ───")
    (println (str "  Function: " fn-spec))
    (println (str "  Args:     " arg-str))
    (println)
    (if-let [ns-sym (resolve-ns short-ns)]
      (try
        (let [result (apply (ns-resolve ns-sym fn-name) edn-args)]
          (println "  Result:")
          (inspect-shape result "    ")
          (println)
          (println "  Full result (first 20 lines):")
          (println "    ---")
          (binding [pp/*print-right-margin* 100]
            (println (str/join "\n" (take 20 (str/split-lines (with-out-str (pp/pprint result)))))))
          (println "    ---"))
        (catch Exception e
          (println "  ERROR:" (.getMessage e))
          (when-let [cause (.getCause e)]
            (println "  CAUSE:" (.getMessage cause)))
          (System/exit 1)))
      (do
        (println "  Could not resolve namespace:" short-ns)
        (println "  Provide the full namespace or add an alias to the script.")
        (println)
        (println "  Full namespace examples:")
        (println "    bb notebook:inspect resolver-sim.protocols.sew.economics/calculate-sew-slash-allocation")
        (println "    bb notebook:inspect resolver-sim.economics.payoffs/evaluate-pro-rata-allocation")
        (println)
        (System/exit 1)))))
