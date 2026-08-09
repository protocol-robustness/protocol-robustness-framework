;; # Allocation Activation v1 — Receipt, All-Active No-Churn, and the Prohibited Boundary
;;
;; **Audience:** Protocol reviewers, conformance testers, implementers of the
;; allocation activation ABI.
;;
;; **Purpose:** Make the `allocation-activation.v1` receipt *visible*. Activation
;; is a separate authenticated step from the allocation proof:
;;
;; - A **passing** proof can be activated → `:activated` receipt.
;; - A **rejected** proof can only ever produce a `:prohibited` receipt that
;;   binds the rejection classification.
;;
;; Verification failure is an **authorization boundary**, not metadata. A
;; `:prohibited` receipt is never valid for authorization — even if an attacker
;; overwrites the status to `:activated`.
;;
;; **All-active no-churn:** when the allocation is all-active (no rejection, no
;; deferred/haircut fail action), the receipt's bound `:result-root` is
;; byte-identical to the unfiltered result-root — the rejection/fail-action
;; filter is a no-op, so activating an all-active allocation introduces no hash
;; churn.
;;
;; **Data contract:** the genuine passing proof is produced by the real
;; allocation kernel over `scenarios/allocation/a-vs-b-plus-c/kernel-input.json`.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.allocation-activation
  (:require [nextjournal.clerk :as clerk]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.allocation.activation :as act]
            [resolver-sim.allocation.realized-statement :as rs]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.pro-rata.allocation :as allocation]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def passing-proof
  "A genuinely produced, passing, all-active allocation proof (a-vs-b-plus-c)."
  (let [input (json/read-str (slurp "scenarios/allocation/a-vs-b-plus-c/kernel-input.json")
                             :key-fn str)]
    (kernel/run-kernel input)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def activation-policy
  {:authority :coordinator :fail-closed true})

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

;; ## 1. Receipt anatomy — allocation-activation.v1
;;
;; A receipt binds the proof to an activation decision:
;;
;; ```
;; {:activation/schema-version "allocation-activation.v1"
;;  :proof-root    <certificate-assertions-digest of the proof>
;;  :result-root   <committed result root the proof establishes>
;;  :rejection/classification <nil when passing; the classification when rejected>
;;  :activation/status        :activated | :prohibited
;;  :activation-policy-root   <committed activation policy>
;;  :activation/root          <content-addressed receipt root>}
;; ```

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [receipt (act/build-receipt {:proof passing-proof :policy activation-policy})]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "allocation-activation.v1 receipt — genuine passing proof"]
   (kv-table [["schema-version" (:activation/schema-version receipt)]
              ["activation/status" (name (:activation/status receipt))]
              ["rejection/classification" (pr-str (:rejection/classification receipt))]
              ["proof-root == certificate digest" (if (= (:proof-root receipt)
                                                         (:certificate-assertions-digest passing-proof))
                                                    "✓" "✗")]
              ["valid authorization?" (if (act/valid-activated-receipt? receipt) "✓ YES" "✗ NO")]])))

;; ## 2. All-active no-churn
;;
;; The genuine proof is **all-active**: passing, no rejection, and no
;; deferred/haircut fail action. For an all-active proof the rejection/fail-action
;; filter is a no-op, so the receipt's bound `:result-root` is byte-identical to
;; the proof's unfiltered `:result-root` — activation introduces no hash churn.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [receipt (act/build-receipt {:proof passing-proof :policy activation-policy})]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "all-active no-churn — activated without hash churn"]
   (kv-table [["proof result/status" (name (:result/status passing-proof))]
              ["all-active?" (if (act/all-active? passing-proof) "✓" "✗")]
              ["all-active-no-churn?" (if (act/all-active-no-churn?
                                            {:proof passing-proof :policy activation-policy})
                                        "✓ (no churn)" "✗")]
              ["receipt bound result-root == unfiltered result-root"
               (if (= (:result-root receipt) (act/no-churn-root passing-proof))
                 "✓ byte-identical" "✗")]])))

