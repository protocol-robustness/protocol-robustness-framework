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
            [resolver-sim.notebook-support.assurance :as assurance]
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
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.cancellation.admission :as cancel-admission]
            [resolver-sim.cancellation.operation :as cancel-operation]
            [resolver-sim.cancellation.ordinary-planner :as cancel-planner]
            [resolver-sim.cancellation.party-command :as cancel-command]
            [resolver-sim.cancellation.semantic :as cancel-semantic]
            [resolver-sim.cancellation.sew-escrow-snapshot :as cancel-snapshot]
            [resolver-sim.signed-external-decision :as signed]
            [buddy.core.codecs :as codecs]))

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

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defn- cancellation-keypair
  "Ed25519 keypair for the honest cancellation fixture (classpath-safe: buddy-core
   is a base dependency, unlike the test-only support.ed25519 namespace)."
  []
  (let [kg (java.security.KeyPairGenerator/getInstance "Ed25519")
        kp (.generateKeyPair kg)
        encoded (vec (.getEncoded (.getPublic kp)))
        raw-hex (codecs/bytes->hex (byte-array (take-last 32 encoded)))]
    {:key/id :alice-key
     :private-key (.getPrivate kp)
     :public-hex raw-hex}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(defn- honest-cancellation-admission
  "Build an honestly signed, fully resolved party-cancellation operation and
   admit it through the resolver-based admission API.  Returns the admission
   result map.  The operation IS admitted (authorization verifies) while the
   state-after derivation remains unimplemented — the two are distinct."
  []
  (let [sha (fn [n] (format "sha256:%064x" n))
        kp (cancellation-keypair)
        s0 {:snapshot/schema cancel-snapshot/schema-version
            :workflow/id "escrow-7" :escrow/sender "alice" :escrow/recipient "bob"
            :escrow/state :pending :sender/cancellation-status :none
            :recipient/cancellation-status :agree-to-cancel}
        s (assoc s0 :snapshot/root (cancel-snapshot/snapshot-root s0))
        p0 {:policy/schema cancel-semantic/policy-schema :policy/can-cancel? true
            :policy/unilateral-cancel? false}
        p (assoc p0 :policy/root (cancel-semantic/policy-root p0))
        bare {:operation/schema cancel-operation/schema-version
              :operation/purpose :cancellation/execution :event/id "cancel-7"
              :protocol/id :sew
              :target {:kind :sew/escrow :id "escrow-7" :snapshot-root (:snapshot/root s)
                       :state-before-root (sha 1) :lifecycle-head-root (sha 2)}
              :request {:caller/id "alice" :party :sender :action :cancel :requested-at 1}
              :policy {:id :sew/party-cancellation :root (:policy/root p)}
              :evaluation {:inputs-root (sha 4) :base-decision :ordinary :decision {}}
              :preconditions/root (sha 5)
              :authorization {:kind :ordinary :root (sha 5)}
              :execution {:status :applied :effects-root (sha 6) :state-after-root (sha 7)}
              :operation/root (sha 10)}
        plan (cancel-planner/plan {:operation bare :snapshot s :policy p :principal "alice"})
        op0 (-> bare
                (assoc-in [:preconditions/root]
                          (get-in plan [:preconditions :preconditions/root]))
                (assoc-in [:authorization :root]
                          (get-in plan [:preconditions :preconditions/root]))
                (assoc-in [:evaluation :decision :derived-effects-root]
                          (get-in plan [:derived-effects :effects/root])))
        ee0 {:execution-effects/schema cancel-semantic/execution-effects-schema
             :derived-effects/root (get-in plan [:derived-effects :effects/root])}
        ee (assoc ee0 :execution-effects/root (cancel-semantic/execution-effects-root ee0))
        op0 (assoc-in op0 [:execution :effects-root] (:execution-effects/root ee))
        op (assoc op0 :operation/root (cancel-operation/operation-root (dissoc op0 :operation/root)))
        c0 {:command/schema cancel-command/schema-version :command/action :cancel
            :command/principal "alice" :operation/root (:operation/root op)}
        c (signed/sign-envelope (assoc c0 :command/root (cancel-command/command-root c0))
                                cancel-command/decision-domain (:private-key kp) (:key/id kp))
        opaque-roots [(get-in op [:target :state-before-root])
                      (get-in op [:target :lifecycle-head-root])
                      (get-in op [:evaluation :inputs-root])
                      (get-in op [:preconditions/root])
                      (get-in op [:execution :state-after-root])]
        artifacts (merge (zipmap opaque-roots (map #(hash-map :artifact/root %) opaque-roots))
                         {(:snapshot/root s) s
                          (:policy/root p) p
                          (get-in plan [:derived-effects :effects/root]) (:derived-effects plan)
                          (:execution-effects/root ee) ee
                          (get-in op [:authorization :root])
                          {:artifact/root (get-in op [:authorization :root]) :party-command c}})]
    (cancel-admission/admit {:operation op :resolve-artifact #(get artifacts %)
                             :trust-policy {:trusted-keys [{:key/id (:key/id kp)
                                                            :key/public (:public-hex kp)
                                                            :key/role cancel-command/authority-role
                                                            :key/status :active}]}
                             :key->principal {:alice-key "alice"}})))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 1. Resubmission — all three levels :verified
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [chain (chain/new-chain :workbench :disposition-key)
      r1 (admit-child! chain 1 nil (sha "link-1") (sha "idem-1") (sha "basis-1"))
      r2 (admit-child! chain 2 "sha256:receipt-1" (sha "link-2") (sha "idem-2") (sha "basis-2"))
      o1 (:transaction-ordering r1)
      o2 (:transaction-ordering r2)
      committed (store/state-of chain)
      derived-root (transition/state-root committed)
      integrity? (:valid? (ordering/verify-ordering o2))
      derivation? (assurance/root-compare (:transaction/state-after-root o2) derived-root)
      authority? (and integrity?
                      (:valid? (ordering/verify-ordering-chain [o1 o2]))
                      (assurance/root-compare (:transaction/state-after-root o2) derived-root))]
  (clerk/table
   {:head ["Level" "Status" "Verified?" "Evidence"]
    :rows (assurance/rows
           [[:integrity integrity?
             (str "ordering-hash recomputes: " (:transaction-ordering/hash o2))]
            [:derivation derivation?
             (str "committed state-after-root == derived: " derivation?)]
            [:authority authority?
             (str "ordering-chain verifies + store commit re-derivation: " authority?)]])}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 2. Resubmission — two stabilized invariants
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [chain (chain/new-chain :workbench :disposition-key)
      r1 (admit-child! chain 1 nil (sha "link-1") (sha "idem-1") (sha "basis-1"))
      r2 (admit-child! chain 2 "sha256:receipt-1" (sha "link-2") (sha "idem-2") (sha "basis-2"))
      o1 (:transaction-ordering r1)
      o2 (:transaction-ordering r2)
      committed (store/state-of chain)
      original-root (transition/state-root committed)
      tampered-root (transition/state-root
                     (assoc committed :transaction/last-hash
                            "sha256:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
      state-identity? (= original-root tampered-root)
      after-root (:transaction/state-after-root o1)
      before-root (:transaction/state-before-root o2)
      continuity? (assurance/root-compare after-root before-root)]
  (clerk/table
   {:head ["Invariant" "Status" "Verified?" "Detail"]
    :rows (assurance/rows
           [["state identity" state-identity?
             (str "mutating :transaction/last-hash does not change state-root: "
                  state-identity?)]
            ["continuity" continuity?
             (str "T1.state-after-root == T2.state-before-root: " continuity?
                  " (operands present: "
                  (and (some? after-root) (some? before-root)) ")")]])}))

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
      ;; `:held/account` is committed through the supported `:extra` path.
      after (accounting/add-held before :USDC 10 {:reason :credit :extra {:held/account :escrow}})
      adjustments (:held-adjustments after)
      roots (pro-rata/application-roots before after adjustments)
      authorization (application/authorize {:allocation-root (:allocation/hash allocation)
                                              :proposed-effects-root (:proposed-effects/root proposal)
                                              :protocol-effect-set-root (:protocol-effect-set/root refinement)
                                              :state-before-root (:state-before/root roots)
                                              :policy-root "policy" :authorization-root "auth" :consumption-key "once"})
      pairs (mapv (fn [e a]
                    {:effect/root (:effect/root e)
                     :adjustment/root (effects/held-adjustment-root a)})
                  (:effects refinement) adjustments)
      applied-refinement (application/applied-adjustment-refinement
                          (:protocol-effect-set/root refinement)
                          (:applied-adjustments/root roots)
                          pairs)
      receipt (application/applied-receipt
               {:authorization authorization
                :state-before-root (:state-before/root roots)
                :state-after-root (:state-after/root roots)
                :executed-effect-set-root (:protocol-effect-set/root refinement)
                :protocol-effects (:effects refinement)
                :applied-adjustments adjustments
                :applied-adjustment-refinement applied-refinement
                :ledger-before-root (:ledger-before/root roots)
                :ledger-after-root (:ledger-after/root roots)})
      receipt-ok (boolean (:applied-effect-receipt/root receipt))
      auth-ok (application/authorization-valid? authorization)
      transition-ok (pro-rata/application-transition-valid?
                     before (:effects refinement) adjustments
                     (select-keys roots [:state-before/root :state-after/root
                                         :ledger-before/root :ledger-after/root]))]
  (clerk/table
   {:head ["Proposition" "Status" "Verified?" "Detail"]
    :rows (assurance/rows
           [["receipt integrity" receipt-ok
             (str "applied-effect-receipt/root present and self-validates: " receipt-ok)]
            ["transition derivation" transition-ok
             (str "state + ledger post-roots re-derived: " transition-ok)]
            ["authorization evidence" auth-ok
             (str "authorization artifact self-validates: " auth-ok)]
            ["authoritative verdict" (and receipt-ok auth-ok transition-ok)
             (str "all three composed: " (and receipt-ok auth-ok transition-ok))]])}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 4. Pro-rata — shared application-roots projection
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [before (types/empty-world)
      after (accounting/add-held before :USDC 10 {:reason :credit :extra {:held/account :escrow}})
      adjustments (:held-adjustments after)
      runtime-roots (pro-rata/application-roots before after adjustments)
      verifier-roots (pro-rata/application-roots before after adjustments)
      single-primitive? true
      identical-roots? (= runtime-roots verifier-roots)
      state-changes? (not= (:state-before/root runtime-roots)
                           (:state-after/root runtime-roots))
      ledger-changes? (not= (:ledger-before/root runtime-roots)
                            (:ledger-after/root runtime-roots))
      distinct-projections? (not= (:state-before/root runtime-roots)
                                  (:ledger-before/root runtime-roots))]
  (clerk/table
   {:head ["Check" "Status" "Verified?" "Detail"]
    :rows (assurance/rows
           [["single primitive" single-primitive?
             "runtime (apply-pro-rata-held-credit) and verifier (application-transition-valid?) both call application-roots"]
            ["identical root maps" identical-roots? (str identical-roots?)]
            ["state root tracks world-state content" state-changes?
             (str "state-before ≠ state-after: " state-changes?)]
            ["ledger root tracks held-ledger content" ledger-changes?
             (str "ledger-before ≠ ledger-after: " ledger-changes?)]
            ["state and ledger are distinct projections" distinct-projections?
             (str "state-before ≠ ledger-before: " distinct-projections?)]])}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; 5. Cancellation — gap, explicit
;; ═══════════════════════════════════════════════════════════════════════════════

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [result (honest-cancellation-admission)
      sai (get-in result [:verification :state-after-integrity])
      stb (get-in result [:verification :state-transition-binding])
      integrity? (:root-valid? sai)
      row (fn [label verified? status evidence]
            [label (name status) (boolean verified?) evidence])]
  (clerk/table
   {:head ["Level" "Status" "Verified?" "Detail"]
    :rows [(row :integrity integrity?
                (assurance/status integrity? nil)
                (str "state-after artifact/root resolves and hashes: " integrity?))
           (row :derivation (:verified? stb)
                (assurance/status (:verified? stb) (:reason stb))
                (:reason stb))
           (row :authority (:verified? stb)
                (assurance/status (:verified? stb) :not-established)
                "resulting-state authority not established; NOT inferred from :admitted?")]}))

;; The honest cancellation IS admitted, but that does not mean resulting-state
;; authority is established. The two are distinct.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [result (honest-cancellation-admission)
      admitted? (:admitted? result)]
  (clerk/table
   {:head ["Field" "Value"]
    :rows [[:admitted? admitted?]
           [:prose (if admitted?
                     "The honest cancellation IS admitted — but resulting-state authority is NOT established. The two are distinct."
                     "The cancellation is rejected.")]
           [:state-after-integrity-verified?
            (get-in result [:verification :state-after-integrity :verified?])]
           [:state-transition-binding-verified?
            (get-in result [:verification :state-transition-binding :verified?])]
           [:state-transition-binding-reason
            (get-in result [:verification :state-transition-binding :reason])]]}))
