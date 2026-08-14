;; # Can a Committed Evidence Record Be Changed?
;;
;; One integrity question, one user-supplied example, one consequence.
;;
;; This is deliberately a **notebook-only** demo. An adopter supplies its own
;; record, mutation, and business meaning; this notebook projects that record
;; into evidence input for the framework verifier. Nothing in this example is a
;; framework workflow, canonical ledger schema, or reusable admission policy.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.demo-not-admitted
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.notebook-support.demo-views :as demo-views]))

;; ## The user-owned example
;;
;; Imagine an application that records a 1,000 USDC escrow deposit. It chooses
;; the record shape, the amount, and what the escrow means. The projection below
;; is this notebook's adapter to an existing verifier; it is not a required
;; application ledger schema.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def user-records
  [{:record/id "application-deposit-1"
    :account "customer-escrow"
    :asset "USDC"
    :recorded-amount 1000
    :recorded-by "0xExampleUser"
    :meaning :customer-deposit}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn records->custody-evidence-input [records]
  (mapv (fn [{:keys [record/id account asset recorded-amount recorded-by meaning]}]
          {:held-adjustment/id id
           :held/direction :in
           :token (keyword asset)
           :amount recorded-amount
           :held/before 0
           :held/after recorded-amount
           :held/reason meaning
           :held/action id
           :held/account (keyword account)
           :held/workflow-id 0
           :owner/address recorded-by})
        records))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn verify [artifacts]
  (try
    {:admitted? true :checks (custody/held-custody-closed-form-checks artifacts)}
    (catch clojure.lang.ExceptionInfo e
      {:admitted? false :checks (:check-results (ex-data e))})))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def check-meanings
  {:held-custody/hash-integrity "contents no longer match committed hash"
   :held-custody/artifact-schema "artifacts conform to supported schema"
   :held-custody/parameter-attribution "parameter context and address are structurally valid"
   :held-custody/local-delta "held/after = held/before ± amount"
   :held-custody/valid-amount "amount is a non-negative number"
   :held-custody/valid-artifact "artifact id, hash, kind, schema are valid"
   :held-custody/non-negative-after "held/after is non-negative"
   :held-custody/predecessor-continuity "held/previous-artifact-hash matches predecessor"
   :held-custody/sequence-replay "artifact replay state remains consistent"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def demo-result
  (let [evidence-input (records->custody-evidence-input user-records)
        artifacts (vals (custody/rebuild-held-custody-artifacts evidence-input))
        changed (mapv #(assoc % :amount 1100 :held/after 1100) artifacts)
        baseline (verify artifacts)
        after (verify changed)
        committed (first artifacts)
        checks-with-meanings (mapv (fn [check]
                                     (assoc check
                                            :meaning (get check-meanings (:check/id check))))
                                   (:checks after))]
    {:demo/id :admission/user-tampered-amount
     :demo/question "Can an application change committed evidence after it has been verified?"
     :demo/baseline {:label "Escrow held amount" :value 1000 :unit "USDC"
                     :admitted? (:admitted? baseline)}
     :demo/action {:label "Application changes projected evidence" :from 1000 :to 1100
                   :unit "USDC"
                   :detail "The application changes the amount in the evidence it submits while leaving the committed artifact hash untouched."}
     :demo/outcome {:admitted? (:admitted? after)
                    :failed-checks (->> (:checks after)
                                        (filter #(= :fail (:status %)))
                                        (mapv :check/id))}
     :demo/expect {:baseline :admitted :after-action :not-admitted}
     :demo/explanation "The framework does not own the application's escrow rule. It verifies supplied evidence: after the application changes the submitted amount, that evidence no longer matches its committed hash."
     :demo/evidence {:committed-hash (:artifact/hash committed)
                     :lines [["projected evidence root" (custody/ledger-root evidence-input)]
                             ["artifact sequence root" (custody/artifact-sequence-root artifacts)]]
                     :after/checks checks-with-meanings}}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (demo-views/demo-surface demo-result))

;; **NOT ADMITTED**
;;
;; This mutated evidence remains locally well-formed, but is no longer the evidence
;; that was committed. Its content no longer recomputes to the committed hash.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (demo-views/technical-proof demo-result))

;; The most interesting property here is that the mutation (amount changed)
;; still passes: artifact schema, parameter attribution, local delta, valid
;; amount, valid-artifact, non-negative-after, predecessor continuity, and
;; sequence replay. Yet hash-integrity fails.
;;
;; This demonstrates that a plausible, internally coherent mutation cannot
;; masquerade as the committed evidence.

;; | Framework | Application / notebook author |
;; | --- | --- |
;; | Canonicalize supplied custody evidence and recompute its verification | Choose the escrow facts: domain data, amount, actors, and intended mutation |
;; | Reject evidence whose committed hash no longer recomputes | Decide what to do after rejection |
;; | Provide generic admission primitives | Define a product's authorization, reservation, cancellation, pro-rata, or dispute policy |
;;
;; The failed check is evidence of an integrity mismatch. It is **not** a
;; framework prescription for the application's next action.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [baseline-admitted? (get-in demo-result [:demo/baseline :admitted?])
       after-admitted? (get-in demo-result [:demo/outcome :admitted?])
       expected-failures [:held-custody/hash-integrity]
       actual-failures (get-in demo-result [:demo/outcome :failed-checks])
       holds? (and baseline-admitted?
                   (not after-admitted?)
                   (= expected-failures actual-failures))]
   [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1"
                  :borderRadius "8px" :padding "12px 16px" :fontFamily "monospace"}}
    "Demo result: expected integrity rejection observed"
    [:br]
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "HOLDS ✓" "HOLDS ✕")]]))
