(ns resolver-sim.commands.researcher-challenge
  "Researcher-facing commands for challenge participation."
  (:require [clojure.pprint :as pprint]
            [resolver-sim.research.researcher-challenges-cli :as challenges-cli]))

(defn- invoke [label f]
  (try
    (let [result (f)]
      (pprint/pprint result)
      {:exit-code 0 :result result})
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (str label ": " (.getMessage e))))
      {:exit-code 1
       :message (.getMessage e)
       :error/kind (or (:reason (ex-data e)) :invalid-challenge-operation)})
    (catch Exception e
      (binding [*out* *err*]
        (println (str label ": " (.getMessage e))))
      {:exit-code 1
       :message (.getMessage e)
       :error/kind :challenge-operation-failed})))

(defn list-challenges [{:keys [config history]}]
  (invoke "researcher challenge list"
          #(challenges-cli/challenge-catalog config nil history)))

(defn show-challenge [{:keys [config challenge-root history]}]
  (invoke "researcher challenge show"
          #(let [catalog (challenges-cli/challenge-catalog config challenge-root history)]
             (assoc (dissoc catalog :challenges) :challenge (first (:challenges catalog))))))

(defn enroll [{:keys [config history public-key key checkpoint]}]
  (invoke "researcher challenge enroll"
          #(let [result (challenges-cli/enroll-file! config history public-key key checkpoint)]
             {:researcher/status :enrolled
              :researcher/root (:actor/root result)
              :history/root (:history/root result)
              :history/checkpoint-status (if checkpoint
                                           :matches-supplied-checkpoint
                                           :not-checked)})))

(defn key-check [{:keys [config public-key key]}]
  (invoke "researcher challenge key-check"
          #(challenges-cli/check-key-pair! config public-key key)))

(defn history-check [{:keys [config history checkpoint]}]
  (invoke "researcher challenge history-check"
          #(challenges-cli/verify-history-extension! config history checkpoint)))

(defn claim [{:keys [config history challenge-root public-key key checkpoint]}]
  (invoke "researcher challenge claim"
          #(let [result (challenges-cli/claim-file! config history challenge-root public-key key checkpoint)
                 quote (:consequence result)]
             {:claim/status :claimed
              :claim/action-root (:action/root result)
              :task-claim/root (:artifact/root quote)
              :challenge/root (:challenge/root quote)
              :quote/reward-tier (:reward/tier quote)
              :quote/reward-amount (:reward/amount quote)
              :quote/reward-currency (:reward/currency quote)
              :history/root (:history/root result)
              :history/checkpoint-status (if checkpoint
                                           :matches-supplied-checkpoint
                                           :not-checked)})))

(defn submit
  "Sign and durably append a researcher's challenge submission."
  [{:keys [config history challenge-root task-claim-root public-key evidence-context
           research-input key checkpoint]}]
  (invoke "researcher challenge submit"
          #(let [result (challenges-cli/submit-file!
                         config history challenge-root task-claim-root public-key evidence-context
                         research-input key checkpoint)]
             {:submission/status :submitted
              :submission/root (:action/root result)
              :task-claim/root (or task-claim-root
                                   (get-in result [:consequence :task-claim/root]))
              :history/root (:history/root result)
              :history/checkpoint-status (if checkpoint
                                           :matches-supplied-checkpoint
                                           :not-checked)
              :review/status :not-reviewed
              :reward/authorization :not-created})))

(defn status [{:keys [config history public-key checkpoint]}]
  (invoke "researcher challenge status"
          #(challenges-cli/researcher-status config history public-key checkpoint)))
