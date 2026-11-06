(ns resolver-sim.validation.runtime-report
  "Versioned file envelope for findings emitted by runtime validation.

   This namespace persists checks that a validator has already executed. It
   does not execute validators or reinterpret domain-specific severity fields."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.atomic-file :as atomic-file])
  (:import [java.io PushbackReader StringReader]
           [java.time Instant]
           [java.util UUID]))

(def schema :prf/runtime-validation-report.v1)
(def ^:private statuses #{:passed :failed :warning})
(def ^:private severities #{:critical :warning :info})
(def ^:private report-fields
  #{:report/schema :report/id :report/generated-at :report/root :run/id
    :source/revision :validator/id :execution/roots :checks :status :storage})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :report/schema schema))))

(defn- valid-source-revision? [value]
  (and (string? value) (re-matches #"(?:[0-9a-f]{40}|[0-9a-f]{64})" value)))

(defn- valid-root? [value]
  (and (string? value) (re-matches #"sha256:[0-9a-f]{64}" value)))

(defn report-root [report]
  (hash-ref/sha256-ref
   (canonical/domain-hash "prf-runtime-validation-report-v1"
                          (dissoc report :report/root))))

(defn- check-problems [check]
  (cond-> []
    (not (and (map? check) (keyword? (:check/id check))))
    (conj :invalid-check-id)
    (not (statuses (:status check)))
    (conj :invalid-check-status)
    (not (severities (:severity check :warning)))
    (conj :invalid-check-severity)
    (and (:evidence-root check) (not (valid-root? (:evidence-root check))))
    (conj :invalid-evidence-root)
    (and (:evidence-roots check)
         (not (and (sequential? (:evidence-roots check))
                   (every? valid-root? (:evidence-roots check)))))
    (conj :invalid-evidence-roots)
    (and (:message check) (not (string? (:message check))))
    (conj :invalid-message)))

(defn report-status [checks]
  (cond
    (some #(= :failed (:status %)) checks) :failed
    (some #(= :warning (:status %)) checks) :warning
    :else :passed))

(defn build-report
  "Build a report from completed runtime checks.

   Required opts: :run/id, full :source/revision commit id, :validator/id,
   and :checks. Optional :execution/roots bind the report to existing evidence
   roots. Severity uses validation.suite-result's generic vocabulary;
   source-specific severity data belongs under an explicitly named source
   field."
  [{:keys [checks generated-at] :as opts}]
  (let [run-id (:run/id opts)
        source-revision (:source/revision opts)
        validator-id (:validator/id opts)
        roots (or (:execution/roots opts) [])]
    (when-not (and (string? run-id) (not (str/blank? run-id)))
      (fail! "Runtime report requires a nonblank run id" {:reason :missing-run-id}))
    (when-not (valid-source-revision? source-revision)
      (fail! "Runtime report requires a full immutable source commit id"
             {:reason :invalid-source-revision}))
    (when-not (and (string? validator-id) (not (str/blank? validator-id)))
      (fail! "Runtime report requires a validator id" {:reason :missing-validator-id}))
    (when-not (and (vector? checks) (seq checks))
      (fail! "Runtime report requires a nonempty vector of executed checks"
             {:reason :missing-checks}))
    (let [problems (vec (mapcat (fn [[index check]]
                                  (map #(hash-map :index index :reason %) (check-problems check)))
                                (map-indexed vector checks)))]
      (when (seq problems)
        (fail! "Runtime report contains invalid checks"
               {:reason :invalid-checks :problems problems})))
    (when-not (= (count checks) (count (set (map :check/id checks))))
      (fail! "Runtime report check ids must be unique" {:reason :duplicate-check-id}))
    (when-not (and (vector? roots) (every? valid-root? roots))
      (fail! "Execution roots must be a vector of canonical SHA-256 references"
             {:reason :invalid-execution-roots}))
    (let [base {:report/schema schema
                :report/id (str (UUID/randomUUID))
                :report/generated-at (or generated-at (str (Instant/now)))
                :run/id run-id
                :source/revision source-revision
                :validator/id validator-id
                :execution/roots roots
                :checks checks
                :status (report-status checks)
                :storage {:format :edn :encryption :none}}]
      (assoc base :report/root (report-root base)))))

(defn validate-report
  "Return {:valid? ... :errors [...]} for a persisted V1 report."
  [report]
  (let [errors (cond-> []
                 (not (map? report)) (conj :not-a-map)
                 (not= schema (:report/schema report)) (conj :unsupported-schema)
                 (and (map? report) (not= report-fields (set (keys report))))
                 (conj :invalid-report-fields)
                 (not (and (string? (:report/id report))
                           (not (str/blank? (:report/id report))))) (conj :invalid-report-id)
                 (not (and (string? (:report/generated-at report))
                           (not (str/blank? (:report/generated-at report)))))
                 (conj :invalid-generated-at)
                 (not (and (string? (:run/id report)) (not (str/blank? (:run/id report)))))
                 (conj :invalid-run-id)
                 (not (valid-source-revision? (:source/revision report))) (conj :invalid-source-revision)
                 (not (and (string? (:validator/id report))
                           (not (str/blank? (:validator/id report))))) (conj :invalid-validator-id)
                 (not (and (vector? (:checks report)) (seq (:checks report)))) (conj :invalid-checks)
                 (not (and (vector? (:execution/roots report))
                           (every? valid-root? (:execution/roots report)))) (conj :invalid-execution-roots)
                 (not= (:status report) (report-status (:checks report))) (conj :status-mismatch)
                 (not= {:format :edn :encryption :none} (:storage report)) (conj :unsupported-storage-profile)
                 (some seq (map check-problems (:checks report))) (conj :invalid-checks)
                 (not= (count (:checks report)) (count (set (map :check/id (:checks report)))))
                 (conj :duplicate-check-id)
                 (not (valid-root? (:report/root report))) (conj :invalid-report-root)
                 (not= (:report/root report)
                       (try (report-root report) (catch Exception _ nil)))
                 (conj :report-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn write-report!
  "Validate and atomically write a report as EDN. Returns the normalized path.

   The storage profile deliberately declares encryption :none. A future
   encrypted envelope must be versioned separately from the semantic report."
  [path report]
  (let [{:keys [valid? errors]} (validate-report report)]
    (when-not valid?
      (fail! "Refusing to persist invalid runtime report"
             {:reason :invalid-report :errors errors}))
    (str (atomic-file/write-text! path (str (pr-str report) "\n") {:replace? false}))))

(defn read-report [path]
  (let [report (try
                 (with-open [reader (PushbackReader. (StringReader. (slurp (io/file (str path)))))]
                   (let [eof (Object.)
                         value (edn/read {:eof eof} reader)
                         trailing (edn/read {:eof eof} reader)]
                     (when (identical? eof value)
                       (fail! "Runtime report file is empty" {:reason :invalid-report-edn}))
                     (when-not (identical? eof trailing)
                       (fail! "Runtime report file contains trailing EDN values"
                              {:reason :trailing-report-data}))
                     value))
                 (catch Exception error
                   (if (= :trailing-report-data (:reason (ex-data error)))
                     (throw error)
                     (fail! "Runtime report file is not valid EDN"
                            {:reason :invalid-report-edn :cause (.getMessage error)}))))
        validation (validate-report report)]
    (when-not (:valid? validation)
      (fail! "Persisted runtime report failed validation"
             {:reason :invalid-report :errors (:errors validation)}))
    report))
