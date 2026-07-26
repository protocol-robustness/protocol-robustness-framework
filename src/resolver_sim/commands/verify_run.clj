(ns resolver-sim.commands.verify-run
  "Verify a completed canonical invariant registry suite run root.

   Checks:
   - run-plan.json exists, parses, and has the expected schema
   - scenario-results.json accounts for every expected scenario exactly once
   - Detail bindings: each result's detail-path exists, hash matches, length matches
   - No unindexed files in details/
   - All paths beneath the run root
   - Aggregate counts reconcile
   - completion.json exists and matches the plan

   Usage:  clojure -M:cli/sew verify-run --run-root DIR"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]))

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

(defn- slurp-json
  [path]
  (when (.exists (io/file path))
    (try (json/read-str (slurp path) :key-fn keyword) (catch Exception _ nil))))

(defn- paths-under-root?
  [root paths]
  (let [root-path (.getAbsolutePath (io/file root))]
    (every? (fn [p]
              (str/starts-with? (.getAbsolutePath (io/file root p)) root-path))
            paths)))

(defn- verify-plan
  [run-root]
  (let [plan (slurp-json (str run-root "/run-plan.json"))]
    (cond
      (nil? plan) ["run-plan.json missing or unparseable"]
      (not= (:kind plan) "invariant-registry-suite") [(str "Unexpected plan kind: " (:kind plan))]
      (not (:expected-scenario-ids plan)) ["run-plan.json missing expected-scenario-ids"]
      (not (vector? (:expected-scenario-ids plan))) ["expected-scenario-ids must be a vector"]
      (not= (count (:expected-scenario-ids plan))
            (count (distinct (:expected-scenario-ids plan)))) ["Duplicate scenario IDs in plan"]
      :else [])))

(defn- verify-results-index
  [run-root expected-ids]
  (let [results (slurp-json (str run-root "/scenario-results.json"))]
    (cond
      (nil? results) ["scenario-results.json missing or unparseable"]
      (not= (:artifact-kind results) "invariant-run-results-index")
      [(str "Unexpected artifact-kind: " (:artifact-kind results))]
      (not (:results results)) ["scenario-results.json missing results"]
      :else
      (let [scenarios (:results results)
            result-ids (mapv :scenario-id scenarios)
            expected-set (set expected-ids)
            result-set (set result-ids)
            missing-ids (remove result-set expected-ids)
            unexpected-ids (remove expected-set result-ids)
            duplicates (->> result-ids frequencies (filter (fn [[_ n]] (> n 1))) (map first))
            errors (atom [])]
        (when (seq missing-ids)
          (swap! errors conj (str "Missing results for scenarios: " (pr-str missing-ids))))
        (when (seq unexpected-ids)
          (swap! errors conj (str "Unexpected scenario results: " (pr-str unexpected-ids))))
        (when (seq duplicates)
          (swap! errors conj (str "Duplicate scenario results: " (pr-str duplicates))))
        (when-not (= (count scenarios) (count expected-ids))
          (swap! errors conj (str "Result count " (count scenarios) " does not match expected " (count expected-ids))))

        ;; Verify detail bindings
        (doseq [s scenarios]
          (let [sid (:scenario-id s)
                dpath (:detail-path s)
                dhash (:detail-hash s)
                dlen (:detail-length s)
                full-path (str run-root "/" dpath)]
            (cond
              (not dpath) (swap! errors conj (str sid " missing detail-path"))
              (not dhash) (swap! errors conj (str sid " missing detail-hash"))
              (not dlen)  (swap! errors conj (str sid " missing detail-length"))
              :else
              (let [f (io/file full-path)]
                (cond
                  (not (.exists f))
                  (swap! errors conj (str sid " detail file not found: " dpath))
                  (not= (.length f) dlen)
                  (swap! errors conj (str sid " detail length mismatch: expected " dlen " got " (.length f)))
                  :else
                  (let [actual-hash (sha256-file f)]
                    (when (not= actual-hash dhash)
                      (swap! errors conj (str sid " detail hash mismatch: expected " dhash " got " actual-hash)))))))))
        @errors))))

(defn- verify-counts
  [run-root expected-count]
  (let [results (slurp-json (str run-root "/scenario-results.json"))
        completion (slurp-json (str run-root "/completion.json"))
        errors (atom [])]
    (when results
      (let [summary (:summary results)
            total (:expected summary 0)
            passed (:passed summary 0)
            failed (:failed summary 0)
            xfailed (:xfailed summary 0)
            unknown (:unknown summary 0)]
        (when (not= total expected-count)
          (swap! errors conj (str "scenario-results.json total " total " != expected " expected-count)))
        (when (not= (+ passed failed xfailed unknown) total)
          (swap! errors conj (str "passed+failed+xfailed+unknown != total: "
                                  passed "+" failed "+" xfailed "+" unknown " != " total)))))
    (when completion
      (let [total (:total-count completion)]
        (when (not= total expected-count)
          (swap! errors conj (str "completion.json total " total " != expected " expected-count)))))
    @errors))

(defn- verify-completion
  [run-root plan]
  (let [completion (slurp-json (str run-root "/completion.json"))]
    (cond
      (nil? completion) ["completion.json missing or unparseable"]
      (not= (:kind completion) "invariant-registry-suite") [(str "Unexpected completion kind: " (:kind completion))]
      (not (:status completion)) ["completion.json missing status"]
      (and plan (not= (:plan-hash completion) (:hash plan))) ["completion.json plan-hash does not match run-plan.json"]
      (not (integer? (:total-count completion))) ["completion.json missing or invalid total-count"]
      :else [])))

(defn- verify-no-unindexed-details
  [run-root indexed-paths]
  (let [details-dir (io/file run-root "details")]
    (if-not (.exists details-dir)
      []
      (let [present-files (sort (map (fn [f] (str "details/" (.getName f)))
                                     (filter (fn [^java.io.File f] (.isFile f)) (.listFiles details-dir))))
            indexed-set (set indexed-paths)
            unindexed (remove indexed-set present-files)]
        (when (seq unindexed)
          [(str "Unindexed detail files: " (pr-str unindexed))])))))

(defn- verify-paths
  [run-root indexed-paths]
  (let [required ["run-plan.json" "scenario-results.json" "completion.json"]
        all-paths (concat required indexed-paths)]
    (if (paths-under-root? run-root all-paths)
      []
      ["Artifacts are not all within the run root"])))

(defn run
  [{:keys [run-root] :as opts}]
  (if-not run-root
    (do (println "Usage: verify-run --run-root DIR")
        {:exit-code 2 :message "Missing --run-root"})
    (let [root-dir (io/file run-root)]
      (if-not (.exists root-dir)
        (do (println (str "Run root not found: " run-root))
            {:exit-code 1 :message "Run root not found"})
        (let [errors (atom [])]

          (println "Verifying run plan...")
          (let [plan-errs (verify-plan run-root)]
            (when (seq plan-errs)
              (doseq [e plan-errs] (swap! errors conj e))))

          (let [plan (slurp-json (str run-root "/run-plan.json"))
                expected-ids (:expected-scenario-ids plan)
                expected-count (count expected-ids)]

            (println "Verifying scenario results...")
            (let [result-errs (verify-results-index run-root expected-ids)]
              (when (seq result-errs)
                (doseq [e result-errs] (swap! errors conj e))))

            (println "Verifying counts...")
            (let [count-errs (verify-counts run-root expected-count)]
              (when (seq count-errs)
                (doseq [e count-errs] (swap! errors conj e))))

            (println "Verifying completion...")
            (let [completion-errs (verify-completion run-root plan)]
              (when (seq completion-errs)
                (doseq [e completion-errs] (swap! errors conj e))))

            (println "Verifying detail bindings...")
            (let [results (slurp-json (str run-root "/scenario-results.json"))
                  indexed-paths (mapv :detail-path (:results results))
                  path-errs (verify-paths run-root indexed-paths)]
              (when (seq path-errs)
                (doseq [e path-errs] (swap! errors conj e))))

            (println "Checking for unindexed details...")
            (let [results (slurp-json (str run-root "/scenario-results.json"))
                  indexed-paths (mapv :detail-path (:results results))
                  unindexed-errs (verify-no-unindexed-details run-root indexed-paths)]
              (when (seq unindexed-errs)
                (doseq [e unindexed-errs] (swap! errors conj e))))

            (if (empty? @errors)
              (do (println "VERIFY-RUN PASSED")
                  {:exit-code 0 :message "Verification passed"})
              (do (println "VERIFY-RUN FAILED")
                  (doseq [e @errors] (println (str "  ✗ " e)))
                  {:exit-code 1
                   :message (str (count @errors) " verification error(s)")
                   :errors @errors}))))))))
