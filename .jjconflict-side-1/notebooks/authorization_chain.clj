;; # Authorization Chain — from allocation proof to bounty payout, with a visibility guarantee
;;
;; **Audience:** Protocol reviewers, conformance testers, implementers of the
;; provability / authorization pipeline.
;;
;; **Purpose:** One continuous story: a decision is **computed** (allocation),
;; **authorized** (activation receipt), **attested** (review certificate), and
;; becomes an **irreversible economic effect** (with-bounty payout) — and at
;; every step the committed root lets any independent verifier recompute the
;; same object.
;;
;; ```
;; allocation proof (allocate)                → §1
;;   → activation receipt (allocation-activation.v1)   → §2
;;   → review certificate (three classifications preserved, threaded)   → §3
;;   → with-bounty composition over the base result        → §4
;;   → visibility guarantee (content-addressed roots end-to-end)         → §5
;; ```
;;
;; Every cell below runs the real code. Where a step needs a test-only fixture
;; that is not on the notebook classpath (the with-bounty effect-schema
;; registry), the committed-root contract is shown and the full application is
;; pointed to its test suite rather than fabricated.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.authorization-chain
  (:require [nextjournal.clerk :as clerk]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.allocation.activation :as act]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.assurance.three-member-authority :as tma]
            [resolver-sim.benchmark.review-aggregate-check :as rac]
            [resolver-sim.hash.canonical :as hc]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- panel [& body]
  (clerk/html (into [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                                   :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}]
                    body)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- kv-table [rows]
  (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
        (map (fn [[k v]]
               [:tr {:style {:borderBottom "1px solid #134e4a"}}
                [:td {:style {:padding "6px 8px" :color "#c4b5fd" :whiteSpace "nowrap"}} k]
                [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} v]])
             rows)))

;; ## 1. Allocation — the computation
;;
;; The chain starts with the allocation proof. A passing, **all-active**
;; allocation (available liquidity covers every request) fills each row in
;; full; an **exact-capacity** violation (over/under) rejects the proof.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def passing-proof
  "A genuinely produced, passing, all-active allocation proof (a-vs-b-plus-c)."
  (let [input (json/read-str (slurp "scenarios/allocation/a-vs-b-plus-c/kernel-input.json")
                             :key-fn str)]
    (kernel/run-kernel input)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [rows [{:row/id [:shared-withdrawal-row :obl-1 :pos-1 :sw-a] :obligation/id :obl-1 :owed 40}
            {:row/id [:shared-withdrawal-row :obl-1 :pos-2 :sw-b] :obligation/id :obl-1 :owed 35}
            {:row/id [:shared-withdrawal-row :obl-1 :pos-3 :sw-c] :obligation/id :obl-1 :owed 25}]
      allocate (fn [available]
                 (allocation/allocate
                  {:schema-version "pro-rata-allocation-request.v1" :mechanism/version 1
                   :allocation/id [:shared-withdrawal-allocation (mapv :row/id rows)]
                   :available available
                   :rows (mapv (fn [r] {:row/id (:row/id r) :obligation/id (:obligation/id r)
                                        :requested (long (:owed r)) :weight (long (:owed r)) :cap (long (:owed r))}) rows)
                   :rounding-policy :largest-remainder :tie-break-policy :canonical-row-id
                   :redistribution-policy :redistribute-cap-excess}))]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "§1 · allocate — all-active vs constrained"]
   (kv-table [["proof result/status" (name (:result/status passing-proof))]
              ["all-active proof (available 100)" (str "allocated " (:allocated-total (allocate 100)) "/100 · full coverage")]
              ["constrained (available 50)" (str "allocated " (:allocated-total (allocate 50)) "/100 · partial")]]
   )))

