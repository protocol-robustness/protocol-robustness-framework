(ns scripts.corpus-verification
  "Corpus-verification scenario round: run comprehensive checks for the benchmark corpus integrity.
   Includes P0 P1 and P2 checks."
  (:require [resolver-sim.benchmark.corpus-validation :as cv]
            [resolver-sim.hash.canonical :as canonical]))

(defn- format-result [result]
  (let [check (:check result)]
    (cond
      (= check :all-intents-have-contract-fields)
      (let [issue-count (:issue-count result)]
        (if (zero? issue-count)
          (println (str "[PASS] " check ": all " (count canonical/hash-intents) " intents have valid contracts"))
          (do
            (println (str "[FAIL] " check ": " issue-count " issues found"))
            (doseq [issue (:issues result)]
              (println (str "  - " (dissoc issue :issues)))))))
      (= check :aggregate)
      (if (:valid? result)
        (println (str "[PASS] " check ": yield aggregate invariants valid"))
        (do
          (println (str "[FAIL] " check ": violations found"))
          (doseq [v (:violations result)]
            (println (str "  - " v)))))
      (= check :cap-respecting)
      (if (:holds? result)
        (println (str "[PASS] " check ": all cap constraints respected"))
        (do
          (println (str "[FAIL] " check ": violations found"))
          (doseq [v (:violations result)]
            (println (str "  - " v)))))
      (= check :conservation)
      (if (:holds? result)
        (println (str "[PASS] " check ": allocations conserve amounts"))
        (do
          (println (str "[FAIL] " check ": violations found"))
          (doseq [v (:violations result)]
            (println (str "  - " v)))))
      (= check :reference-closure)
      (if (:valid? result)
        (println (str "[PASS] " check ": all references close"))
        (do
          (println (str "[FAIL] " check ": dangling/ambiguous refs found"))
          (when-let [err (:error result)]
            (println (str "  - " err)))))
      (= check :no-orphan-artifacts)
      (if (empty? (:orphan-paths result))
        (println (str "[PASS] " check ": no orphan artifacts"))
        (do
          (println (str "[FAIL] " check ": orphans found"))
          (doseq [p (:orphan-paths result)]
            (println (str "  - " p)))))
      (= check :hash-integrity)
      (if (empty? (:mismatched result))
        (println (str "[PASS] " check ": all hashes match"))
        (do
          (println (str "[FAIL] " check ": mismatched hashes"))
          (doseq [m (:mismatched result)]
            (println (str "  - " m)))))
      (= check :canonical-fixed-point)
      (if (zero? (:failures result))
        (println (str "[PASS] " check ": all artifacts stable"))
        (do
          (println (str "[FAIL] " check ": " (:failures result) " failures"))))
      (= check :unique-identities)
      (if (empty? (:duplicates result))
        (println (str "[PASS] " check ": no ID collisions"))
        (do
          (println (str "[FAIL] " check ": collisions found"))
          (doseq [d (:duplicates result)]
            (println (str "  - " d)))))
      (= check :schema-version-support)
      (if (empty? (:unsupported-versions result))
        (println (str "[PASS] " check ": all versions supported"))
        (do
          (println (str "[FAIL] " check ": unsupported versions"))
          (doseq [v (:unsupported-versions result)]
            (println (str "  - " v)))))
      (= check :corpus)
      (let [status (:status result)]
        (if (= status :passed)
          (println (str "[PASS] corpus validation: " (:packs result) " packs, " (:benchmarks result) " benchmarks"))
          (do
            (println (str "[FAIL] corpus validation failed"))
            (doseq [err (:errors result)]
              (println (str "  - " err)))))
      :else
      (println (str "[UNKNOWN] " check ": " check))))))

(defn -main [& _args]
  (println "=== Corpus Verification Checks (v2) ===")
  (println)
  (println "P0 — Referential integrity:")
  (println "  1. Validating intent registry contracts...")
  (try
    (format-result (cv/check-all-intents-have-contract-fields))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "P0 — Reference closure:")
  (println "  2. Checking reference closure...")
  (try
    (format-result (cv/check-reference-closure))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  3. Checking for orphan artifacts...")
  (try
    (format-result (cv/check-no-orphan-artifacts))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  4. Checking hash integrity...")
  (try
    (format-result (cv/check-hash-integrity))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  5. Checking canonical fixed-point...")
  (try
    (format-result (cv/check-canonical-fixed-point))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  6. Checking unique identities...")
  (try
    (format-result (cv/check-unique-identities))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  7. Checking schema version support...")
  (try
    (format-result (cv/check-schema-version-support))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "P0 — Expected-result correctness:")
  (println "  8. Running yield aggregate invariant check...")
  (try
    (format-result (cv/check-aggregate))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  9. Running cap-respecting check...")
  (try
    (format-result (cv/check-cap-respecting))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  10. Running conservation check...")
  (try
    (format-result (cv/check-conservation))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  11. Validating corpus structure...")
  (try
    (let [result (cv/validate-corpus!)]
      (println (str "[PASS] corpus validation: " (:packs result) " packs, " (:benchmarks result) " benchmarks"))
      (println "OK: corpus structure valid"))
    (catch Exception e
      (println (str "[ERROR] Corpus validation failed: " (.getMessage e)))))
  (println)
  (println "=== Corpus Verification Complete ===")
  (System/exit 0))