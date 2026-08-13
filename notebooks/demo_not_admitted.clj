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
(def demo-result
  (let [evidence-input (records->custody-evidence-input user-records)
        artifacts (vals (custody/rebuild-held-custody-artifacts evidence-input))
        changed (mapv #(assoc % :amount 1100 :held/after 1100) artifacts)
        baseline (verify artifacts)
        after (verify changed)
        committed (first artifacts)]
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
                     :after/checks (:checks after)}}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (demo-views/demo-surface demo-result))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (demo-views/technical-proof demo-result))

;; ## What the framework does — and does not — do
;;
;; | Framework | Application / notebook author |
;; | --- | --- |
;; | Build and independently verify committed custody evidence | Choose the escrow domain, amount, actor, and mutation |
;; | Reject evidence whose committed hash no longer recomputes | Decide what to do after rejection |
;; | Provide generic admission primitives | Define a product's authorization, reservation, cancellation, pro-rata, or dispute policy |
;;
;; The failed check is evidence of an integrity mismatch. It is **not** a
;; framework prescription for the application's next action.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [holds? (and (get-in demo-result [:demo/baseline :admitted?])
                   (not (get-in demo-result [:demo/outcome :admitted?]))
                   (= [:held-custody/hash-integrity]
                      (get-in demo-result [:demo/outcome :failed-checks])))]
   [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1"
                  :borderRadius "8px" :padding "12px 16px" :fontFamily "monospace"}}
    "notebook demonstration: "
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "VERIFIED ✓" "VIOLATED ✕")]]))
