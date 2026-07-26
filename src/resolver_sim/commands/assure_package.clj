(ns resolver-sim.commands.assure-package
  "Evaluate whether a verified canonical invariant registry suite run package
   satisfies release or assurance policy.

   This is a policy judgement about an immutable, verified package.  It does
   not modify execution artifacts.  The result is written as a derived artifact
   that binds the completion hash.

   Usage:  clojure -M:cli/sew assure-package --run-root DIR"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.hash.canonical :as hc]))

(defn- slurp-json
  [path]
  (when (.exists (io/file path))
    (try (json/read-str (slurp path) :key-fn keyword) (catch Exception _ nil))))

(defn- check-verify-passed
  [run-root]
  (let [completion (slurp-json (str run-root "/completion.json"))
        results (slurp-json (str run-root "/scenario-results.json"))]
    (if (and completion results)
      {:status :passed :passed (get-in completion [:passed-count] 0)}
      {:status :failed :reason "Completion or results missing"})))

(defn- check-all-expected-passed
  [run-root]
  (let [results (slurp-json (str run-root "/scenario-results.json"))
        summary (:summary results)]
    (if summary
      (let [expected (:expected summary 0)
            passed (:passed summary 0)
            failed (:failed summary 0)
            xfailed (:xfailed summary 0)]
        (if (and (zero? failed) (zero? xfailed))
          {:status :passed :expected expected :passed passed}
          {:status :failed :expected expected :passed passed :failed failed :xfailed xfailed}))
      {:status :failed :reason "No results summary"})))

(defn- check-no-skipped
  [run-root]
  (let [results (slurp-json (str run-root "/scenario-results.json"))
        summary (:summary results)]
    (if summary
      (let [expected (:expected summary 0)
            results-count (count (:results results))]
        (if (= expected results-count)
          {:status :passed :expected expected :results-count results-count}
          {:status :failed :expected expected :results-count results-count
           :reason "Result count does not match expected"}))
      {:status :failed :reason "No results summary"})))

(defn- subject-binding
  [run-root]
  (let [completion (slurp-json (str run-root "/completion.json"))
        plan (slurp-json (str run-root "/run-plan.json"))]
    {:completion/status (:status completion)
     :completion/kind (:kind completion)
     :plan-hash (:plan-hash completion)
     :plan-kind (:kind plan)}))

(defn run
  "Evaluate package assurance for a verified invariant registry suite run root.
   Options:
     :run-root DIR  — required, path to the verified run package"
  [{:keys [run-root] :as opts}]
  (if-not run-root
    (do (println "Usage: assure-package --run-root DIR")
        {:exit-code 2 :message "Missing --run-root"})
    (let [root-dir (io/file run-root)]
      (if-not (.exists root-dir)
        (do (println (str "Run root not found: " run-root))
            {:exit-code 1 :message "Run root not found"})
        (let [verify-check (check-verify-passed run-root)
              passed-check (check-all-expected-passed run-root)
              skip-check (check-no-skipped run-root)
              checks {:package-verification {:status (:status verify-check)
                                             :passed (:passed verify-check 0)}
                      :all-scenarios-passed {:status (:status passed-check)
                                             :expected (:expected passed-check 0)
                                             :passed (:passed passed-check 0)}
                      :no-skipped-scenarios {:status (:status skip-check)}}
              check-failed? (some #(= :failed (:status (val %))) checks)
              assurance {:artifact-kind "package-assurance"
                         :assurance/version 1
                         :assurance/timestamp (str (java.time.Instant/now))
                         :subject (subject-binding run-root)
                         :checks (into {} (map (fn [[k v]] [k v]) checks))
                         :assurance/status (if check-failed? "failed" "passed")}]
          (spit (str run-root "/package-assurance.json")
                (json/write-str assurance :escape-slash false :escape-unicode false))
          (println (str "Package assurance: " (:assurance/status assurance)))
          (when check-failed?
            (doseq [[k v] checks :when (= (:status v) :failed)]
              (println (str "  ✗ " (name k) ": " (:reason v "failed")))))
          {:exit-code (if check-failed? 1 0)
           :message (str "Assurance: " (:assurance/status assurance))
           :assurance assurance})))))

(defn -main
  [& args]
  (let [run-root (some (fn [[flag val]] (when (= flag "--run-root") val))
                       (partition 2 args))]
    (if-not run-root
      (do (println "Usage: clojure -M -m resolver-sim.commands.assure-package --run-root DIR")
          (System/exit 2))
      (let [result (run {:run-root run-root})]
        (System/exit (:exit-code result 1))))))