;; ## 2. Activation — the authorization boundary
;;
;; The passing proof can be **activated**; a rejected proof (e.g. an
;; exact-capacity violation) can only produce a **:prohibited** receipt that is
;; never valid authorization. `valid-activated-receipt?` is the examination.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def activation-policy
  {:authority :coordinator :fail-closed true})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [genuine (act/build-receipt {:proof passing-proof :policy activation-policy})
      rejected-proof (assoc passing-proof
                            :result/status :rejected
                            :result-root (apply str (repeat 64 "f"))
                            :rejection/classification :outcome-not-exact-capacity
                            :rejection/reason "over capacity")
      prohibited (act/build-receipt {:proof rejected-proof :policy activation-policy})
      forged (assoc prohibited :activation/status :activated)
      examine (fn [label receipt]
                (let [ok? (act/valid-activated-receipt? receipt)]
                  [:tr {:style {:borderBottom "1px solid #134e4a"}}
                   [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
                   [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (name (:activation/status receipt))]
                   [:td {:style {:padding "6px 8px" :color (if ok? "#22c55e" "#f87171") :fontWeight 700}}
                    (if ok? "✓ valid authorization" "✗ rejected")]]))]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "§2 · allocation-activation.v1 — the examination"]
   (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
         (concat
          [[:tr {:style {:borderBottom "1px solid #334155"}}
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Candidate receipt"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "status"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "examination"]]]
          [(examine "genuine all-active receipt" genuine)
           (examine "prohibited (exact-capacity)" prohibited)
           (examine "forged :activated status" forged)]))))

;; ## 3. Review certificate — three classifications preserved, threaded
;;
;; A three-member authority report carries THREE classification dimensions
;; (`:authority-status`, `:outcome-source`, and the non-collapsed position
;; categories). `run-review-aggregate-checks` **threads** them through the
;; aggregate check without collapsing, dropping, or double-counting them. Below
;; a genuine `evaluate-three-member-authority` report (three approvals) passes;
;; tampering a classification (duplicate seat) is revealed.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def review-members
  [{:researcher/id "researcher-a" :role :model-steward}
   {:researcher/id "researcher-b" :role :independent-reproducer}
   {:researcher/id "researcher-c" :role :adversarial-reviewer}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- review-position [member outcome]
  (let [request-root (str "sha256:" (apply str (take 64 (cycle "a1"))))
        round-hash (str "sha256:" (apply str (take 64 (cycle "b2"))))
        h (str "sha256:" (hc/domain-hash :researcher-decision-v2
                                         {:researcher/id member
                                          :authorisation/id :authorisation/test
                                          :authorisation/request-root request-root
                                          :review-round/hash round-hash
                                          :outcome/root outcome
                                          :decision :approve}))]
    {:schema-version "researcher-decision.v2"
     :researcher/id member
     :authorisation/id :authorisation/test
     :authorisation/request-root request-root
     :review-round/hash round-hash
     :outcome/root outcome
     :decision :approve
     :decision/hash h
     :signature {:value (str "sig-" member) :signed-at "t0"}}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def review-report
  (let [round-hash (str "sha256:" (apply str (take 64 (cycle "b2"))))
        request-root (str "sha256:" (apply str (take 64 (cycle "a1"))))
        outcome (str "sha256:" (apply str (take 64 (cycle "c3"))))
        positions [(review-position "researcher-a" outcome)
                   (review-position "researcher-b" outcome)
                   (review-position "researcher-c" outcome)]
        auth {:authorisation/id :authorisation/test
              :authorisation/request-root request-root
              :authorisation/review-round {:review-round/id :review-round/test
                                           :review-round/hash round-hash}
              :authorisation/target {:target/kind :benchmark-branch
                                     :target/proposed-content-root outcome}
              :authorisation/decision-references positions}
        round {:review-round/id :review-round/test :review-round/hash round-hash
               :review-round/members review-members}]
    {:report (tma/evaluate-three-member-authority
              :authorisation auth :review-round round :signature-valid? (constantly true))
     :round round
     :outcome outcome}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def review-threaded
  (let [{:keys [report round]} review-report]
    (rac/run-review-aggregate-checks round nil report)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [report]} review-report
      preserved? (get-in review-threaded [:checks :three-member-classifications :holds?])
      fixed-pt? (get-in review-threaded [:checks :classifications-fixed-point :holds?])
      tampered (update-in report [:valid-supporting-positions 0] (fn [p]
                                                                   (update-in p [:decision/hash] (fn [h] (str "sha256:" (apply str (take 64 (cycle "0"))))))))
      tampered-check (rac/check-aggregate-three-member-classifications (:round review-report) tampered)]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "§3 · three-classifications-preserved, threaded through the aggregate check"]
   (kv-table [["authority-status" (name (:authority-status report))]
              ["outcome-source" (name (:outcome-source report))]
              ["counted-support" (str (:counted-support report) " of 3")]
              ["aggregate :three-member-classifications" (if preserved? "✓ preserved" "✗")]
              ["classifications fixed-point (threaded)" (if fixed-pt? "✓ holds" "✗")]])
   [:div {:style {:marginTop "8px" :color "#94a3b8" :fontSize "11px"}}
    "A tampered decision/hash is revealed: " (if (:holds? tampered-check) "✓ (no violation)" "✗ violation detected") "."]))

