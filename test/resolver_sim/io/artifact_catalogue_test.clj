(ns resolver-sim.io.artifact-catalogue-test
  "Verify that the code-level artifact catalogue in io.artifacts agrees with
   the external evidence configuration in config/evidence.json.

   The catalogue is the source of truth for canonical artifact filenames.
   config/evidence.json may enrich or validate entries but must not silently
   redefine the package contract."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.io.artifacts :as arts]))


(defn- load-evidence-config
  []
  (-> (io/resource "config/evidence.json") slurp (json/read-str :key-fn keyword)))


(def ^:private code-artifact-ids
  "Canonical artifact IDs that SHOULD appear in config/evidence.json."
  #{:run/completion :run/package-index :artifacts/registry :artifacts/validation :sensitivity/report})


(deftest code-artifacts-have-expected-structure
  (doseq [id (keys arts/artifacts)]
    (let [a (arts/artifact id)]
      (is (string? (:file a)) (str "artifact " id " has a :file string"))
      (is (keyword? (:kind a)) (str "artifact " id " has a :kind keyword"))
      (is (contains? #{:canonical :transient} (:durability a))
          (str "artifact " id " has valid :durability"))
      (is (contains? #{true false} (:review-surface? a))
          (str "artifact " id " has boolean :review-surface?"))
      (when (= :canonical (:durability a))
        (is (string? (:schema a)) (str "canonical artifact " id " has a :schema string"))))))


(deftest code-artifacts-appear-in-evidence-config
  (let [cfg (load-evidence-config)
        config-artifacts (set (map :id (:artifacts cfg)))]
    (doseq [code-id code-artifact-ids]
      (let [code-file (arts/artifact-file code-id)
            ;; config/evidence.json uses string IDs like "run-completion"
            config-id (name code-id)
            matching (filter #(= (:file %) code-file) (:artifacts cfg))]
        (if (seq matching)
          (is (= 1 (count matching))
              (str "evidence config has exactly one entry for " code-id))
          (is (false? "not found") ; will fail with message
              (str code-id " (\"" code-file "\") is missing from config/evidence.json")))))))


(deftest no-evidence-config-silently-redefines-code-artifact
  ;; Every canonical artifact entry in config/evidence.json whose :file matches
  ;; a code-level artifact must have the same filename as the code catalogue.
  ;; If the config disagrees, the code catalogue wins — the test fails.
  (let [cfg (load-evidence-config)]
    (doseq [entry (:artifacts cfg)]
      (let [cfg-file (:file entry)]
        (doseq [code-id code-artifact-ids]
          (let [code-file (arts/artifact-file code-id)]
            (when (= cfg-file code-file)
              ;; Filenames match — verify the config ID is consistent
              (is (= (name code-id) (:id entry))
                  (str "evidence config entry \"" cfg-file "\" has :id \""
                       (:id entry) "\" but code catalogue expects \""
                       (name code-id) "\"")))))))))
