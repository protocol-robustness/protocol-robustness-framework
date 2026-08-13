;; # Can a Verified Result Be Changed?
;;
;; One question, one user-supplied example, one consequence.
;;
;; This is deliberately a **notebook-only** demo. The framework supplies the
;; custody artifact builder and verifier; an adopter supplies its own ledger,
;; mutation, and business meaning. Nothing in this example is a framework
;; workflow or a reusable admission policy.

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
;; the record shape, the amount, and what the escrow means. The framework only
;; makes and verifies the committed custody evidence.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def user-ledger
  [{:held-adjustment/id "example-deposit-1"
    :held/direction :in
    :token :USDC
    :amount 1000
    :held/before 0
    :held/after 1000
    :held/reason :escrow-principal-deposited
    :held/action "application-deposit-1"
    :held/account :escrow-principal
    :held/workflow-id 0
    :owner/address "0xExampleUser"}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn verify [artifacts]
  (try
    {:admitted? true :checks (custody/held-custody-closed-form-checks artifacts)}
    (catch clojure.lang.ExceptionInfo e
      {:admitted? false :checks (:check-results (ex-data e))})))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def demo-result
  (let [artifacts (vals (custody/rebuild-held-custody-artifacts user-ledger))
        changed (mapv #(assoc % :amount 1100 :held/after 1100) artifacts)
        baseline (verify artifacts)
        after (verify changed)
        committed (first artifacts)]
    {:demo/id :admission/user-tampered-amount
     :demo/question "Can an application change a result after it has been verified?"
     :demo/baseline {:label "Escrow held amount" :value 1000 :unit "USDC"
                     :admitted? (:admitted? baseline)}
     :demo/action {:label "Application changes the recorded amount" :from 1000 :to 1100
                   :unit "USDC"
                   :detail "The application edits its record but leaves the committed artifact hash untouched."}
     :demo/outcome {:admitted? (:admitted? after)
                    :failed-checks (->> (:checks after)
                                        (filter #(= :fail (:status %)))
                                        (mapv :check/id))}
     :demo/expect {:baseline :admitted :after-action :not-admitted}
     :demo/explanation "The framework does not own the application's escrow rule. It verifies the supplied evidence: after the application edits the amount, that evidence no longer matches its committed hash."
     :demo/evidence {:committed-hash (:artifact/hash committed)
                     :lines [["ledger root" (custody/ledger-root user-ledger)]
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
