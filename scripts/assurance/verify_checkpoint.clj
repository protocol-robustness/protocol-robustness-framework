#!/usr/bin/env bb
(ns scripts.assurance.verify-checkpoint
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def checkpoint-files
  {:sew-v1x-deployment-era
   "docs/assurance/checkpoints/sew-v1x-deployment-era.edn"
   :sew-v1x-appeal-bond-correction-2026-07-31
   "docs/assurance/checkpoints/sew-v1x-appeal-bond-correction-2026-07-31.edn"
   :sew-sep-2026-appeal-candidate
   "docs/assurance/checkpoints/sew-sep-2026-appeal-candidate.edn"})

(defn sha256 [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array 65536)]
        (loop [n (.read in buf)]
          (when-not (neg? n)
            (.update digest buf 0 n)
            (recur (.read in buf))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn fail! [errors]
  (doseq [error errors] (println "ASSURANCE CHECK FAILED" error))
  (System/exit 1))

(defn scenario-id [path]
  (:scenario-id (edn/read-string (slurp path))))

(defn verify-scenario [entry]
  (let [path (:path entry)
        errors (cond-> []
                 (not (.exists (io/file path)))
                 (conj {:path path :error :missing-file})
                 (and (.exists (io/file path))
                      (not= (:scenario/id entry) (scenario-id path)))
                 (conj {:path path :error :scenario-id-mismatch})
                 (and (.exists (io/file path))
                      (not= (:sha256 entry) (sha256 path)))
                 (conj {:path path :error :sha256-mismatch}))]
    errors))

(let [id (keyword (or (first *command-line-args*) ""))
      checkpoint-path (get checkpoint-files id)]
  (when-not checkpoint-path
    (fail! [{:error :unknown-checkpoint :checkpoint id
             :known (sort (keys checkpoint-files))}]))
  (let [checkpoint (edn/read-string (slurp checkpoint-path))
        scenarios (:scenarios checkpoint)
        ids (map :scenario/id scenarios)
        errors (vec (concat
                     (when (not= id (:checkpoint/id checkpoint))
                       [{:error :checkpoint-id-mismatch}])
                     (when (not= (count ids) (count (distinct ids)))
                       [{:error :duplicate-scenario-id}])
                     (mapcat verify-scenario scenarios)))]
    (if (seq errors)
      (fail! errors)
      (do
        (println "ASSURANCE CHECK PASS" id)
        (println "scenario-count" (count scenarios))
        (println "status" (:status checkpoint))
        (println "classification" (:classification checkpoint))))))
