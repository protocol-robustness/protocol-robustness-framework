(ns resolver-sim.commands.benchmark-smoke
  "All-manifest benchmark smoke matrix: plan/load every benchmark and execute
   a bounded representative matrix (one scenario per active benchmark) to
   control runtime while exercising the full plan-load-execute pipeline."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.commands.scenario-run :as scenario-run]
            [resolver-sim.commands.scenario-orchestration :as orchestration]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.suites :as suites]))

(defn- read-edn [path]
  (try (edn/read-string (slurp path)) (catch Exception _ nil)))

(defrecord BenchmarkPlan [id status pack suite-key scenario-ids scenario-count])

(defn- enumerate-plans
  []
  (let [registry (read-edn "benchmarks/registry.edn")]
    (when registry
      (mapcat
       (fn [pack]
         (let [pack-path (str "benchmarks/" (:pack/registry pack))
               pack-data (read-edn pack-path)]
           (when pack-data
             (let [pack-dir (.getParent (io/file pack-path))]
               (map
                (fn [ref]
                  (let [manifest-path (str pack-dir "/" (:benchmark/file ref))
                        manifest (read-edn manifest-path)
                        suite-key (:benchmark/scenario-suite manifest)
                        scenario-ids (when suite-key
                                       (->> (suites/suite-paths suite-key)
                                            (keep io-sc/scenario-file->id)
                                            vec))]
                    (->BenchmarkPlan
                     (:benchmark/id ref)
                     (:benchmark/status ref)
                     (:pack/id pack-data)
                     suite-key
                     scenario-ids
                     (count scenario-ids))))
                (:benchmarks pack-data)))))
         (:packs registry))))))

(def ^:private smoke-output-root "results/benchmark-smoke")

(defn- run-single-scenario
  [scenario-ref run-root]
  (try
    (let [parsed (scenario-run/parse-request [scenario-ref "--run-root" run-root])]
      (if-not (:ok? parsed)
        {:status :parse-error :errors (:errors parsed)}
        (let [context (scenario-run/build-run-context (:request parsed) {:project-root "."})
              result (orchestration/run-scenario! context)]
          {:status (:command/status result)
           :outcome (:scenario/outcome result)
           :run-root (:run/root result)
           :exit-code (:exit-code result)})))
    (catch Exception e
      {:status :exception :error (.getMessage e)})))

(defn- pick-scenario [plan rng]
  (let [ids (:scenario-ids plan)]
    (when (seq ids)
      (nth ids (.nextInt rng (count ids))))))

(defn smoke
  [{:keys [all? seed]}]
  (let [rng (java.util.Random. (or seed (System/currentTimeMillis)))
        plans (enumerate-plans)
        filtered (filter #(or (= :active (:status %))
                              (and all? (= :experimental (:status %))))
                         plans)]
    (println "Benchmark smoke matrix")
    (println (str "Total plans: " (count plans)
                  ", active/experimental: " (count filtered)))
    (println (str "Output root: " smoke-output-root))
    (println)
    (io/make-parents (str smoke-output-root "/.keep"))
    (let [results (doall
                   (map
                    (fn [plan]
                      (printf "  %-45s " (:id plan))
                      (flush)
                      (if-let [sid (pick-scenario plan rng)]
                        (let [root (str smoke-output-root "/" (name (:id plan)))
                              result (run-single-scenario sid root)]
                          (println (str (:status result) "/" (:outcome result)))
                          (assoc plan :smoke-status (:status result)
                                 :smoke-outcome (:outcome result)
                                 :smoke-run-root (:run-root result)
                                 :smoke-scenario sid))
                        (do (println "SKIP")
                            (assoc plan :smoke-status :skipped))))
                    filtered))]
      (println)
      (let [passed (count (filter #(= :completed (:smoke-status %)) results))
            failed (count (filter #(#{:exception :parse-error :rejected}
                                    (:smoke-status %))
                                  results))
            skipped (count (filter #(= :skipped (:smoke-status %)) results))]
        (printf "Passed: %d  Failed: %d  Skipped: %d\n" passed failed skipped)
        (when (pos? failed)
          (println "\nFailed benchmarks:")
          (doseq [r (filter #(#{:exception :parse-error :rejected}
                              (:smoke-status %))
                            results)]
            (printf "  - %s (%s)\n" (:id r) (:smoke-status r))))
        (println)
        {:exit-code (if (zero? failed) 0 1)
         :message (str "Smoke matrix: " (count results) " benchmarks, "
                       passed " passed, " failed " failed, " skipped " skipped")}))))

(defn -main [& args]
  (let [opts (loop [xs args m {:all? false :seed nil}]
               (if-let [a (first xs)]
                 (case a
                   "--all" (recur (rest xs) (assoc m :all? true))
                   "--seed" (recur (drop 2 xs)
                                   (assoc m :seed (Long/parseLong (second xs))))
                   (recur (rest xs) m))
                 m))]
    (System/exit (:exit-code (smoke opts)))))
