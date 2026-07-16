(ns resolver-sim.commands.benchmark-validate-jar
  "Built-JAR corpus validation: enumerate every packaged benchmark, resolve
   every suite/input through resolver-sim.io.input-source, and construct
   execution plans without filesystem fallback.

   Run from an unrelated working directory to simulate JAR context, or from
   the repo to verify that all paths are classpath-resolvable."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.scenario.suites :as suites]))

;; ───────────────────────────────────────────────────────────────────────
;; Helpers
;; ───────────────────────────────────────────────────────────────────────

(defn- edn-read-resource [path]
  (try (rp/edn-read path)
       (catch Exception e
         (throw (ex-info (str "Cannot read " path ": " (.getMessage e))
                         {:path path :error (.getMessage e)})))))

(defn- resolve-or-throw
  "Resolve a path spec via input-source/source, throwing with context on failure."
  [path-spec label]
  (try
    (input-source/source path-spec)
    (catch Exception e
      (throw (ex-info (str label " not resolvable: " path-spec)
                      {:path path-spec :label label :error (.getMessage e)})))))

;; ───────────────────────────────────────────────────────────────────────
;; Enumeration
;; ───────────────────────────────────────────────────────────────────────

(defrecord Plan [benchmark-id manifest-path suite-key scenario-count scenario-paths])

(defn- plan-benchmark
  "Construct an execution plan for one benchmark, resolving every path through
   InputSource. Throws if any resource is not classpath-resolvable."
  [pack-resource-path benchmark-ref]
  (let [bid (:benchmark/id benchmark-ref)
        pack-file (io/file (subs pack-resource-path (count "resource:")))
        pack-dir (.getParent pack-file)
        manifest-rel (:benchmark/file benchmark-ref)
        manifest-resource (str "resource:" pack-dir "/" manifest-rel)]
    ;; Resolve manifest
    (resolve-or-throw manifest-resource (str "Manifest " bid))
    (let [manifest (edn-read-resource manifest-resource)
          suite-key (:benchmark/scenario-suite manifest)]
      (when suite-key
        (let [scenario-paths (suites/suite-paths suite-key)]
          (when scenario-paths
            ;; Resolve every scenario path
            (doseq [sp scenario-paths]
              (resolve-or-throw sp (str "Scenario " sp))))
          (->Plan bid manifest-resource suite-key
                  (count scenario-paths) scenario-paths))))))

(defn- enumerate-plans
  "Enumerate all benchmark execution plans from the canonical registry,
   resolving every path through InputSource."
  []
  (let [registry-path rp/canonical-registry-path
        _ (resolve-or-throw registry-path "Global registry")
        registry (edn-read-resource registry-path)
        packs (:packs registry [])]
    (mapcat (fn [pack]
              (let [pack-rel (:pack/registry pack)
                    pack-resource (rp/pack-registry-path pack-rel)
                    _ (resolve-or-throw pack-resource (str "Pack " (:pack/id pack)))
                    pack-data (edn-read-resource pack-resource)]
                (map (fn [benchmark-ref]
                       (plan-benchmark pack-resource benchmark-ref))
                     (:benchmarks pack-data))))
            packs)))

;; ───────────────────────────────────────────────────────────────────────
;; Validation
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-plan
  "Validate that a single benchmark plan produces a coherent execution graph
   without filesystem fallback."
  [errors plan]
  (when (nil? (:suite-key plan))
    (swap! errors conj (str "Benchmark " (:benchmark-id plan)
                            " has nil suite-key — cannot construct plan")))
  (when (zero? (:scenario-count plan))
    (swap! errors conj (str "Benchmark " (:benchmark-id plan)
                            " has zero scenarios in suite " (:suite-key plan))))
  (doseq [sp (:scenario-paths plan)]
    (when (and (string? sp)
               (not (str/starts-with? sp "resource:"))
               (not (str/starts-with? sp "classpath:")))
      (swap! errors conj (str "Non-resource scenario path " sp
                              " in " (:benchmark-id plan)
                              " will not resolve from JAR")))))

(defn validate-jar
  "Validate that every benchmark in the registry is fully resolvable through
   InputSource without filesystem fallback.

   This simulates JAR context: all paths must use resource: or classpath:
   prefixes.  Bare filesystem paths will fail when the code runs from a JAR
   in an unrelated working directory."
  [_opts]
  (println "Validating benchmark JAR corpus...")
  (println "  Enumerating plans from registry:" rp/canonical-registry-path)
  (let [errors (atom [])
        plans (try
                (enumerate-plans)
                (catch Exception e
                  (swap! errors conj (str "Plan enumeration failed: " (.getMessage e)))
                  nil))]
    (when plans
      (println (str "  Found " (count plans) " benchmark(s)"))
      (doseq [p plans]
        (printf "    %-45s suite=%-30s scenarios=%d\n"
                (:benchmark-id p) (:suite-key p) (:scenario-count p))
        (validate-plan errors p)))
    (let [exit-code (if (empty? @errors) 0 1)]
      (doseq [e @errors] (println (str "  ✗ " e)))
      (println (str "  " (count @errors) " error(s)"))
      {:exit-code exit-code
       :message (if (zero? exit-code)
                  "Benchmark JAR corpus validation passed"
                  "Benchmark JAR corpus validation failed")
       :errors @errors})))

(defn -main [& _]
  (let [result (validate-jar {})]
    (System/exit (:exit-code result))))
