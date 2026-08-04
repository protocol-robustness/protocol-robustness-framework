(ns resolver-sim.commands.benchmark-conclusion
  "Deterministic, scope-bounded conclusion projection for a benchmark bundle."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.coverage :as coverage])
  (:import [java.math BigInteger]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop [read (.read stream buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur (.read stream buffer))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- outcome-counts [claim-results]
  (merge {:pass 0 :fail 0 :inconclusive 0 :not-exercised 0 :not-implemented 0}
         (frequencies (map :claim/outcome claim-results))))

(defn- classify [evidence]
  (let [metrics (:metrics evidence)
        manifest (:benchmark evidence)
        required (set (or (:benchmark/required-claims manifest)
                          (coverage/claim-ids manifest)))
        claims (filter #(required (:claim/id %)) (:claim-results evidence))
        counts (outcome-counts claims)
        required-complete? (= (count required) (count (set (map :claim/id claims))))
        invariant-summary (:invariant-summary evidence)
        scenarios-pass? (= (:total metrics) (:passed metrics))
        invariants-pass? (= (:total-checks invariant-summary)
                            (:passed-checks invariant-summary))]
    (cond
      (or (not scenarios-pass?)
          (pos? (:fail counts))
          (not invariants-pass?)) [:fail "scenario-claim-or-invariant-failed" counts]
      (or (not required-complete?)
          (pos? (:inconclusive counts))
          (pos? (:not-exercised counts))
          (pos? (:not-implemented counts))) [:inconclusive "required-claim-not-conclusively-evaluated" counts]
      :else [:pass "all-scenarios-and-required-claims-passed" counts])))

(defn write! [context evidence]
  (let [file (io/file (str (:benchmark/evidence-file context)))
        [outcome reason claims] (classify evidence)
        metrics (:metrics evidence)
        invariants (:invariant-summary evidence)
        manifest (:benchmark evidence)
        required (set (or (:benchmark/required-claims manifest)
                          (coverage/claim-ids manifest)))
        value {"schema_version" "benchmark-conclusion.v1"
               "run_id" (:run/id context)
               "benchmark" {"id" (str (:benchmark/id manifest))
                            "status" (name (or (:benchmark/status manifest) :unknown))
                            "manifest_source" (get-in evidence [:run/manifest :benchmark/manifest-source])}
               "command_status" "completed"
               "outcome" (name outcome)
               "reason" reason
               "scenarios" {"total" (:total metrics) "passed" (:passed metrics)
                            "failed" (- (or (:total metrics) 0) (or (:passed metrics) 0))}
               "claims" {"required" (count required) "passed" (:pass claims)
                         "failed" (:fail claims) "inconclusive" (:inconclusive claims)
                         "not_exercised" (:not-exercised claims)
                         "not_implemented" (:not-implemented claims)}
               "invariants" {"total" (:total-checks invariants) "passed" (:passed-checks invariants)
                             "failed" (- (or (:total-checks invariants) 0) (or (:passed-checks invariants) 0))}
               ;; Two-commitment model: :evidence/hash is the SEMANTIC, reproducible
               ;; bundle root (the substantive commitment a verifier checks), while
               ;; file_sha256 is an exact-instance TRANSPORT checksum proving only
               ;; that this artifact file has not changed. The file sha is NOT the
               ;; benchmark outcome identity and is NOT expected to match across an
               ;; original and an independently reproduced run.
               "evidence" {"path" "benchmark/evidence/evidence.edn"
                           "hash" (get evidence :evidence/hash)
                           "file_sha256" (sha256 file)
                           "bytes" (.length file)}
               "scope" {"statement" "Declared benchmark claims passed only for the executed inputs."
                        "does_not_establish" ["unexercised claims" "protocol-wide safety"]}}
        target (io/file (str (:benchmark/conclusion-file context)))
        temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str value :indent true))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    value))
