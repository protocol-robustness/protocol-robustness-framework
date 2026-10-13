(ns resolver-sim.sim.common-kwargs
  "Single source of truth for dispute/resolve-dispute keyword-arg extraction.
   Both batch/run-batch and shared-batch/run-shared-batch consume these to
ensure new params are never silently dropped in one path.")

(defn common-kwargs
  "Extract the shared keyword-args vector for dispute/resolve-dispute from params.
   Every key here must also be handled in dispute/resolve-dispute's & rest
   destructuring (dispute.clj lines 43–91)."
  [params]
  [:l2-detection-prob                  (:l2-detection-prob params 0)
   :slashing-detection-delay-weeks     (:slashing-detection-delay-weeks params 0)
   :allow-slashing?                    (:allow-slashing? params true)
   :resolver-bond-bps                  (:resolver-bond-bps params 0)
   :fraud-detection-probability        (:fraud-detection-probability params)  ; nil fallback → l1-malicious in normalize-detection-probabilities
   :fraud-slash-bps                    (:fraud-slash-bps params 0)
   :l2-slash-bps                       (:l2-slash-bps params 200)
   :reversal-detection-probability     (:reversal-detection-probability params 1.0)
   :reversal-slash-bps                 (:reversal-slash-bps params 0)
   :resolver-stake-wei                 (:resolver-stake-wei params)
   :new-evidence-probability           (:new-evidence-probability params 0.0)  ; 0.0 disables pending-evidence entirely; >0 gates an oracle roll
   :timeout-detection-probability      (:timeout-detection-probability params 0.0)
   :timeout-slash-bps                  (:timeout-slash-bps params 200)
   :unstaking-delay-days               (:unstaking-delay-days params 14)
   :freeze-on-detection?               (:freeze-on-detection? params true)
   :freeze-duration-days               (:freeze-duration-days params 3)
   :appeal-window-days                 (:appeal-window-days params 7)
   :fraud-model                        (:fraud-model params :single-stage-ev)
   :escalation-assumptions             (:escalation-assumptions params)
   :escalation-assumption-band         (:escalation-assumption-band params :base)
   :p-appeal-wrong                     (:p-appeal-wrong params)
   :p-l1-reversal                      (:p-l1-reversal params)
   :p-l2-escalation                    (:p-l2-escalation params)
   :p-l2-reversal                      (:p-l2-reversal params)
   :has-kleros?                        (:has-kleros? params)
   :fraud-success-rate                 (:fraud-success-rate params 0.0)
   :oracle-fixture                     (:oracle-fixture params)
   :oracle-mode                        (:oracle-mode params)
   :oracle-roll-sequence               (:oracle-roll-sequence params)
   :oracle-roll-on-exhaustion          (:oracle-roll-on-exhaustion params)
   :fixed-or                            (:fixed-or params)
   :oracle-roll-trace-enabled?         (:oracle-roll-trace-enabled? params false)
   :evidence-quality?                  (:evidence-quality? params false)
   :lazy-correct-prob                  (:lazy-correct-prob params 0.5)
   :malicious-correct-prob             (:malicious-correct-prob params 0.3)
   :collusive-correct-prob             (:collusive-correct-prob params 0.8)])