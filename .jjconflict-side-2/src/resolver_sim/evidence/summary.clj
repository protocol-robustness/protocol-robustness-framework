(ns resolver-sim.evidence.summary
  "Evidence summary artifact: produces a researcher-readable evidence-summary.json
   that surfaces world-before/world-after hashes, dispute context, and evidence
   chain linkage for all evidence records in a run.

   This bridges the gap between raw evidence files (each with hashes) and a
   compact researcher-facing artifact that shows the full evidence-dag in one
   file.

   Usage:
     (require '[resolver-sim.evidence.summary :as ev-sum])
     (ev-sum/write-evidence-summary!)
     ;; reads results/test-artifacts/event-evidence/*.json
     ;; writes results/test-artifacts/evidence-summary.json"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.logging :as log]))

;; ---------------------------------------------------------------------------
;; Reading evidence files
;; ---------------------------------------------------------------------------

(defn- read-evidence-file
  "Read an event-evidence JSON file, converting string keys to keywords
   to match the writing format (event_evidence.clj uses qualified-key)."
  [f]
  (try (with-open [r (io/reader f)]
         (json/read r :key-fn keyword))
       (catch Exception e
         (log/warn! "Failed to read evidence file" {:path (str f) :error (.getMessage e)})
         nil)))

(defn- evidence-files
  [dir]
  (sort (filter #(.endsWith (.getName %) ".json")
                (map #(io/file dir %) (.list (io/file dir))))))

(defn- safe-str [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (nil? v) ""
    :else (str v)))

(defn- kw->json-key
  "Convert a keyword to a JSON-safe key string, preserving namespace.
   :evidence/type -> \"evidence/type\""
  [k]
  (if (keyword? k)
    (let [ns (namespace k)
          n (name k)]
      (if ns (str ns "/" n) n))
    (safe-str k)))

(defn- keywordize-keys-for-json
  "Convert keyword keys to strings for JSON serialization.
   Handles nested maps and vectors. Nil values are passed through."
  [x]
  (cond
    (nil? x) nil
    (map? x) (into {} (for [[k v] x]
                        [(kw->json-key k) (keywordize-keys-for-json v)]))
    (vector? x) (mapv keywordize-keys-for-json x)
    (keyword? x) (name x)
    :else x))

;; ---------------------------------------------------------------------------
;; Evidence summary builder
;; ---------------------------------------------------------------------------

(defn build-evidence-summary
  "Read all evidence files from the event-evidence directory and produce a
   structured summary map. Each entry includes:
     - seq, type, importance
     - event-hash, before-hash, after-hash
     - subject type, action type, evidence reason
     - group-id (for linked evidence)

   Evidence files use namespaced keys (\"evidence/type\", \"world/before-hash\",
   \"world/after-hash\", \"world/before-full-hash\", \"world/after-full-hash\",
   \"evidence/hash\", \"evidence/chain-seq\", \"scenario/id\", \"run/id\").

   Returns {:evidence-count N :by-group {group-id [...]} :records [...]}"
  [& [dir]]
  (let [evidence-dir (or dir (str (evcfg/artifact-dir) "/event-evidence"))
        dir-file (io/file evidence-dir)]
    (if-not (.isDirectory dir-file)
      {:evidence-count 0 :records [] :by-group {} :error (str "Not a directory: " evidence-dir)}
      (let [files (evidence-files evidence-dir)
            records (vec (keep read-evidence-file files))
            enriched (mapv (fn [r]
                             (let [ctx (or (:evidence/context r) {})]
                               {:evidence/chain-seq (or (:evidence/chain-seq r) 0)
                                :evidence/type (safe-str (or (:evidence/type r) ""))
                                :evidence/hash (safe-str (or (:evidence/hash r) ""))
                                :evidence/importance (safe-str (or (:evidence/importance r) "core"))
                                :scenario/id (safe-str (or (:scenario/id r) ""))
                                :run/id (safe-str (or (:run/id r) ""))
                                :world/before-hash (safe-str (or (:world/before-hash r)
                                                                 (:world/before-full-hash r)
                                                                 ""))
                                :world/after-hash (safe-str (or (:world/after-hash r)
                                                                (:world/after-full-hash r)
                                                                ""))
                                :world/before-full-hash (safe-str (or (:world/before-full-hash r) ""))
                                :world/after-full-hash (safe-str (or (:world/after-full-hash r) ""))
                                :evidence/subject-type (safe-str (or (:subject/type ctx) ""))
                                :evidence/action-type (safe-str (or (:action/type ctx) ""))
                                :evidence/reason (safe-str (or (:evidence/reason ctx) ""))
                                :evidence/group-id (or (:evidence/group-id r)
                                                       (get-in r [:inputs :group-id])
                                                       nil)}))
                           records)
            by-group (group-by :evidence/group-id enriched)
            dispute-records (filter #(str/includes? (:evidence/type %) "dispute") enriched)
            slash-records (filter #(str/includes? (:evidence/type %) "slash") enriched)]
        {:evidence-count (count enriched)
         :records enriched
         :by-group (into {} (map (fn [[k v]] [k (vec v)]) by-group))
         :dispute-records (vec dispute-records)
         :slash-records (vec slash-records)}))))

;; ---------------------------------------------------------------------------
;; Persistence
;; ---------------------------------------------------------------------------

(defn write-evidence-summary!
  "Build and persist the evidence summary to evidence-summary.json.
   Registers the artifact in the chain registry for test-artifacts.json.
   Returns the path to the written file."
  [& [dir]]
  (let [summary (build-evidence-summary dir)
        json-safe (keywordize-keys-for-json summary)
        out-dir (or dir (evcfg/artifact-dir))
        f (io/file (str out-dir) "evidence-summary.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str json-safe {:indent true}))
    (println "Wrote evidence summary:" (.getPath f))
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-summary "evidence-summary.json"
                                 "evidence-summary.v1" "CORE"))
    (.getPath f)))