;; ## 3. Rejection ⇒ :prohibited (never valid authorization)
;;
;; A rejected proof always yields a `:prohibited` receipt, and a prohibited
;; receipt is never valid for authorization. Each row is a rejection
;; classification the kernel can emit; the activation boundary is fail-closed
;; for every one of them. `exact-capacity` and `forbidden-authorized`
;; (ineligible claimant) are the two spelled out in detail below.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def rejection-classifications
  [{:label "exact-capacity" :classification :outcome-not-exact-capacity
    :property "outcomes must sum to exactly capacity (no over/under fill)"}
   {:label "forbidden-authorized (ineligible claimant)" :classification :ineligible-claimant
    :property "every claimant must be eligible / authorized; a forbidden claimant is not admitted"}
   {:label "rates-not-canonical" :classification :rates-not-canonical
    :property "proposed rates must be reduced exact ratios"}
   {:label "rates-not-sum-to-one" :classification :rates-not-sum-to-one
    :property "proposed rates must sum to 1"}
   {:label "all-or-nothing" :classification :allocation-not-all-or-nothing
    :property "each outcome must be allocated in full or not at all"}
   {:label "duplicate-claim" :classification :duplicate-claim-in-outcome
    :property "no claim may appear twice in an outcome"}
   {:label "empty-outcome" :classification :empty-outcome-set
    :property "the outcome set must not be empty"}
   {:label "proportionality" :classification :proportionality-failure
    :property "allocations must be proportional to declared rates"}
   {:label "selected-outcome" :classification :selected-outcome-mismatch
    :property "the selected outcome must be a member of the outcome set"}])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
  "Rejected proof ⇒ :prohibited receipt — never valid authorization"]
 (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
       (concat
        [[:tr {:style {:borderBottom "1px solid #334155"}}
          [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Case"]
          [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Violated property"]
          [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "classification"]
          [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "receipt status"]
          [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "valid authorization?"]]]
        (for [{:keys [label classification property]} rejection-classifications]
          (let [rejected {:result/status :rejected
                          :result-root (apply str (repeat 64 "f"))
                          :rejection/classification classification
                          :rejection/reason label}
                rcpt (act/build-receipt {:proof rejected :policy activation-policy})]
            [:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
             [:td {:style {:padding "6px 8px" :color "#fbbf24"}} property]
             [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (name classification)]
             [:td {:style {:padding "6px 8px" :color "#f87171"}} (name (:activation/status rcpt))]
             [:td {:style {:padding "6px 8px" :color "#22c55e" :fontWeight 700}}
              (if (act/valid-activated-receipt? rcpt) "✗ VALID(!!)" "✓ never valid")]])))))

;; ## 4. fraction-covered — how much of each request the allocation covers
;;
;; For a realized allocation, the **covered fraction** of a claim is
;; `filled / requested`. An all-active allocation covers every request fully
;; (covered fraction = 100%, every disposition `:full-fill`). A partial fill
;; covers less (covered fraction < 100%), produces `:deferred` / `:partial-fill`
;; dispositions, and is **not** all-active — so the fail-action filter is not a
;; no-op and no-churn does not hold.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def all-active-decision
  {:requested {:A 50 :B 30 :C 20}
   :filled {:A 50 :B 30 :C 20}
   :deferred {} :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def partial-fill-decision
  {:requested {:A 50 :B 30 :C 20}
   :filled {:A 25 :B 15 :C 20}
   :deferred {:A 25 :B 15} :haircut {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- covered-fraction
  "Overall covered fraction = total filled / total requested (0..1)."
  [decision]
  (let [r (reduce + (vals (:requested decision)))
        f (reduce + (vals (:filled decision)))]
    (if (zero? r) 1.0 (double (/ f r)))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
  "fraction-covered — covered vs requested"]
 (kv-table [["all-active covered-fraction" (format "%.0f%%" (* 100 (covered-fraction all-active-decision)))]
            ["all-active?" (if (rs/all-active? all-active-decision) "✓" "✗")]
            ["partial-fill covered-fraction" (format "%.0f%%" (* 100 (covered-fraction partial-fill-decision)))]
            ["partial-fill all-active?" (if (rs/all-active? partial-fill-decision) "✓" "✗")]
            ["partial-fill dispositions"
             (pr-str (mapv (fn [k] (rs/disposition-of {:requested (long (get-in partial-fill-decision [:requested k] 0))
                                                       :filled (long (get-in partial-fill-decision [:filled k] 0))
                                                       :deferred (long (get-in partial-fill-decision [:deferred k] 0))
                                                       :haircut (long (get-in partial-fill-decision [:haircut k] 0))}))
                           (sort (keys (:requested partial-fill-decision)))))]])
 [:div {:style {:marginTop "8px" :color "#94a3b8" :fontSize "11px"}}
  "Full coverage ⇒ all-active ⇒ no-churn.  Partial coverage (covered-fraction < 100%) ⇒ not all-active ⇒ the fail-action filter is no longer a no-op."])

;; ## 5. Receipt examination — is it valid authorization?
;;
;; `valid-activated-receipt?` is the authorization boundary. It accepts only a
;; receipt that is `:activated`, binds a proof with no rejection, and whose root
;; recomputes. Examining each candidate below: a genuine activated receipt passes;
;; a prohibited receipt, a forged `:activated` status, and a tampered root all fail.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [genuine (act/build-receipt {:proof passing-proof :policy activation-policy})
      rejected-proof (assoc passing-proof
                            :result/status :rejected
                            :result-root (apply str (repeat 64 "f"))
                            :rejection/classification :result-root-mismatch
                            :rejection/reason "mutated result root")
      prohibited (act/build-receipt {:proof rejected-proof :policy activation-policy})
      forged (assoc prohibited :activation/status :activated)
      tampered (assoc genuine :activation/root (apply str (repeat 64 "0")))
      examine (fn [label receipt]
                (let [ok? (act/valid-activated-receipt? receipt)]
                  [:tr {:style {:borderBottom "1px solid #134e4a"}}
                   [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
                   [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (name (:activation/status receipt))]
                   [:td {:style {:padding "6px 8px" :color (if ok? "#22c55e" "#f87171") :fontWeight 700}}
                    (if ok? "✓ valid authorization" "✗ rejected")]]))]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "Receipt examination — valid-activated-receipt?"]
   (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
         (concat
          [[:tr {:style {:borderBottom "1px solid #334155"}}
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Candidate receipt"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "status"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "examination"]]]
          [(examine "genuine activated receipt" genuine)
           (examine "prohibited receipt (rejected proof)" prohibited)
           (examine "forged :activated status" forged)
           (examine "tampered root" tampered)]))))

;; ## 6. Gallery summary
;;
;; Candidate → what is violated / asserted → admission outcome.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [row (fn [candidate outcome note ok?]
            [:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} candidate]
             [:td {:style {:padding "6px 8px" :color "#fbbf24"}} outcome]
             [:td {:style {:padding "6px 8px" :color (if ok? "#22c55e" "#f87171") :fontWeight 700}} note]])]
  (panel
   (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
         (concat
          [[:tr {:style {:borderBottom "1px solid #334155"}}
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Candidate"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Violated property"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "NOT ADMITTED"]]]
          [(row "genuine all-active proof" "none — all assertions hold" ":activated (valid)" true)
           (row "exact-capacity violation" "outcomes not exactly capacity" ":prohibited" false)
           (row "forbidden-authorized (ineligible)" "claimant not eligible/authorized" ":prohibited" false)
           (row "partial fill (covered-fraction < 100%)" "not all-active — fail-action filter active" "not all-active / no-churn" false)
           (row "forged / tampered receipt" "status overwritten or root does not recompute" "invalid authorization" false)]))))

