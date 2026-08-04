(ns resolver-sim.commands.concepts
  "Concept validation commands.
   Port of scripts/concepts_validate.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.concepts.registry :as concepts-registry]
            [resolver-sim.config.paths :as paths]))

(defn- validate-concepts
  "Core validation logic. Returns {:exit-code N :message str :errors [] :warnings []}."
  [root-dir]
  (let [root (io/file root-dir)
        errors (atom [])
        warnings (atom [])]
    (if-not (.exists root)
      (do (println (str "  Concept directory not found: " root-dir))
          {:exit-code 3 :message "Concept directory not found" :errors [] :warnings []})
      (do
        (try
          (let [registry-file (io/file root "registry.edn")]
            (when-not (.exists registry-file)
              (swap! errors conj "registry.edn not found"))
            (let [registry (edn/read-string (slurp registry-file))
                  commands (:commands registry [])]
              (doseq [cmd commands]
                (when-let [file-path (:file-path cmd)]
                  (let [f (io/file root file-path)]
                    (when-not (.exists f)
                      (swap! errors conj (str "File not found: " file-path)))))
                (when (nil? (:id cmd))
                  (swap! errors conj "Command missing :id"))
                (when (nil? (:type cmd))
                  (swap! errors conj (str "Command " (:id cmd) " missing :type"))))
              (let [ids (map :id commands)
                    dups (set (for [[id freq] (frequencies ids) :when (> freq 1)] id))]
                (doseq [d dups]
                  (swap! errors conj (str "Duplicate concept ID: " d))))
              (doseq [f (filter #(.endsWith (.getName %) ".edn")
                                (remove #(.isDirectory %) (file-seq root)))
                      :when (not= "registry.edn" (.getName f))]
                (try
                  (let [data (edn/read-string (slurp f))]
                    (doseq [k [:concept/id :concept/name :concept/summary]]
                      (when (nil? (get data k))
                        (swap! errors conj (str (.getName f) " missing " k)))))
                  (catch Exception e
                    (swap! errors conj (str "Failed to parse " (.getName f) ": " (.getMessage e))))))
              (try
                (let [missing (concepts-registry/missing-related-concepts registry)]
                  (doseq [m missing]
                    (swap! warnings conj (str "Missing related concept: " m))))
                (catch Exception e
                  (swap! errors conj (str "Cross-reference check failed: " (.getMessage e)))))))
          (catch Exception e
            (swap! errors conj (str "Registry parse failed: " (.getMessage e)))))
        (let [exit-code (if (empty? @errors) 0 1)]
          (doseq [e @errors] (println (str "  ✗ " e)))
          (doseq [w @warnings] (println (str "  ⚠ " w)))
          (println (str "  " (count @errors) " errors, " (count @warnings) " warnings"))
          {:exit-code exit-code
           :message (if (zero? exit-code) "Concept validation passed" "Concept validation failed")
           :errors @errors :warnings @warnings})))))

(defn validate
  "Validate concept data files and the concept registry."
  [opts]
  (validate-concepts (paths/concepts-dir)))
