;; # Not Admitted — admission boundary, not application workflow
;;
;; ## How do generic admission boundaries behave?
;;
;; The framework provides small, composable checks. An application configures
;; them with its own policy and data; it owns the surrounding process.
;;
;; The concrete amount-tampering walkthrough is intentionally notebook-only in
;; `notebooks/demo_not_admitted`. The clean-room corpus is separately shown in
;; `notebooks/clean_room_not_admitted`.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-admitted
  (:require [nextjournal.clerk :as clerk]

            [resolver-sim.assurance.force-authorisation :as force-auth]
            [resolver-sim.composition.combination :as combination]
            [resolver-sim.resubmission.chain :as chain]
            [resolver-sim.resubmission.store :as store]))

;; ## The reusable boundaries

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- admit-request
  "Request shape for `chain/admit!`. Keyword args: :r :seq :parent :link :idem :basis :version."
  [& {:keys [r seq parent link idem basis version]}]
  (cond-> {:receipt-hash r :sequence seq :parent-receipt-hash parent
           :link-hash link :idempotency-key idem :basis-root basis}
     version (assoc :expected-chain-version version)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- configured-chain
  "A chain with a disposition public key (required for signed admission)."
  [family-id]
  (chain/new-chain family-id "sha256:0000000000000000000000000000000000000000000000000000000000000000"))

(def boundary-results
  (let [scope {:authorization/id "example-release-42"
               :authorization/type :force-authorisation
               :held/direction :out :token :USDC :amount 40
               :held/account :escrow-principal :owner/address "0xExampleUser"
               :held/reason :application-release :held/workflow-id 42
               :held/position-id [:held/position :USDC :escrow-principal 42]
               :parameter/context {:parameter-context/type :protocol-parameters
                                   :parameter-context/root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                                   :parameter-context/version 1}
               :parameter/address {:parameter/id :sew/escrow-principal}}
          record {:authorization/id "example-release-42" :authorization/status :active
                  :consumed? false :starts-at 0 :authorization/scope scope
                  :authorization/scope-hash (force-auth/force-authorisation-scope-hash scope)}
          valid-scope-result (force-auth/verify-authorisation-usable record {} scope 0)
          tampered-scope (assoc scope :held/reason :application-refund)
          tampered-result (force-auth/verify-authorisation-usable record {} tampered-scope 0)
          malformed-scope (dissoc scope :parameter/address)
          malformed-result (force-auth/verify-authorisation-usable record {} malformed-scope 0)
          unconfigured-chain (chain/new-chain "sha256:example-family")
          unconfigured-result (chain/admit! unconfigured-chain {})

          configured-c (configured-chain "sha256:chain-family")
          req-a (admit-request :r :receipt-a :seq 1 :parent nil :link "sha256:link-a" :idem "sha256:idem-a" :basis "sha256:basis-a")
          result-a (binding [chain/*admit-compat-guard* nil]
                     (chain/admit-compat! configured-c req-a))
          req-b (admit-request :r :receipt-b :seq 2 :parent :receipt-a :link "sha256:link-b" :idem "sha256:idem-b" :basis "sha256:basis-b")
          result-b (binding [chain/*admit-compat-guard* nil]
                     (chain/admit-compat! configured-c req-b))
          head-after-b (store/chain-head configured-c)

          wrong-parent-req (admit-request :r :r4 :seq 3 :parent :receipt-a :link "sha256:link-4" :idem "sha256:idem-4" :basis "sha256:basis-4")
          wrong-parent-result (binding [chain/*admit-compat-guard* nil]
                                (chain/admit-compat! configured-c wrong-parent-req))

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
         :expected? (:valid? valid-scope-result)
         :fail-closed? (and (not (:valid? tampered-result))
                            (contains? (set (map :code (:errors tampered-result)))
                                       :scope-hash-mismatch)
                            (contains? (set (map :code (:errors tampered-result)))
                                       :scope-mismatch))
         :observation "TAMPERED SCOPE REJECTED"}
        {:boundary "Parameter attribution must be complete"
         :framework-role "Rejects a scope where :parameter/context is supplied without :parameter/address."
         :expected? (not (:valid? malformed-result))
         :fail-closed? (some #(= :invalid-parameter-attribution (:code %)) (:errors malformed-result))
         :application-owns "Application owns which parameter context/address identifies its intended parameters."}
        {:boundary "Unconfigured chain fails closed"
         :framework-role "Refuses admission when no trusted receipt authority is configured."
         :expected? (= :not-admitted (:admission-status unconfigured-result))
         :fail-closed? (= :receipt-authority-not-configured (:reason unconfigured-result))
         :application-owns "Application configures trusted key; framework enforces."}
        {:boundary "Configured chain advances on consecutive admissions"
         :framework-role "A chain with disposition authority accepts two changes; each produces a new chain head."
         :expected? (and (= :admitted (:admission-status result-a))
                         (= :admitted (:admission-status result-b)))
         :fail-closed? (and (= :ok (:reason result-a))
                            (= :ok (:reason result-b))
                            (not= :receipt-a head-after-b))
         :application-owns "Application owns when and how to submit changes; framework advances state."}
        {:boundary "Stale parent rejected"
         :framework-role "A change with stale parent (pointing to an old chain head) is rejected."
         :expected? (= :not-admitted (:admission-status wrong-parent-result))
         :fail-closed? (= :parent-not-current-head (:reason wrong-parent-result))
         :application-owns "Application owns submission timing; framework enforces ancestry."}
        {:boundary "Consecutive composition"
         :framework-role "Validates that v1 composition is a declared consecutive pipeline."
         :expected? (:valid? (combination/validate-combination pipeline))
         :fail-closed? (not (:valid? (combination/validate-combination branched)))
         :application-owns "Application defines workflow; framework validates structure."}]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Boundary" "Framework check" "Expected behavior" "Observed" "Fail-closed behavior" "Application owns"]
  :rows (mapv (fn [{:keys [boundary framework-role expected? fail-closed? observation application-owns]}]
                [boundary framework-role
                 (if expected? "OBSERVED" "CHECK FAILED")
                 (if expected? "✓" "✗")
                 (if fail-closed? "REJECTED" "ERROR")
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
 (let [holds? (every? #(and (:expected? %) (:fail-closed? %)) boundary-results)]
   [:div {:style {:background (if holds? "#f0fdf4" "#fef2f2")
                  :border (str "1px solid " (if holds? "#86efac" "#fca5a5"))
                  :border-radius "8px" :padding "12px 16px" :font-family "monospace"}}
    "generic admission boundaries produce the expected result — "
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "HOLDS ✓" "VIOLATED ✕")]]))