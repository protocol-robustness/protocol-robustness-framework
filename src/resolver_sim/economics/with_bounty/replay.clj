(ns resolver-sim.economics.with-bounty.replay
  "Implementation replay for with-bounty (design note §12.2, ADR-0006 C1).

   Re-runs the exact sealed eligibility and amount implementations against the
   committed inputs (:replay/inputs captured by the evaluator) and reconciles
   every committed artifact — receipt, application plan, and effect — by full
   value comparison. This establishes deterministic replay, NOT independent
   correctness: the result is classified :implementation-replay and must never
   be labelled independent (ADR-0006 D6).

   Replay stops at the capability boundary: it re-invokes eligibility and
   amount only. Protocol transition reproduction is a separate concern that
   requires a sealed protocol runtime and state fixture; it is deliberately not
   claimed here."
  (:require [resolver-sim.economics.with-bounty.evaluation :as evaluation]))

(def verification-profile
  :implementation-replay)

(defn replay-inputs
  "The committed inputs of an evaluation, captured at evaluation time so a
   replay run reproduces the run without re-deriving them."
  [original-result]
  (:replay/inputs original-result))

(defn replay-mismatches
  "Full-value reconciliation between an original evaluation result and a replay
   run. Returns a vector of {:field <keyword> :original <value> :replayed
   <value>} entries (empty when identical)."
  [original replayed]
  (into []
        (keep (fn [[field ov rv]]
                (when (not= ov rv)
                  {:field field :original ov :replayed rv})))
        [[:status (:status original) (:status replayed)]
         [:receipt (:receipt original) (:receipt replayed)]
         [:plan (:plan original) (:plan replayed)]
         [:effect (:effect original) (:effect replayed)]]))

(defn replay-with-bounty
  "Implementation replay of an evaluation result: re-run the sealed capability
   implementations against the committed inputs and reconcile every committed
   root.

   Returns
     {:verification/profile :implementation-replay
      :valid? bool
      :mismatches [...]}."
  [original-result]
  (let [inputs (replay-inputs original-result)
        replayed (evaluation/evaluate-with-bounty inputs)
        mismatches (replay-mismatches original-result replayed)]
    {:verification/profile verification-profile
     :valid? (empty? mismatches)
     :mismatches mismatches
     :replayed/status (:status replayed)}))
