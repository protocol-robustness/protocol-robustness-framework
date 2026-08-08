(ns resolver-sim.demos.reorder-chain.demo
  "The demonstration model for 'reorder the evidence'.

   One question, one intervention, one visible consequence. The verifier is the
   real chain verifier (resolver-sim.evidence.chain); the same function runs on
   the baseline order and on the reordered order."
  (:require [clojure.string :as str]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.demos.reorder-chain.scenario :as scenario]))

(def demo-id :admission/reordered-chain)

(defn verify
  "Run the real chain verifier over the records. Returns the verification
   result map (an invalid chain is reported, never thrown)."
  [records]
  (chain/verify-scenario-chain records :scenario-id "demo"))

(defn run
  "Produce the complete demo model: question, baseline, action, outcome,
   expectation, explanation, and technical evidence."
  []
  (let [baseline (scenario/baseline-records)
        baseline-result (verify baseline)
        reordered (scenario/reorder-records baseline)
        after-result (verify reordered)
        after-errors (:chain/errors after-result)]
    {:demo/id demo-id
     :demo/question "Does the same evidence in a different order mean the same thing?"
     :demo/baseline {:label "Evidence in order"
                     :value (str/join " → " (map name scenario/baseline-order))
                     :admitted? (= :verified (:chain/status baseline-result))}
     :demo/action {:label "Reorder the evidence"
                   :from (str/join " → " (map name scenario/baseline-order))
                   :to (str/join " → " (map name scenario/reordered-order))
                   :detail "The same three evidence items are rearranged; every committed position binding is left untouched."}
     :demo/outcome {:admitted? (= :verified (:chain/status after-result))
                    :failed-checks (mapv :reason after-errors)}
     :demo/expect {:baseline :admitted
                   :after-action :not-admitted}
     :demo/explanation "The order is part of the commitment. Each record is bound to its position, so after the swap the records no longer match the signatures they were committed with."
     :demo/evidence {:committed-hash (:chain/head-hash baseline-result)
                     :lines [["chain status" (name (:chain/status after-result))]
                             ["links" (if (:chain/links-valid? after-result) "valid" "BROKEN")]
                             ["signatures" (if (:chain/hashes-valid? after-result) "valid" "INVALID")]]
                     :after/checks (mapv (fn [e]
                                           {:check/id (or (:reason e) :unknown)
                                            :status :fail
                                            :detail (:chain-seq e)})
                                         after-errors)}}))
