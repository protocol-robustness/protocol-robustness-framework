#!/usr/bin/env bb
(ns scripts.assurance.verify-checkpoint
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def checkpoint-files
  {:sew-v1x-deployment-era
   "docs/assurance/checkpoints/sew-v1x-deployment-era.edn"
   :sew-v1x-appeal-bond-correction-2026-07-31
   "docs/assurance/checkpoints/sew-v1x-appeal-bond-correction-2026-07-31.edn"
   :sew-sep-2026-appeal-candidate
   "docs/assurance/checkpoints/sew-sep-2026-appeal-candidate.edn"})

(def overlay-files
  {:sew-v1x-appeal-bond-correction-2026-07-31
   "docs/assurance/checkpoints/sew-v1x-appeal-bond-claim-overlay.edn"})

(defn digest-bytes [bytes]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest bytes)
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn sha256-string [value]
  (digest-bytes (.getBytes (str value) "UTF-8")))

(defn sha256-file [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array 65536)]
        (loop [n (.read in buf)]
          (when-not (neg? n)
            (.update digest buf 0 n)
            (recur (.read in buf))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn sh-quote [value]
  (str "'" (str/replace value "'" "'\\''") "'"))

(defn historical-content [revision path]
  (let [tmp (java.io.File/createTempFile "prf-historical-" ".edn")
        command (str "PAGER=cat jj file show -r "
                     (sh-quote revision) " " (sh-quote path)
                     " > " (sh-quote (.getAbsolutePath tmp)))
        {:keys [exit err]} (shell/sh "sh" "-c" command)]
    (try
      (if (zero? exit)
        (slurp tmp)
        (throw (ex-info "unable to read historical file"
                        {:revision revision :path path :exit exit :stderr err})))
      (finally (.delete tmp)))))

(defn scenario-id [content]
  (:scenario-id (edn/read-string content)))

(defn manifest-entries [scenarios]
  (->> scenarios
       (sort-by (juxt :scenario/id :path))
       (mapv #(select-keys % [:scenario/id :path :sha256 :classification]))))

(defn corpus-root [scenarios]
  (sha256-string (pr-str (manifest-entries scenarios))))

(defn overlay-root [overlay]
  (sha256-string
   (pr-str (->> (:entries overlay)
                (sort-by (juxt :scenario/id :scenario/path))
                (mapv #(select-keys % [:scenario/id :scenario/path :scenario/sha256 :claim-to]))))))

(defn checkpoint-root [checkpoint overlay-root-value]
  (sha256-string
   (pr-str {:checkpoint/id (:checkpoint/id checkpoint)
            :target (:target checkpoint)
            :prf (:prf checkpoint)
            :scenarios (manifest-entries (:scenarios checkpoint))
            :overlay-root overlay-root-value})))

(defn unknown-values [value path]
  (cond
    (= :unknown value) [path]
    (map? value) (mapcat (fn [[k v]] (unknown-values v (conj path k))) value)
    (vector? value) (mapcat (fn [[i v]] (unknown-values v (conj path i))) (map-indexed vector value))
    :else []))

(defn entries-for [checkpoint]
  (or (:scenarios checkpoint) []))

(defn verify-entry [entry content source]
  (let [actual (digest-bytes (.getBytes content "UTF-8"))]
    (cond-> []
      (not= (:scenario/id entry) (scenario-id content))
      (conj {:source source :path (:path entry) :error :scenario-id-mismatch})
      (not= (:sha256 entry) actual)
      (conj {:source source :path (:path entry) :error :sha256-mismatch
             :expected (:sha256 entry) :observed actual})) ))

(defn verify-overlay [checkpoint-id checkpoint]
  (if-let [path (get overlay-files checkpoint-id)]
    (let [overlay (edn/read-string (slurp path))
          by-id (into {} (map (juxt :scenario/id identity) (:entries overlay)))
          errors (mapcat
                  (fn [entry]
                    (let [overlay-entry (get by-id (:scenario/id entry))]
                      (cond-> []
                        (nil? overlay-entry)
                        (conj {:error :claim-overlay-missing
                               :scenario/id (:scenario/id entry)})
                        (and overlay-entry
                             (not= (:sha256 entry) (:scenario/sha256 overlay-entry)))
                        (conj {:error :claim-overlay-hash-mismatch
                               :scenario/id (:scenario/id entry)}))))
                  (:scenarios checkpoint))]
      {:overlay overlay :errors (vec errors)})
    {:overlay nil :errors []}))

(defn status-for [checkpoint errors]
  (cond
    (seq errors) :mismatch
    (seq (unknown-values checkpoint [])) :provenance-incomplete
    :else :verified))

(defn check [mode checkpoint-id]
  (let [checkpoint-path (get checkpoint-files checkpoint-id)]
    (when-not checkpoint-path
      (println "MISMATCH" {:error :unknown-checkpoint :checkpoint checkpoint-id
                            :known (sort (keys checkpoint-files))})
      (System/exit 1))
    (let [checkpoint (edn/read-string (slurp checkpoint-path))
          revision (get-in checkpoint [:prf :commit])
          entries (entries-for checkpoint)
          overlay-result (verify-overlay checkpoint-id checkpoint)
          overlay (:overlay overlay-result)
          contents (map (fn [entry]
                          (let [path (:path entry)]
                            (if (= mode :historical)
                              [entry (historical-content revision path) :historical]
                              [entry (slurp path) :worktree])))
                        entries)
          errors (vec (concat
                       (when (not= checkpoint-id (:checkpoint/id checkpoint))
                         [{:error :checkpoint-id-mismatch}])

                       (when (not= (count (map :scenario/id entries))
                                   (count (distinct (map :scenario/id entries))))
                         [{:error :duplicate-scenario-id}])
                       (mapcat (fn [[entry content source]]
                                 (verify-entry entry content source)) contents)
                       (:errors overlay-result)))
          actual-corpus-root (corpus-root entries)
          actual-overlay-root (when overlay (overlay-root overlay))
          actual-checkpoint-root (checkpoint-root checkpoint actual-overlay-root)
          root-errors (vec (concat
                            (when (and (get-in checkpoint [:roots :corpus])
                                       (not= (get-in checkpoint [:roots :corpus]) actual-corpus-root))
                              [{:error :corpus-root-mismatch
                                :expected (get-in checkpoint [:roots :corpus])
                                :observed actual-corpus-root}])
                            (when (and (not (or (nil? (get-in checkpoint [:roots :overlay]))
                                                                 (= :none (get-in checkpoint [:roots :overlay]))))
                                       (not= (get-in checkpoint [:roots :overlay]) actual-overlay-root))
                              [{:error :overlay-root-mismatch}])
                            (when (and (get-in checkpoint [:roots :checkpoint])
                                       (not= (get-in checkpoint [:roots :checkpoint]) actual-checkpoint-root))
                              [{:error :checkpoint-root-mismatch}])))
          all-errors (vec (concat errors root-errors))
          status (if (seq all-errors) :mismatch (status-for checkpoint []))]
      (println (str/upper-case (name status))
               {:checkpoint checkpoint-id
                :mode mode
                :scenario-count (count entries)
                :corpus-root actual-corpus-root
                :overlay-root actual-overlay-root
                :checkpoint-root actual-checkpoint-root
                :unknown-fields (unknown-values checkpoint [])})
      (when (seq all-errors)
        (doseq [error all-errors] (println "DETAIL" error)))
      (System/exit (if (= status :mismatch) 1 0)))))

(let [[command checkpoint-name] *command-line-args*
      checkpoint-id (keyword (or checkpoint-name ""))]
  (case command
    "verify-checkpoint" (check :historical checkpoint-id)
    "check-worktree" (check :worktree checkpoint-id)
    ;; Backward-compatible one-argument invocation verifies historical content.
    nil (check :historical (keyword (or (first *command-line-args*) "")))
    (do (println "Usage: verify-checkpoint <checkpoint> | check-worktree <checkpoint>")
        (System/exit 2))))
