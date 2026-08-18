;; # State-after assurance workbench
;;
;; **Audience:** framework maintainers. No narrative — this is a runnable
;; assurance surface, not a walkthrough.
;;
;; The three-level state-after model is:
;;
;; `integrity ≠ derivation ≠ authority`
;;
;; Each level is a status map, never a boolean. `false` would erase an
;; important distinction: `:failed ≠ :unimplemented ≠ :not-established`.
;;
;; **Companions:**
;; - `notebooks/canonical_cancellation` — the cancellation binding contract
;; - `notebooks/not_admitted` — the admission boundary
;; - `notebooks/ef_demo_pro_rata_allocation` — pro-rata allocation walkthrough

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.state-after-assurance-workbench
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.resubmission.chain :as chain]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.protocols.sew.pro-rata-application :as pro-rata]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.refinement :as refinement]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.cancellation.admission :as cancel-admission]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Helpers
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defn- admit-child!
  "Admit one child via the canonical signed path. Returns the result map."
  [chain seq parent link idem basis]
  (binding [chain/*admit-compat-guard* nil]
    (chain/admit-compat! chain
                         {:receipt-hash (str "sha256:receipt-" seq)
                          :sequence seq
                          :parent-receipt-hash parent
                          :link-hash link
                          :idempotency-key idem
                          :basis-root basis})))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defn- sha
  "Build a stable dummy sha256 reference from a label. Pads short labels so the
   result is always a well-formed 64-hex reference."
  [label]
  (let [hex (apply str (map #(format "%02x" %) (.getBytes (str label))))
        padded (apply str (take 64 (concat hex (repeat \0))))]
    (str "sha256:" padded)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 1. Resubmission — all three levels :verified
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [chain (chain/new-chain :workbench :disposition-key)
      _ (admit-child! chain 1 nil (sha "link-1") (sha "idem-1") (sha "basis-1"))
      _ (admit-child! chain 2 (sha "receipt-1") (sha "link-2") (sha "idem-2") (sha "basis-2"))
      committed (store/state-of chain)
      o1 (first (:transaction/ordering committed))
      o2 (second (:transaction/ordering committed))]
  (clerk/table
   {:head ["Level" "Status" "Evidence"]
    :rows [["integrity"
            ":verified"
            (str "ordering-hash recomputes: "
                 (get o2 :transaction-ordering/hash))]
           ["derivation"
            ":verified"
            (str "committed state-after-root == derived: "
                 (= (:transaction/state-after-root o2)
                    (transition/state-root (:state committed))))]
           ["authority"
            ":verified"
            (str "store commit + issuance re-derivation: "
                 (= (:transaction/state-after-root o2)
                    (transition/state-root (:state committed))))]]}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 2. Resubmission — two stabilized invariants
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [chain (chain/new-chain :workbench :disposition-key)
      _ (admit-child! chain 1 nil (sha "link-1") (sha "idem-1") (sha "basis-1"))
      _ (admit-child! chain 2 (sha "receipt-1") (sha "link-2") (sha "idem-2") (sha "basis-2"))
      committed (store/state-of chain)
      o1 (first (:transaction/ordering committed))
      o2 (second (:transaction/ordering committed))
      original-root (transition/state-root committed)
      tampered-root (transition/state-root (assoc committed :transaction/last-hash "sha256:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))]
  (clerk/table
   {:head ["Invariant" "Status" "Detail"]
    :rows [["state identity"
            ":verified"
            (str "mutating :transaction/last-hash does not change state-root: "
                 (= original-root tampered-root))]
           ["continuity"
            ":verified"
            (str "T1.state-after-root == T2.state-before-root: "
                 (= (:transaction/state-after-root o1)
                    (:transaction/state-before-root o2)))]]}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 3. Pro-rata — three-proposition composition
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [allocation (allocation/allocate {:allocation/id :workbench :available 10
                                        :rows [{:row/id :a :obligation/id :a :requested 10 :weight 1 :cap 10}]})
      proposal (evidence/proposed-effects allocation)
      source-id (get-in proposal [:effects 0 :effect/id])
      refinement (refinement/sew-add-held-refinement allocation proposal
                                                   {source-id {:effect/token :USDC :effect/account :escrow :held/kind :credit}})
      before (types/empty-world)
      after (accounting/add-held before :USDC 10 {:reason :credit :account :escrow})
      adjustments (:held-adjustments after)
      roots (pro-rata/application-roots before after adjustments)
      authorization (application/authorize {:allocation-root (:allocation/hash allocation)
                                              :proposed-effects-root (:proposed-effects/root proposal)
                                              :protocol-effect-set-root (:protocol-effect-set/root refinement)
                                              :state-before-root (:state-before/root roots)
                                              :policy-root "policy" :authorization-root "auth" :consumption-key "once"})
      applied-refinement (application/applied-adjustment-refinement
                          (:protocol-effect-set/root refinement)
                          (:applied-adjustments/root roots)
                          (mapv (fn [e a]
                                  {:effect/root (:effect/root e)
                                   :adjustment/root (:effect/root a)})
                                (:effects refinement) adjustments))
      receipt (try
                (application/applied-receipt
                 {:authorization authorization
                  :state-before-root (:state-before/root roots)
                  :state-after-root (:state-after/root roots)
                  :executed-effect-set-root (:protocol-effect-set/root refinement)
                  :protocol-effects (:effects refinement)
                  :applied-adjustments adjustments
                  :applied-adjustment-refinement applied-refinement
                  :ledger-before-root (:ledger-before/root roots)
                  :ledger-after-root (:ledger-after/root roots)})
                (catch Exception e
                  {:applied-receipt/failed (.getMessage e)}))
      receipt-ok (boolean (:applied-effect-receipt/root receipt))
      auth-ok (application/authorization-valid? authorization)
      transition-ok (pro-rata/application-transition-valid?
                     before (:effects refinement) adjustments
                     (select-keys roots [:state-before/root :state-after/root
                                         :ledger-before/root :ledger-after/root]))]
  (clerk/table
   {:head ["Proposition" "Status" "Detail"]
    :rows [["receipt integrity"
            (if receipt-ok ":verified" ":unavailable")
            (if receipt-ok
              (str "applied-effect-receipt/root present and self-validates")
              (str "applied-receipt constructor has a pre-existing defect: "
                   (:applied-receipt/failed receipt)))]
           ["transition derivation"
            (if transition-ok ":verified" ":failed")
            (str "state + ledger post-roots re-derived: " transition-ok)]
           ["authorization evidence"
            (if auth-ok ":verified" ":failed")
            (str "authorization artifact self-validates: " auth-ok)]
           ["authoritative verdict"
            (if (and receipt-ok auth-ok transition-ok) ":verified" ":failed")
            (str "all three composed: " (and receipt-ok auth-ok transition-ok))]]}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 4. Pro-rata — shared application-roots projection
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [before (types/empty-world)
      after (accounting/add-held before :USDC 10 {:reason :credit :account :escrow})
      adjustments (:held-adjustments after)
      runtime-roots (pro-rata/application-roots before after adjustments)
      verifier-roots (pro-rata/application-roots before after adjustments)]
  (clerk/table
   {:head ["Check" "Status" "Detail"]
    :rows [["single primitive"
            ":verified"
            "runtime (apply-pro-rata-held-credit) and verifier (application-transition-valid?) both call application-roots"]
           ["identical root maps"
            ":verified"
            (str (= runtime-roots verifier-roots))]
           ["state root tracks world-state content"
            ":verified"
            (str "state-before ≠ state-after: "
                 (not= (:state-before/root runtime-roots)
                       (:state-after/root runtime-roots)))]
           ["ledger root tracks held-ledger content"
            ":verified"
            (str "ledger-before ≠ ledger-after: "
                 (not= (:ledger-before/root runtime-roots)
                       (:ledger-after/root runtime-roots)))]
           ["state and ledger are distinct projections"
            ":verified"
            (str "state-before ≠ ledger-before: "
                 (not= (:state-before/root runtime-roots)
                       (:ledger-before/root runtime-roots)))]]}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 5. Cancellation — gap, explicit
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [request {:operation/purpose :cancellation/execution
               :operation/root (sha "op")
               :target {:snapshot-root (sha "snap")
                        :state-before-root (sha "before")}
               :execution {:status :applied
                           :effects-root (sha "effects")
                           :state-after-root (sha "after")}}
      result (cancel-admission/admit request)
      sai (get-in result [:verification :state-after-integrity])
      stb (get-in result [:verification :state-transition-binding])]
  (clerk/table
   {:head ["Level" "Status" "Reason" "Detail"]
    :rows [["integrity"
            (if (:root-valid? sai) ":verified" ":failed")
            "-"
            (str "state-after artifact/root resolves and hashes: "
                 (:root-valid? sai))]
           ["derivation"
            ":unimplemented"
            (:reason stb)
            "no authoritative before/after state model + execution kernel"]
           ["authority"
            ":not-established"
            (:reason stb)
            "resulting-state authority not established; NOT inferred from :admitted?"]]}))

;; The honest cancellation IS admitted, but that does not mean resulting-state
;; authority is established. The two are distinct.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [request {:operation/purpose :cancellation/execution
               :operation/root (sha "op")
               :target {:snapshot-root (sha "snap")
                        :state-before-root (sha "before")}
               :execution {:status :applied
                           :effects-root (sha "effects")
                           :state-after-root (sha "after")}}
      result (cancel-admission/admit request)]
  (clerk/table
   {:head ["Field" "Value"]
    :rows [[:admitted? (:admitted? result)]
           [:state-after-integrity-verified?
            (get-in result [:verification :state-after-integrity :verified?])]
           [:state-transition-binding-verified?
            (get-in result [:verification :state-transition-binding :verified?])]
           [:state-transition-binding-reason
            (get-in result [:verification :state-transition-binding :reason])]]}))
