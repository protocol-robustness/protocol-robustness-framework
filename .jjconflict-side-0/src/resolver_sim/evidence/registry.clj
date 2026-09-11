(ns resolver-sim.evidence.registry
  "Evidence registry: a post-run aggregation of all evidence artifacts
   for a scenario run.  Built by scanning existing outputs, not by
   modifying capture paths.
   
   Usage:
     (build-evidence-registry! dir)
     ;; scans dir/event-evidence/, writes dir/evidence-registry.json
     ;; runs validation, writes dir/evidence-registry-validation.json
     ;; registers both in the chain registry for test-artifacts.json"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.io.event-evidence :as io-evidence]
            [resolver-sim.logging :as log]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- safe-str
  "Safely convert a value to a string, handling nil and keywords."
  [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (nil? v) ""
    :else (str v)))

;; ── Evidence Entry Builder ───────────────────────────────────────────────────

(defn- derive-evidence-id
  "Derive a stable evidence ID from the content hash or chain info."
  [artifact]
  (let [hash (or (:evidence/hash artifact)
                 (:evidence-hash artifact))
        chain-seq (:evidence/chain-seq artifact)]
    (cond
      (and hash (string? hash)) (str "ev-" (subs hash 0 (min 8 (count hash))))
      chain-seq (str "ev-seq-" chain-seq)
      :else (str "ev-unknown-" (System/identityHashCode artifact)))))

(defn- derive-evidence-role
  "Derive evidence role from importance or type."
  [artifact]
  (let [imp (or (:evidence/importance artifact) :diagnostic)
        etype (:evidence/type artifact)]
    (or (when (keyword? imp)
          (case imp
            :core :core
            :diagnostic :diagnostic
            :trace :trace
            nil))
        :diagnostic)))

(defn- extract-links
  "Derive cross-links for an evidence entry from the artifact data and indexes."
  [artifact event-idx group-idx]
  (let [etype (:evidence/type artifact)
        gid (:evidence/group-id artifact)]
    {:mechanism (io-evidence/mechanism-for-type etype)
     :targeted-count (if (and gid group-idx) (count (get group-idx gid [])) 0)}))

;; ── Registry Builder ─────────────────────────────────────────────────────────

(defn build-evidence-registry
  "Scan the event-evidence and diff-evidence directories and build an
   evidence registry map.  Accepts either a run directory (containing
   event-evidence/ and optionally diff-evidence/) or the event-evidence
   directory itself.
   
   Returns a map with:
   :entries     — vector of EvidenceEntry maps
   :indexes     — {:by-event-index, :by-group-id, :by-subject, :by-evidence-type, :by-layer}
   :run/id      — extracted from the first artifact
   :scenario/id — extracted from the first artifact
   
   Use build-evidence-registry! for the full build+write+validate pipeline."
  [& [dir]]
  (let [base-dir (or dir (str (evcfg/artifact-dir)))
        ;; Accept either run-dir with subdirs, or event-evidence dir directly
        ev-dir (let [d (io/file base-dir)]
                 (if (.isDirectory (io/file d "event-evidence"))
                   (str base-dir "/event-evidence")
                   base-dir))
        dir-file (io/file ev-dir)]
    (if-not (.isDirectory dir-file)
      {:entries [] :indexes {} :run/id nil :scenario/id nil :error (str "Not a directory: " ev-dir)}
      (let [ev-dir-file (if (.isDirectory (io/file base-dir "event-evidence"))
                          (io/file base-dir "event-evidence")
                          dir-file)
            diff-dir-file (when (.isDirectory (io/file base-dir "diff-evidence"))
                            (io/file base-dir "diff-evidence"))
            json-artifact? (fn [f]
                             (and (.isFile f)
                                  (.endsWith (.getName f) ".json")))
            ev-files (filter json-artifact? (file-seq ev-dir-file))
            diff-files (when diff-dir-file
                         (filter json-artifact? (file-seq diff-dir-file)))
            all-files (filter (fn [f] (not= "diff-index.json" (.getName f)))
                              (concat ev-files diff-files))
            artifacts (vec (sort-by :evidence/chain-seq
                                    (keep (fn [f]
                                            (try (io-evidence/read-evidence-json f)
                                                 (catch Exception e
                                                   (log/warn! "Failed to read evidence file for registry" {:path (str f) :error (.getMessage e)})
                                                   nil)))
                                          all-files)))
            ;; Extract run/scenario from first available
            first-artifact (first artifacts)
            run-id (or (:run/id first-artifact)
                       (get-in first-artifact [:attribution/context :run/id]))
            scenario-id (or (:scenario/id first-artifact)
                            (get-in first-artifact [:attribution/context :scenario/id]))
            ;; Build entries
            entries (mapv (fn [a]
                            (let [etype (:evidence/type a)
                                  ctx (or (:attribution/context a) (:attribution a) {})
                                  gid (:evidence/group-id a)
                                  aid (derive-evidence-id a)]
                              (merge
                               {:evidence/id aid
                                :evidence/type (keyword (safe-str etype))
                                :evidence/layer (if-let [l (:evidence/layer a)] (if (keyword? l) l (keyword (name l))) :targeted-protocol)
                                :evidence/role (derive-evidence-role a)
                                :hash/content (or (:evidence/hash a) (:evidence-hash a))
                                :file/path (str "event-evidence/"
                                                (io-evidence/evidence-filename a))}
                               (when (:evidence/chain-seq a)
                                 {:evidence/chain-seq (:evidence/chain-seq a)})
                               (when gid {:evidence/group-id gid})
                               (when scenario-id {:scenario/id scenario-id})
                               (when run-id {:run/id run-id})
                               (when-let [eid (:event/seq a)] {:event/index eid})
                               (when-let [ei (:event/index a)] {:event/index ei})
                               (when-let [ei (:ctx/event-index ctx)] {:event/index ei})
                               (when-let [et (:event/type a)] {:event/type (keyword (safe-str et))})
                               (when-let [et (:ctx/event-type ctx)] {:event/type (keyword (safe-str et))})
                               (when-let [st (:subject/type ctx)] {:subject/type (if (keyword? st) st (keyword (name st)))})
                               (when-let [si (:subject/id ctx)] {:subject/id si})
                               (when-let [at (:action/type ctx)] {:action/type at})
                               (when-let [er (:evidence/reason ctx)] {:evidence/reason er})
                               (when-let [wb (:world/before-full-hash a)]
                                 {:hash/world-before wb})
                               (when-let [wa (:world/after-full-hash a)]
                                 {:hash/world-after wa}))))
                          artifacts)
            ;; Build indexes
            indexed-entries (reduce (fn [m e] (assoc m (:evidence/id e) e)) {} entries)
            by-event-idx (reduce (fn [acc e]
                                   (if-let [ei (:event/index e)]
                                     (update acc ei (fnil conj []) (:evidence/id e))
                                     acc))
                                 {} entries)
            by-group-idx (reduce (fn [acc e]
                                   (if-let [gid (:evidence/group-id e)]
                                     (update acc gid (fnil conj []) (:evidence/id e))
                                     acc))
                                 {} entries)
            by-subject-idx (reduce (fn [acc e]
                                     (if-let [st (:subject/type e)]
                                       (let [si (or (:subject/id e) :unknown)]
                                         (update acc [st si] (fnil conj []) (:evidence/id e)))
                                       acc))
                                   {} entries)
            by-type-idx (reduce (fn [acc e]
                                  (if-let [et (:evidence/type e)]
                                    (update acc et (fnil conj []) (:evidence/id e))
                                    acc))
                                {} entries)
            by-layer-idx (reduce (fn [acc e]
                                   (if-let [l (:evidence/layer e)]
                                     (update acc l (fnil conj []) (:evidence/id e))
                                     acc))
                                 {} entries)]
        {:schema/version "evidence-registry.v1"
         :run/id (safe-str run-id)
         :scenario/id (safe-str scenario-id)
         :entries entries
         :indexes {:by-event-index by-event-idx
                   :by-group-id by-group-idx
                   :by-subject by-subject-idx
                   :by-evidence-type by-type-idx
                   :by-layer by-layer-idx}}))))

