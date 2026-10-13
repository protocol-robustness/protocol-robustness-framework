(ns resolver-sim.db.temporal
  "XTDB temporal evidence adapter for long-horizon scenarios.

   This layer is intentionally write-side and analysis-side only.
   It must not influence deterministic transition semantics."
  (:require [resolver-sim.db.store               :as ss]
            [resolver-sim.protocols.protocol      :as proto])
  (:import [java.util Date UUID]))

(defn- sim-date [block-time]
  (if block-time (Date. (* 1000 (long block-time))) (Date.)))

(defn build-run-record
  [{:keys [run-id batch-id protocol suite-id scenario-id seed git-sha outcome metrics block-time]}]
  {:id         (or run-id (str (UUID/randomUUID)))
   :batch-id   (or batch-id :temporal-batch)
   :protocol-id (proto/protocol-id protocol)
   :suite-id   (or suite-id :temporal-suite)
   :scenario-id (or scenario-id :unknown-scenario)
   :seed       (long (or seed 0))
   :git-sha    (or git-sha "unknown")
   :outcome    (or outcome :unknown)
   :metrics    (or metrics {})
   :valid-from (sim-date block-time)})

(defn build-step-record
  [{:keys [run-id step-index action result time-before time-advance time-after projection-hash block-time]}]
  {:id              (str (UUID/randomUUID))
   :run-id          run-id
   :step-index      (long step-index)
   :action          (or action :unknown)
   :result          (or result :unknown)
   :time-before     (or time-before {})
   :time-advance    (or time-advance {})
   :time-after      (or time-after {})
   :projection-hash (or projection-hash "")
   :valid-from      (sim-date block-time)})

(defn build-invariant-record
  [{:keys [run-id step-index invariant holds? severity violations block-time]}]
  {:id         (str (UUID/randomUUID))
   :run-id     run-id
   :step-index (long step-index)
   :invariant  (or invariant :unknown)
   :holds?     (boolean holds?)
   :severity   (or severity :info)
   :violations (or violations [])
   :valid-from (sim-date block-time)})

(defn build-coverage-record
  [{:keys [run-id coverage block-time]}]
  {:id         (str (UUID/randomUUID))
   :run-id     run-id
   :coverage   (or coverage {})
   :valid-from (sim-date block-time)})

(defn- non-decreasing-valid-from?
  "True when :valid-from values are non-decreasing across records.
   Nil valid-from values are treated as now by builders, so this is defensive."
  [records]
  (or (empty? records)
      (every? true?
              (map (fn [[a b]]
                     (not (pos? (.compareTo ^Date (:valid-from a) ^Date (:valid-from b)))))
                   (partition 2 1 records)))))

(defn- assert-non-decreasing-valid-from!
  [records kind]
  (when-not (non-decreasing-valid-from? records)
    (throw (ex-info (str "non-decreasing valid-time violated for " kind)
                    {:error :non-decreasing-valid-time-violation
                     :kind kind}))))

(defn record-temporal-run!
  "Writes run + step + invariant + optional coverage docs.
   Safe with ds=nil (all writes no-op)."
  [ds {:keys [run steps invariants coverage]}]
  (let [run-rec (build-run-record run)
        run-id  (:id run-rec)
        steps*  (map #(build-step-record (assoc % :run-id run-id)) (or steps []))
        invs*   (map #(build-invariant-record (assoc % :run-id run-id)) (or invariants []))
        cov*    (when coverage (build-coverage-record (assoc coverage :run-id run-id)))]
    (assert-non-decreasing-valid-from! (vec steps*) :steps)
    (assert-non-decreasing-valid-from! (vec invs*) :invariants)
    (ss/insert-temporal-run! ds run-rec)
    (doseq [s steps*] (ss/insert-temporal-step! ds s))
    (doseq [i invs*] (ss/insert-temporal-invariant! ds i))
    (when cov* (ss/insert-temporal-coverage! ds cov*))
    {:run run-rec :steps (vec steps*) :invariants (vec invs*) :coverage cov*}))

(defn record-from-replay!
  "Temporal recorder callback for contract-model replay.
   Invoked at scenario terminal states when :temporal-evidence :recorder is set.

   OUTCOME BOUNDARY. `outcome` is the replay-kernel terminal outcome AFTER
   expected-error analysis — the outcome determined by `run-simulation-loop`
   (e.g. `:pass` for a nominal pass, `:fail` for an expected-error mismatch or
   an invariant halt). It is recorded at the kernel boundary and does NOT
   include the later `finalize-scenario-result` expectations/theory layer that
   `replay-events` applies after the kernel returns. The temporal `:outcome`
   field is therefore the kernel outcome, not necessarily the externally
   finalized scenario outcome."
  [ds temporal-cfg scenario-id outcome world metrics trace]
  (record-temporal-run!
   ds
   {:run {:run-id      (or (:run-id temporal-cfg) (str scenario-id "-run"))
          :batch-id    (or (:batch-id temporal-cfg) :temporal-batch)
          :protocol    (:protocol temporal-cfg)
          :suite-id    (or (:suite-id temporal-cfg) :temporal-suite)
          :scenario-id scenario-id
          :seed        (:seed temporal-cfg)
          :git-sha     (or (:git-sha temporal-cfg) "unknown")
          :outcome     outcome
          :metrics     metrics
          :block-time  (:block-time world)}
    :steps (map-indexed (fn [i e]
                          {:step-index i
                           :action (:action e)
                           :result (:result e)
                           :time-before (:time-before e {})
                           :time-advance {}
                           :time-after (or (:time-after e) {:block-ts (:time e)})
                           :projection-hash (:projection-hash e)
                           :block-time (:time e)})
                        trace)
    :invariants (mapcat (fn [i e]
                          (for [[k r] (:violations e)
                                :when (map? r)]
                            {:step-index i
                             :invariant k
                             :holds? (:holds? r)
                             :severity :time
                             :violations (:violations r)
                             :block-time (:time e)}))
                        (range)
                        trace)
    :coverage (:coverage temporal-cfg)}))

(defn summarize-boundary-outcomes
  "Pure helper for boundary cohorts.
   Input rows with keys: :offset and :result"
  [rows]
  (->> rows
       (group-by :offset)
       (into {}
             (map (fn [[offset rs]]
                    [offset {:n (count rs)
                             :ok (count (filter #(= :ok (:result %)) rs))
                             :rejected (count (filter #(= :rejected (:result %)) rs))}])))))

(defn summarize-drift-budget
  "Pure helper: reports budget violations.
   Input rows: {:module :drift :budget}"
  [rows]
  (let [violations (filter (fn [{:keys [drift budget]}] (> (long drift) (long budget))) rows)]
    {:n (count rows)
     :violations (count violations)
     :violation-rows (vec violations)}))

(defn summarize-determinism
  "Pure helper: groups by cohort and detects projection hash divergence.
   Input rows: {:cohort :projection-hash}"
  [rows]
  (->> rows
       (group-by :cohort)
       (into {}
             (map (fn [[c rs]]
                    (let [hashes (set (map :projection-hash rs))]
                      [c {:n (count rs)
                          :hashes hashes
                          :deterministic? (= 1 (count hashes))}]))))))