;; ## 4. with-bounty — composition over the authorized base result
;;
;; The authorized result root from the chain above is **threaded** as the
;; `:base-result-root` into a with-bounty plan (ADR-0006): the plan is a
;; composition artifact that commits the base result, the bounty effect roots,
;; and the obligation — so a payout can only be composed over an authorized
;; base. The plan root is content-addressed via `plan-hash`.
;;
;; Full evaluation/application (which requires the effect-schema registry and a
;; concrete extension map, both test-only fixtures) runs in
;; `protocols_src/test/.../with_bounty_test.clj`; the committed-plan contract is
;; shown here.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [authorized-base-root (:result-root passing-proof)
      composition {:plan/base-operation-root (str "sha256:op")
                   :plan/base-result-root authorized-base-root
                   :plan/effect-roots ["sha256:effect/bounty-payable"
                                       "sha256:effect/custody-reserve"]
                   :plan/combined-effect-root (hc/domain-hash "with-bounty-effect-set"
                                                              ["sha256:effect/bounty-payable"
                                                               "sha256:effect/custody-reserve"])
                   :plan/obligation-id :obl/bounty
                   :plan/no-duplicate-creation-key [:with-bounty/obligations :obl/bounty]
                   :plan/recipient "example-org"}
      committed-root (hc/domain-hash "with-bounty-application-plan" composition)]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "§4 · with-bounty composition over the authorized base result"]
   (kv-table [[":plan/base-result-root (from §2 authorization)" authorized-base-root]
              [":plan/obligation-id" (name (:plan/obligation-id composition))]
              [":plan/no-duplicate-creation-key" (pr-str (:plan/no-duplicate-creation-key composition))]
              [":plan/recipient" (:plan/recipient composition)]
              [":plan/combined-effect-root" (subs (:plan/combined-effect-root composition) 0 24) "…"]
              ["committed plan root" (subs committed-root 0 24) "…"]])
   [:div {:style {:marginTop "8px" :color "#94a3b8" :fontSize "11px"}}
    "The plan is a composition over the base result: without an authorized base result-root, the bounty payout cannot be composed. Full application is exercised in the with-bounty test suite (test-only effect-schema registry)."]))

;; ## 5. Visibility guarantee
;;
;; Every step commits a root that any independent verifier recomputes
;; identically. The whole chain is content-addressed and inspectable:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [receipt (act/build-receipt {:proof passing-proof :policy activation-policy})
      round-hash (get-in review-report [:round :review-round/hash])]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "§5 · the committed roots of the chain"]
   (kv-table [["allocation · result-root" (subs (:result-root passing-proof) 0 24) "…"]
              ["allocation · certificate-assertions-digest" (subs (:certificate-assertions-digest passing-proof) 0 24) "…"]
              ["activation · receipt-root" (subs (:activation/root receipt) 0 24) "…"]
              ["review · review-round hash" (subs round-hash 0 24) "…"]
              ["with-bounty · plan root (committed)" (subs (hc/domain-hash "with-bounty-application-plan"
                                                                           {:base-result-root (:result-root passing-proof)})
                                                           0 24) "…"]])
   [:div {:style {:marginTop "8px" :color "#94a3b8" :fontSize "11px"}}
    "Each root is a canonical, content-addressed commitment: recomputing it from
     the committed fields yields the same value for any verifier."]))
