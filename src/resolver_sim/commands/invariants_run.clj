(ns resolver-sim.commands.invariants-run
  "Run the canonical invariant registry suite and persist the full execution
   as a structured, verifiable run root.

   Output:
     run-plan.json           — expected scenario IDs and registry snapshot
     scenario-results.json   — compact index (one entry per scenario, no detail payload)
     details/<id>.json       — per-scenario detail artifact (hash-bound)
     completion.json         — binds plan and results

   Usage:  clojure -M:cli/sew invariants run --run-root DIR"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.io.scenario-runner :as sr]
            [resolver-sim.hash.canonical :as hc]))

(defn- registry-scenario-ids
  []
  (let [entries @(requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/all-scenarios)]
    (sort (map (fn [entry]
                 (let [s (if (vector? entry) (second entry) entry)]
                   (or (:scenario-id s) (str (first entry)))))
               entries))))

(defn- sha256-file
  [f]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (java.io.FileInputStream. f)]
      (loop [buf (byte-array 8192)]
        (let [n (.read in buf)]
          (when (pos? n)
            (.update digest buf 0 n)
            (recur buf)))))
    (apply str (map (fn [b] (format "%02x" (bit-and b 0xff))) (.digest digest)))))

(defn- safe-filename
  "Produce a safe filename from a scenario ID. Returns nil for unacceptable IDs."
  [id]
  (when (and (seq id) (not (re-find #"[/\\]" id)))
    (str/replace id #"[^a-zA-Z0-9._-]" "_")))

(defn- write-json
  [root path data]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f (json/write-str data :escape-slash false :escape-unicode false))))

(defn run
  [{:keys [run-root protocol] :as opts}]
  (let [protocol-id (or protocol "sew-v1")]
    (when-not run-root
      (println "Usage: invariants run --run-root DIR")
      {:exit-code 2 :message "Missing --run-root"})

    (let [root-dir (io/file run-root)]
      (when (and (.exists root-dir) (seq (.list root-dir)))
        (println (str "Run root already exists and is not empty: " run-root))
        {:exit-code 1 :message "Run root not empty"})
      (io/make-parents root-dir))

    (let [scenario-ids (registry-scenario-ids)
          plan {:plan/version 1
                :plan/kind "invariant-registry-suite"
                :plan/protocol protocol-id
                :plan/timestamp (str (java.time.Instant/now))
                :plan/expected-scenario-ids (vec scenario-ids)
                :plan/scenario-count (count scenario-ids)
                :plan/hash (hc/hash-with-intent
                            {:hash/intent :evidence-record}
                            (pr-str {:scenario-ids scenario-ids
                                     :protocol protocol-id}))}]
      (write-json run-root "run-plan.json" plan)

      (println (str "Invariant registry suite → " run-root))
      (println (str "  Expected: " (count scenario-ids) " scenarios"))

      (let [summary (try (sr/run-registry-suite {:protocol protocol-id})
                         (catch Exception e
                           (println (str "  Warning: run-registry-suite error: " (.getMessage e)))
                           {:results []}))
            results (:results summary)
            passed (filterv #(= :pass (:outcome %)) results)
            failed (filterv #(= :fail (:outcome %)) results)
            xfailed (filterv #(= :xfail (:outcome %)) results)
            unknown (filterv #(not ((into #{} [:pass :fail :xfail]) (:outcome %))) results)
            internal-tests (filterv #(= :tests (:scenario/type %)) results)
            root-dir-str run-root
            details-dir (str run-root "/details")
            detail-entries (mapv
                            (fn [r]
                              (let [sid (or (:scenario-id r) (:trace-id r) (:name r)
                                            (str "result-" (java.util.UUID/randomUUID)))
                                    outcome (if (:outcome r) (name (:outcome r))
                                                (if (:pass? r) "passed" "unknown"))
                                    halt-reason (when (:halt-reason r) (str (:halt-reason r)))
                                    fname (safe-filename sid)]
                                (when fname
                                  (let [detail-path (str "details/" fname ".json")
                                        detail-data {:scenario/id sid
                                                     :outcome outcome
                                                     :pass? (boolean (:pass? r))
                                                     :halt-reason halt-reason}]
                                    (try
                                      (write-json root-dir-str detail-path detail-data)
                                      (catch Exception e
                                        (println (str "  Warning: failed to write detail for " sid ": " (.getMessage e)))
                                        (write-json root-dir-str detail-path
                                                    {:scenario/id sid :outcome outcome :error (.getMessage e)})))
                                    (let [f (io/file root-dir-str detail-path)]
                                      (when (.exists f)
                                        {:scenario-id sid
                                         :status outcome
                                         :detail-path detail-path
                                         :detail-hash (sha256-file f)
                                         :detail-length (.length f)}))))))
                            results)
            detail-entries (vec (remove nil? detail-entries))
            passed-count (count passed)
            failed-count (count failed)
            xfailed-count (count xfailed)
            unknown-count (count unknown)
            results-index {:artifact-kind "invariant-run-results-index"
                           :schema-version 1
                           :plan-hash (:plan/hash plan)
                           :results detail-entries
                           :summary {:expected (count results)
                                     :passed passed-count
                                     :failed failed-count
                                     :xfailed xfailed-count
                                     :unknown unknown-count}}]
        (try
          (write-json run-root "scenario-results.json" results-index)
          (catch Exception e
            (println (str "  Warning: failed to write scenario-results.json: " (.getMessage e)))
            (write-json run-root "scenario-results.json" {:error (.getMessage e) :status "write-failed"})))

        (println (str "  Passed: " passed-count "/" (count results)
                      "  Failed: " failed-count "  XFAIL: " xfailed-count
                      "  Unknown: " unknown-count))

        (let [completion {:version 1
                          :kind "invariant-registry-suite"
                          :timestamp (str (java.time.Instant/now))
                          :plan-hash (:plan/hash plan)
                          :passed-count passed-count
                          :failed-count failed-count
                          :xfailed-count xfailed-count
                          :unknown-count unknown-count
                          :total-count (count results)
                          :status (if (and (zero? failed-count)
                                           (zero? xfailed-count)
                                           (zero? unknown-count))
                                    "passed" "failed")}]
          (write-json run-root "completion.json" completion)
          (println (str "  Status: " (:status completion)))
          (println "  Completion: written")
          (when (pos? (+ failed-count xfailed-count))
            (println "  Failed scenarios:")
            (doseq [e detail-entries
                    :when (#{"failed" "xfailed"} (:status e))]
              (println (str "    " (:scenario-id e) "  " (:detail-path e)))))
          {:exit-code (if (and (zero? failed-count) (zero? xfailed-count)) 0 1)
           :message (str "Invariant suite: " passed-count "/" (count results) " passed")
           :run-root run-root})))))

