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
      (= check :allocation-domain-invariants)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": " (:constituent-count result) " constituent checks passed"))
        (do
          (println (str "[FAIL] " check ": " (:constituent-count result) " constituent checks, some failed"))
          (doseq [c (:checks result)]
            (when-not (:holds? c)
              (println (str "  - " (:name c) ": " (:violations c)))))))
      (= check :expected-results-recompute)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": " (:vector-count result) " vectors recomputed and match expected outputs"))
        (do
          (println (str "[FAIL] " check ": " (:vector-count result) " vectors, some mismatches"))
          (doseq [m (:mismatches result)]
            (println (str "  - " (:vector-id m) ": " (:path m))))))
      (= check :intent-coverage)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": " (:defined-intents result) " intents defined, "
                      (:exercised-intents result) " exercised"))
        (do
          (println (str "[FAIL] " check ": "
                        (count (:required-but-unexercised result))
                        " required intents not exercised"))
          (doseq [i (:required-but-unexercised result)]
            (println (str "  - " i " is :required but not exercised")))))
      (= check :contract-case-coverage)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": all contract domains have test vector coverage"))
        (do
          (println (str "[FAIL] " check ": missing contract case coverage"))
          (doseq [m (:missing-cases result)]
            (println (str "  - " m)))))
      (= check :negative-corpus)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": all " (:fixture-count result)
                      " negative fixtures correctly rejected"))
        (do
          (println (str "[FAIL] " check ": " (:fixture-count result)
                        " fixtures, some not correctly rejected"))
          (doseq [r (:results result)]
            (when (= :fail (:status r))
              (println (str "  - " (:fixture r) " [:" (:fixture-type r) "]: "
                            "expected " (:expected-reasons r)
                            " observed " (:observed-reasons r)))))))
      (= check :order-independence)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": corpus enumeration is order-independent across "
                      (:orderings-tested result) " orderings"))
        (do
          (println (str "[FAIL] " check ": ordering-dependent results detected"))
          (doseq [d (:differences result)]
            (println (str "  - " d)))))
      (= check :verification-fixed-point)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": verification report survives canonical round-trip ("
                      (:vector-count result) " vectors, hash: " (:semantic-hash result) ")"))
        (do
          (println (str "[FAIL] " check ": canonical round-trip failed ("
                        (:vector-count result) " vectors)"))
          (doseq [m (:mismatched result)]
            (println (str "  - " m)))))
      (= check :verifier-registry-consistency)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": configuration and transition verifier roots match"))
        (do
          (println (str "[FAIL] " check ": configuration and transition verifier roots diverge"))
          (when-let [err (:error result)]
            (println (str "  - " err)))))
      (= check :claim-registry-closure)
      (if (= :pass (:status result))
        (println (str "[PASS] " check ": "
                      (:evaluator-count result) " evaluators, "
                      (:definition-count result) " definitions, all closure-consistent"))
        (do
          (println (str "[FAIL] " check ": registry closure violations"))
          (when-let [e (:evaluators-without-definitions result)]
            (doseq [eid e]
              (println (str "  - evaluator without definition: " eid))))
          (when-let [d (:definitions-without-evaluators result)]
            (doseq [did d]
              (println (str "  - definition without evaluator: " did))))
          (when-let [d (:duplicate-definitions result)]
            (doseq [did d]
              (println (str "  - duplicate definitions: " did))))
          (when-let [s (:schema-errors result)]
            (doseq [se s]
              (println (str "  - " (:claim-id se) " missing field: " (:missing-field se)))))))
      (= check :corpus)
      (let [manifest (:manifest result)]
        (if (= :pass (:status result))
          (do
            (println (str "[PASS] " check ": CORPUS VERIFIED"))
            (println (format "  packs                  %d" (:corpus/packs manifest)))
            (println (format "  benchmarks             %d" (:corpus/benchmark-count manifest)))
            (println (format "  hash intents           %d" (:corpus/hash-intent-count manifest)))
            (println (format "  semantic checks        %d" (:semantic-checks result)))
            (println (format "  verification-root      %s" (:verification-root result))))
          (do
            (println (str "[FAIL] " check ": not all checks passed"))
            (doseq [[k v] (:corpus/verification-checks manifest)]
              (when (not= :pass (:status v))
                (println (str "  - " k ": " (:status v))))))))
      :else
      (println (str "[UNKNOWN] " check ": " check)))))

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
  (println "  11. Running allocation domain invariants aggregate check...")
  (try
    (format-result (cv/check-allocation-domain-invariants))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  12. Running claim registry closure check...")
  (try
    (format-result (cv/check-claim-registry-closure))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  13. Running expected-results-recompute check...")
  (try
    (format-result (cv/check-expected-results-recompute))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "P1 — Coverage verification:")
  (println "  14. Running intent coverage check...")
  (try
    (format-result (cv/check-intent-coverage))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  15. Running contract case coverage check...")
  (try
    (format-result (cv/check-contract-case-coverage))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  16. Running verifier registry consistency check...")
  (try
    (format-result (cv/check-verifier-registry-consistency))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  17. Running negative corpus check...")
  (try
    (format-result (cv/check-negative-corpus))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  18. Running order independence check...")
  (try
    (format-result (cv/check-order-independence))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  19. Running verification fixed-point check...")
  (try
    (format-result (cv/check-verification-fixed-point))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "  20. Producing committed corpus manifest / root...")
  (try
    (format-result (cv/check-corpus))
    (catch Exception e
      (println (str "[ERROR] " (.getMessage e)))))
  (println)
  (println "=== Corpus Verification Complete ===")
  (System/exit 0))
