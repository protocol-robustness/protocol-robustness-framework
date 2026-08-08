(ns resolver-sim.demos.not-admitted.demo
  "The demonstration model for 'tamper with the amount'.

   One question, one intervention, one visible consequence. The computation
   here is the same pure, deterministic core that the notebook, the CLI, and
   the assertions all consume. The verifier is the real closed-form custody
   verifier (resolver-sim.assurance.custody); the demo adds nothing to it."
  (:require [resolver-sim.assurance.custody :as custody]
            [resolver-sim.demos.not-admitted.scenario :as scenario]))

(def demo-id :admission/tampered-amount)

(def ^:private baseline-amount 1000)
(def ^:private changed-amount 1100)

(defn verify
  "Run the real closed-form verifier over the evidence artifacts.

   Returns {:admitted? true :checks [...]} when every check passes, or
   {:admitted? false :checks [...]} carrying the full check results when the
   verifier fails closed. The same function is used for the baseline and for
   the after-action evidence."
  [artifacts]
  (try
    (let [checks (custody/held-custody-closed-form-checks artifacts)]
      {:admitted? true :checks checks})
    (catch clojure.lang.ExceptionInfo e
      {:admitted? false
       :checks (:check-results (ex-data e))})))

(defn run
  "Produce the complete demo model: question, baseline, action, outcome,
   expectation, explanation, and technical evidence."
  []
  (let [adjustments (scenario/baseline-adjustments)
        artifacts (scenario/baseline-artifacts adjustments)
        baseline (verify artifacts)
        changed (scenario/change-recorded-amount artifacts changed-amount)
        after (verify changed)
        committed (first artifacts)]
    {:demo/id demo-id
     :demo/question "Can a result be changed after it has been verified?"
     :demo/baseline {:label "Escrow held amount"
                     :value baseline-amount
                     :unit "USDC"
                     :admitted? (:admitted? baseline)}
     :demo/action {:label "Change the recorded amount"
                   :from baseline-amount
                   :to changed-amount
                   :unit "USDC"
                   :detail "The amount and the resulting balance are edited on the record; the committed signature is left untouched."}
     :demo/outcome {:admitted? (:admitted? after)
                    :failed-checks (when-not (:admitted? after)
                                     (->> (:checks after)
                                          (filter #(= :fail (:status %)))
                                          (mapv :check/id)))}
     :demo/expect {:baseline :admitted
                   :after-action :not-admitted}
     :demo/explanation "The recorded amount no longer matches the committed evidence. The same check now rejects it, because what is on the record no longer matches its committed signature."
     :demo/evidence {:committed-hash (:artifact/hash committed)
                     :lines [["ledger root" (custody/ledger-root adjustments)]
                             ["artifact sequence root"
                              (custody/artifact-sequence-root artifacts)]]
                     :after/checks (:checks after)}}))