;; ## 7. Shared-withdrawal allocation — all-active
;;
;; `allocate-shared-withdrawal-rows` (resolver-sim.yield.partial-fill) adapts
;; shared-withdrawal rows to the **public** pro-rata mechanism boundary
;; (`resolver-sim.pro-rata.allocation/allocate`).  When the available liquidity
;; covers every request, the allocation is **all-active**: each row is filled in
;; full, total allocated == total owed, and the covered-fraction is 100% (no
;; shortfall).  When liquidity is constrained, the fill is pro-rata and partial —
;; covered-fraction < 100%, so the allocation is NOT all-active.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- shared-withdrawal-allocate
  "Adapt shared-withdrawal rows to the public pro-rata mechanism (the same
   boundary allocate-shared-withdrawal-rows delegates to)."
  [available rows]
  (allocation/allocate
   {:schema-version "pro-rata-allocation-request.v1"
    :mechanism/version 1
    :allocation/id [:shared-withdrawal-allocation (mapv :row/id rows)]
    :available available
    :rows (mapv (fn [row]
                  {:row/id (:row/id row)
                   :obligation/id (:obligation/id row)
                   :requested (long (:owed row))
                   :weight (long (or (:weight row) (:owed row)))
                   :cap (long (:owed row))})
                rows)
    :rounding-policy :largest-remainder
    :tie-break-policy :canonical-row-id
    :redistribution-policy :redistribute-cap-excess}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def shared-withdrawal-rows
  [{:row/id [:shared-withdrawal-row :obl-1 :pos-1 :sw-a] :obligation/id :obl-1 :owed 40}
   {:row/id [:shared-withdrawal-row :obl-1 :pos-2 :sw-b] :obligation/id :obl-1 :owed 35}
   {:row/id [:shared-withdrawal-row :obl-1 :pos-3 :sw-c] :obligation/id :obl-1 :owed 25}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- covered-fraction*
  "Overall covered fraction of an allocation result: total allocated / total requested."
  [result]
  (let [req (reduce + 0 (map :requested (:rows result)))
        al  (reduce + 0 (map :allocated (:rows result)))]
    (if (zero? req) 1.0 (double (/ al req)))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [aa (shared-withdrawal-allocate 100 shared-withdrawal-rows)
      cx (shared-withdrawal-allocate 50 shared-withdrawal-rows)
      cx-by-id (into {} (map (juxt :row/id identity)) (:rows cx))
      rows (:rows aa)]
  (panel
   [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
    "Shared-withdrawal allocation — allocate-shared-withdrawal-rows"]
   (kv-table [["all-active · available 100" (format "allocated %s/100 · covered-fraction %.0f%%"
                                                    (:allocated-total aa) (* 100 (covered-fraction* aa)))]
              ["all-active?" (if (= (:allocated-total aa) (bigint 100)) "✓ full coverage (all-active)" "✗")]
              ["constrained · available 50" (format "allocated %s/100 · covered-fraction %.0f%%"
                                                    (:allocated-total cx) (* 100 (covered-fraction* cx)))]
              ["constrained all-active?" (if (= (:allocated-total cx) (bigint 100))
                                           "✓" "✗ partial coverage — NOT all-active")]])
   (into [:table {:style {:width "100%" :borderCollapse "collapse" :marginTop "8px"}}]
         (concat
          [[:tr {:style {:borderBottom "1px solid #334155"}}
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "row"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "owed"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "allocated (all-active)"]
            [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "allocated (constrained)"]]]
          (for [row rows]
            (let [cx-row (get cx-by-id (:row/id row))]
              [:tr {:style {:borderBottom "1px solid #134e4a"}}
               [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (name (last (:row/id row)))]
               [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} (str (:requested row))]
               [:td {:style {:padding "6px 8px" :color "#22c55e"}} (str (:allocated row))]
               [:td {:style {:padding "6px 8px" :color "#fbbf24"}} (str (:allocated cx-row))]]))))))
