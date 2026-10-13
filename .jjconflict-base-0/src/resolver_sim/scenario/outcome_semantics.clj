(ns resolver-sim.scenario.outcome-semantics
  "Shared outcome interpretation semantics used across reporting and suite evaluation."
  (:require [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.theory-result :as theory-result]))

(defn normalize-purpose [purpose]
  (keyword (or purpose "")))

(defn negative-test-purpose?
  "True when the scenario purpose indicates expected-failure / falsification semantics."
  [purpose]
  (= (normalize-purpose purpose) :theory-falsification))

(defn theory-label
  "Human label for metric-track theory status.

  Prefer `theory-result/result-display-label` when a full result map is available."
  ([status]
   (or (:label (defs/status-def status))
       (:label (defs/status-def :not-evaluated))
       "Not evaluated"))
  ([status result]
   (if result
     (theory-result/result-display-label result)
     (theory-label status))))

(defn theory-expected?
  "Whether a theory status is expected under a scenario purpose.
   A falsified claim is expected only for theory-falsification scenarios."
  [status purpose]
  (case status
    :not-evaluated true
    :not-falsified true
    :falsified     (negative-test-purpose? purpose)
    :inconclusive  false
    true))

(defn theory-status-ok?
  "Suite pass/fail semantics for metric-track theory status.
   :inconclusive is soft by default, optionally strict via require-conclusive?.

   Uses canonical :status on the theory result map."
  [status purpose {:keys [require-conclusive?] :or {require-conclusive? false}}]
  (case status
    nil            true
    :not-evaluated true
    :not-falsified true
    :falsified     (negative-test-purpose? purpose)
    :inconclusive  (not require-conclusive?)
    true))

(defn theory-result-ok?
  "Suite pass/fail for a full theory result map (metric track).
   Uses canonical :status (see `theory-result/result-status`). Nil theory-result passes."
  [theory-result purpose opts]
  (if (nil? theory-result)
    true
    (theory-status-ok? (theory-result/result-status theory-result)
                       purpose
                       opts)))

(defn hard-failure-results?
  "True when any result entry is an explicit hard failure."
  [results]
  (some #(and (= :fail (:status %))
              (= :hard (:severity %)))
        results))

(defn inconclusive-status?
  [status]
  (contains? #{:inconclusive :not-applicable} status))

(defn domain-results-ok?
  "Generic pass/fail logic for mechanism/equilibrium result groups.
   Hard failures fail except in negative-test scenarios.
   In strict mode, inconclusive/not-applicable status fails.
   :not-checked is treated as no-op and passes."
  [purpose status results {:keys [require-conclusive?] :or {require-conclusive? false}}]
  (let [neg-test? (negative-test-purpose? purpose)]
    (and
     (or (not (hard-failure-results? results)) neg-test?)
     (or (not require-conclusive?)
         (not (inconclusive-status? status))))))

(defn purpose-label
  "Human-readable purpose label with stable fallback for unknown purposes."
  [p]
  (or (get-in (defs/purpose-def p) [:label])
      (if p (name p) (get-in (defs/purpose-def :unclassified) [:label]))))
