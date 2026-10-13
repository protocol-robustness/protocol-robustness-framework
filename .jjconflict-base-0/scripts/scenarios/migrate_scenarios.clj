(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn- infer-id [filename]
  (keyword (str "scenarios/" (str/replace filename #"\.trace\.json$" ""))))

(defn- migrate-file [file]
  (println "Migrating:" (.getName file))
  (let [scenario (with-open [r (io/reader file)] (json/read r :key-fn keyword))
        version (str (:schema-version scenario))]
    (if (= version "1.0")
      (let [new-scenario (assoc scenario
                                :schema-version "1.1"
                                :id (infer-id (.getName file))
                                :purpose :regression
                                :threat-tags [])]
        (with-open [w (io/writer file)]
          (json/write new-scenario w :key-fn name :value-fn (fn [k v] (if (keyword? v) (name v) v))))
        (println "  DONE: Version 1.1 with regression purpose."))
      (println "  SKIP: Already version" version))))

(defn -main []
  (let [traces-dir (io/file "data/fixtures/traces")]
    (doseq [f (.listFiles traces-dir)
            :when (str/ends-with? (.getName f) ".trace.json")]
      (migrate-file f))))

(-main)
