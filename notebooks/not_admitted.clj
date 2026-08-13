;; # Not Admitted — admission boundary, not application workflow
;;
;; ## Can this candidate enter the next trusted state?
;;
;; The framework answers only that narrow question: a candidate is admitted when
;; the relevant generic verifier recomputes it as valid. It does **not** define
;; an application's authorization policy, reservation token, cancellation during
;; a dispute, pro-rata allocation, accounting write-back, or a user's response
;; to a failed attempt. Those are adopter-owned workflow choices.
;;
;; The concrete amount-tampering walkthrough is intentionally notebook-only in
;; `notebooks/demo_not_admitted`. The clean-room corpus is separately shown in
;; `notebooks/clean_room_not_admitted`.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-admitted
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.allocation.activation :as activation]
            [resolver-sim.assurance.force-authorisation :as force-auth]
            [resolver-sim.composition.combination :as combination]
            [resolver-sim.resubmission.chain :as chain]))

;; ## The reusable boundaries
;;
;; The framework provides small, composable checks. An application configures
;; them with its own policy and data; it owns the surrounding process.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- sha256-like [c] (apply str (repeat 64 c)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def boundary-results
  (let [scope {:authorization/id "example-release-42"
               :authorization/type :force-authorisation
               :held/direction :out :token :USDC :amount 40
               :held/account :escrow-principal :owner/address "0xExampleUser"
               :held/reason :application-release :held/workflow-id 42}
        record {:authorization/id "example-release-42" :authorization/status :active
                :consumed? false :starts-at 0 :authorization/scope scope
                :authorization/scope-hash (force-auth/force-authorisation-scope-hash scope)}
        activation-policy {:authority :application-coordinator :fail-closed true}
        passing-proof {:result/status :passing :result-root (sha256-like "a")
                       :certificate-assertions-digest (sha256-like "b")}
        rejected-proof {:result/status :rejected :result-root (sha256-like "c")
                        :rejection/classification :application-ineligible}
        genuine (activation/build-receipt {:proof passing-proof :policy activation-policy})
        forged-status (assoc (activation/build-receipt {:proof rejected-proof :policy activation-policy})
                             :activation/status :activated)
        configured-chain (chain/new-chain "sha256:example-family")
        unconfigured-result (chain/admit! configured-chain {})
        pipeline {:combination/id :application-pipeline
                  :combination/version 1
                  :combination/nodes [{:node/id :collect
                                       :capability/ref [:example/collect :v1]
                                       :capability/version 1}
                                      {:node/id :verify
                                       :capability/ref [:example/verify :v1]
                                       :capability/version 1}]
                  :combination/input {:schema-ref :example/input :semantic-type :example/value}
                  :combination/expected-output {:schema-ref :example/output :semantic-type :example/value}}
        branched (assoc pipeline :combination/edges [{:from :collect :to :verify}
                                                     {:from :collect :to :other}])]
    [{:boundary "Scoped authority"
      :framework-role "Checks that supplied action data stays inside an application's declared scope."
      :admitted? (:valid? (force-auth/verify-authorisation-usable record {} scope 0))
      :rejected? (not (:valid? (force-auth/verify-authorisation-usable
                                record {} (assoc scope :held/reason :application-refund) 0)))
      :application-owns "Which actor may authorize which effect, and any reservation or consumption workflow."}
     {:boundary "Derived status"
      :framework-role "Recomputes an activation receipt rather than trusting its visible status."
      :admitted? (activation/valid-activated-receipt? genuine)
      :rejected? (not (activation/valid-activated-receipt? forged-status))
      :application-owns "The meaning of passing, rejected, and the action taken after either verdict."}
     {:boundary "Configured chain"
      :framework-role "Refuses admission when the chain has no trusted receipt authority configured."
      :admitted? false
      :rejected? (= :receipt-authority-not-configured (:reason unconfigured-result))
      :application-owns "Keys, receipt issuance, submission timing, retries, and the user-facing workflow."}
     {:boundary "Consecutive composition"
      :framework-role "Validates that v1 composition is a declared consecutive pipeline."
      :admitted? (:valid? (combination/validate-combination pipeline))
      :rejected? (not (:valid? (combination/validate-combination branched)))
      :application-owns "The capabilities, data contracts, parallelism, and semantics of every step."}]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Boundary" "Framework recomputes" "Control" "Candidate" "Application owns"]
  :rows (mapv (fn [{:keys [boundary framework-role admitted? rejected? application-owns]}]
                [boundary framework-role
                 (if admitted? "ADMITTED" "CHECK FAILED")
                 (if rejected? "NOT ADMITTED" "CHECK FAILED")
                 application-owns])
              boundary-results)})

;; ## How to use this boundary
;;
;; 1. Build your application-specific candidate and evidence outside the framework.
;; 2. Call the relevant framework verifier at the admission cutpoint.
;; 3. If it rejects, leave your authoritative state unchanged and apply your own
;;    failure policy.
;; 4. Keep user workflows — including reservations, accounting, dispute handling,
;;    corpus selection, and pro-rata policy — in your application or notebook.
;;
;; `not-admitted` therefore means only: **this supplied candidate did not pass
;; the selected admission boundary**. It does not imply a canonical application
;; lifecycle or prescribe a recovery action.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [holds? (every? #(and (:admitted? %) (:rejected? %)) boundary-results)]
   [:div {:style {:background (if holds? "#f0fdf4" "#fef2f2")
                  :border (str "1px solid " (if holds? "#86efac" "#fca5a5"))
                  :borderRadius "8px" :padding "12px 16px" :fontFamily "monospace"}}
    "generic admission boundaries recompute independently — "
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "HOLDS ✓" "VIOLATED ✕")]]))