;; ── Write + Register ─────────────────────────────────────────────────────────

(defn write-evidence-registry!
  "Build the evidence registry and persist to evidence-registry.json.
   Also registers the artifact in the chain registry for test-artifacts.json.
   Returns {:registry-path \"...\" :entry-count N :registry <map>}"
  [& [dir]]
  (let [out-dir (or dir (str (evcfg/artifact-dir)))
        registry (build-evidence-registry (str out-dir "/event-evidence"))
        f (io/file out-dir "evidence-registry.json")]
    (.mkdirs (io/file out-dir))
    (spit f (json/write-str registry {:key-fn io-evidence/qualified-key :indent true}))
    (println "Wrote evidence registry:" (.getPath f) "-" (count (:entries registry)) "entries")
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :evidence-registry "evidence-registry.json"
                                 "evidence-registry.v1" "CORE"))
    {:registry-path (.getPath f)
     :entry-count (count (:entries registry))
     :registry registry}))

;; ── Researcher Query Helpers ─────────────────────────────────────────────────

(defn evidence-for-event
  "Return evidence entries matching the given event index."
  [registry event-index]
  (let [ids (get-in registry [:indexes :by-event-index event-index] [])]
    (filter (fn [e] (some #{(:evidence/id e)} (set ids))) (:entries registry))))

(defn evidence-for-group
  "Return evidence entries matching the given group-id."
  [registry group-id]
  (let [ids (get-in registry [:indexes :by-group-id group-id] [])]
    (filter (fn [e] (some #{(:evidence/id e)} (set ids))) (:entries registry))))

(defn evidence-for-subject
  "Return evidence entries for the given subject type and id."
  [registry subject-type subject-id]
  (let [ids (get-in registry [:indexes :by-subject [subject-type subject-id]] [])]
    (filter (fn [e] (some #{(:evidence/id e)} (set ids))) (:entries registry))))

(defn evidence-for-type
  "Return evidence entries matching the given evidence type."
  [registry evidence-type]
  (let [ids (get-in registry [:indexes :by-evidence-type evidence-type] [])]
    (filter (fn [e] (some #{(:evidence/id e)} (set ids))) (:entries registry))))

(defn evidence-for-layer
  "Return evidence entries matching the given layer (:generic-trace, :targeted-protocol, etc.)."
  [registry layer]
  (let [ids (get-in registry [:indexes :by-layer layer] [])]
    (filter (fn [e] (some #{(:evidence/id e)} (set ids))) (:entries registry))))