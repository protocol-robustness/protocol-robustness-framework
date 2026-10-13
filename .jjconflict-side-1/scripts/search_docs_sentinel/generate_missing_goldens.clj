(ns scripts.search_docs_sentinel.generate-missing-goldens
  "Generate golden reports for scenario files that lack them.
   Usage: clojure -M -i scripts/search_docs_sentinel/generate_missing_goldens.clj"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.normalize :as normalize]))

(def golden-dir "data/fixtures/golden")
(def scenarios-dir "scenarios")

(def golden-schema-version "2.0")

(defn- normalize-name [n]
  (-> n str/lower-case (str/replace "_" "-")))

(defn- load-scenario [path]
  (try
    (let [raw (io-sc/load-scenario-file path)
          scenario (normalize/normalize-scenario raw)]
      scenario)
    (catch Exception e
      (println "  Error loading" path ":" (.getMessage e))
      nil)))

(defn- generate-golden-report [suite-id trace-id replay-result]
  (let [last-entry (last (:trace replay-result))
        final-hash (get-in last-entry [:projection-hash])]
    {:golden-schema-version golden-schema-version
     :suite-id suite-id
     :trace-id trace-id
     :final-state-hash final-hash
     :metrics (:metrics replay-result)
     :outcome (:outcome replay-result)}))

(defn -main [& args]
  (let [scenario-files (->> (.listFiles (io/file scenarios-dir))
                            (filter #(str/ends-with? (.getName %) ".json"))
                            (remove #(.startsWith (.getName %) "debug-"))
                            (remove #(.startsWith (.getName %) "dynamic-"))
                            (sort-by #(.getName %)))
        existing-goldens (->> (.listFiles (io/file golden-dir))
                              (filter #(str/ends-with? (.getName %) ".report.edn"))
                              (map #(-> (.getName %) (str/replace #"\.report\.edn$" "") normalize-name))
                              set)
        missing (filter (fn [f]
                          (let [normalized (normalize-name (-> (.getName f) (str/replace #"\.json$" "")))]
                            (not (contains? existing-goldens normalized))))
                        scenario-files)
        total (count missing)]
    (println (str "Found " total " scenario files without golden reports"))
    (doseq [[idx f] (map-indexed vector missing)]
      (let [stem (-> (.getName f) (str/replace #"\.json$" ""))
            normalized (normalize-name stem)]
        (print (str "[" (inc idx) "/" total "] " stem "... "))
        (flush)
        (if-let [scenario (load-scenario (.getPath f))]
          (try
            (let [result (sew/replay-with-sew-protocol scenario)
                  golden (generate-golden-report :standalone normalized result)
                  path (str golden-dir "/" normalized ".report.edn")]
              (spit path (with-out-str (pp/pprint golden)))
              (println "OK"))
            (catch Exception e
              (println "FAIL:" (.getMessage e))))
          (println "SKIP (load error)"))))
    (println "\nDone.")))
