(ns resolver-sim.allocation.proof-artifact-verify
  "Strict Gate-A artifact/proof-file verifier.

   This command deliberately does not claim SP1 verification or issue a receipt.
   It independently checks persisted JSON parsing, public-value/artifact identity,
   and the content-addressed Core-proof envelope before a verifier service may
   consume the bundle."
  (:require [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]))

(def default-artifact
  "results/allocation/a-vs-b-plus-c/realized-statement/sp1-proof-artifact.json")

(defn- artifact-dir [path]
  (.getParentFile (java.io.File. path)))

(defn verify! [path]
  (let [raw (try (slurp path) (catch Exception _ nil))
        ingested (admission/ingest-proof-artifact-json raw)
        proof-file-ok? (and (:valid? ingested)
                            (admission/verify-proof-file! (artifact-dir path)
                                                          (:artifact ingested)))
        input-result (when (:valid? ingested)
                       (persisted/verify-persisted-input path (:artifact ingested)))]
    {:artifact path
     :strict-json-ingestion (:valid? ingested)
     :artifact-identity (:valid? ingested)
     :proof-file-hash proof-file-ok?
     :persisted-input-statement-recomputed (:valid? input-result)
     :persisted-input-sha256 (:input-sha256 input-result)
     :statement-root (get-in ingested [:artifact :statement/root])
     :proof-hash (get-in ingested [:artifact :proof/sha256])
     :valid? (and (:valid? ingested) proof-file-ok? (:valid? input-result))
     :reason (or (:reason ingested)
                 (when-not proof-file-ok? :proof-file-hash-mismatch)
                 (:reason input-result))}))

(defn -main [& args]
  (let [result (verify! (or (first args) default-artifact))]
    (println (pr-str result))
    (when-not (:valid? result)
      (System/exit 1))))
