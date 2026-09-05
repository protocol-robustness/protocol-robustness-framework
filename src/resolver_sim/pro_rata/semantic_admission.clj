(ns resolver-sim.pro-rata.semantic-admission
  "Ephemeral admission boundary for independently reconstructed pro-rata decisions.

   This namespace intentionally covers only simple, no-row pro-rata decisions.
   Row/cap, redistribution, multi-round, and concurrent state-transition
   assurance remain separate contracts."
  (:require [resolver-sim.yield.partial-fill :as partial-fill]))

(def supported-mode :pro-rata)
(def supported-persistence :ephemeral)

(defn- unsupported-reason
  [{:keys [policy rows]}]
  (cond
    rows :rows-not-yet-supported
    (not= supported-mode (:mode policy supported-mode)) :mechanism-not-yet-supported
    :else nil))

(defn verify-semantic-decision
  "Verify a supplied decision against authoritative simple pro-rata inputs.

   Returns an ephemeral result with separate closed-form and semantic
   reconstruction surfaces. Unsupported mechanism scope is never admitted."
  [{:keys [policy] :as input} decision]
  (let [reason (unsupported-reason input)
        reconstruction (if reason
                         {:valid? false
                          :supported? false
                          :reason reason
                          :expected nil
                          :actual (select-keys decision
                                               [:settlement-mode :requested :filled
                                                :deferred :haircut :unrealized])
                          :mismatches [{:reason reason}]
                          :scope {:kind :independent-semantic-reconstruction
                                  :producer-independent? true
                                  :mechanism :yield/partial-fill
                                  :mode (:mode policy supported-mode)
                                  :rows-supported? false}}
                         (partial-fill/semantic-reconstruction input decision))
        closed-form (try
                      (partial-fill/partial-fill-closed-form-checks decision)
                      (catch clojure.lang.ExceptionInfo e
                        (:check-results (ex-data e))))
        closed-form-valid? (every? #(= :pass (:status %)) closed-form)
        status (cond
                 reason :unsupported
                 (and closed-form-valid? (:valid? reconstruction)) :admitted
                 :else :rejected)]
    {:valid? (= :admitted status)
     :admission/status status
     :closed-form {:valid? closed-form-valid?
                   :checks closed-form}
     :semantic-reconstruction reconstruction
     :scope {:mechanism :yield/partial-fill
             :mode (:mode policy supported-mode)
             :rows-supported? false
             :persistence supported-persistence
             :authority :caller-supplied-semantic-inputs}
     :input (select-keys input [:available :requested :policy])}))
