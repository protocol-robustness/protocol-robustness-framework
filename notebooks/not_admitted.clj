^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-admitted
  "Not Admitted — Evidence Chain Ordering, Verification, and Invariant-Based Admission"
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.yield.invariants :as yinv]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]))

;; # Not Admitted
;; ## Evidence Chain Ordering, Verification, and Invariant-Based Admission

;; ---
;; ## 1. Evidence Chain Ordering

;; A hash-linked evidence chain commits each record to its position.
;; Three records with distinct content hashes:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def content-hashes
  [(hc/hash-with-intent {:hash/intent :evidence-content} {:event :deposit  :amount 100})
   (hc/hash-with-intent {:hash/intent :evidence-content} {:event :dispute  :id "0x1"})
   (hc/hash-with-intent {:hash/intent :evidence-content} {:event :resolve  :outcome :release})])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head [:seq :content-hash]
  :rows (map-indexed (fn [i h] [(inc i) (str (subs h 0 20) "...")]) content-hashes)})

;; Chain-link hashes commit to content, seq, and predecessor:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def chain-links
  (let [link1 (chain/chain-link-hash (nth content-hashes 0) 1 nil)
        link2 (chain/chain-link-hash (nth content-hashes 1) 2 link1)
        link3 (chain/chain-link-hash (nth content-hashes 2) 3 link2)]
    [{:chain-seq 1 :content-hash (nth content-hashes 0) :chain-self-hash link1
      :chain-prev-hash nil}
     {:chain-seq 2 :content-hash (nth content-hashes 1) :chain-self-hash link2
      :chain-prev-hash link1}
     {:chain-seq 3 :content-hash (nth content-hashes 2) :chain-self-hash link3
      :chain-prev-hash link2}]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head [:seq :content-hash :prev-hash :self-hash]
  :rows (mapv (fn [l]
                [(str (:chain-seq l))
                 (str (subs (:content-hash l) 0 12) "...")
                 (if (:chain-prev-hash l) (str (subs (:chain-prev-hash l) 0 12) "...") "—")
                 (str (subs (:chain-self-hash l) 0 12) "...")])
              chain-links)})

;; ---
;; ## 2. Chain-Specific Ordering

;; Evidence has two hash commitments:
;;
;; **Content hash** (`:evidence-content` intent) — order-independent.
;; Same content always produces the same hash regardless of chain position.
;;
;; **Link hash** (`:evidence-chain-link-v1` intent) — order-dependent.
;; Commits to content-hash + chain-seq + prev-link-hash. Moving a record
;; to a different position changes its link hash.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px" :font-family "monospace" :border-radius "4px"}}
  [:div "Content hash (same regardless of position):  " [:strong {:style {:color "#22c55e"}} (str (subs (nth content-hashes 1) 0 16) "...")]]
  [:div "Link hash at seq 2 (depends on prev):         " [:strong {:style {:color "#7ADDDC"}} (str (subs (:chain-self-hash (nth chain-links 1)) 0 16) "...")]]
  [:div "Link hash at seq 1 if reordered:              " [:strong {:style {:color "#f59e0b"}} (str (subs (chain/chain-link-hash (nth content-hashes 1) 1 nil) 0 16) "...")]]])

;; ---
;; ## 3. Chain Spec

;; A valid chain link (`:evidence-chain-link-v1`) commits to exactly four fields:
;;
;; | Field | Description |
;; |-------|-------------|
;; | ` :chain/hash-scheme` | Currently `"link-v1"` |
;; | `:evidence/hash` | The content hash of the evidence record |
;; | `:evidence/chain-seq` | Monotonically increasing integer |
;; | `:evidence/chain-prev-hash` | Predecessor's chain-self-hash (nil for seq 1) |

;; The chain cursor tracks `{:seq N :last-hash <prev-self-hash>}` and the
;; `verify-scenario-chain` function validates five properties:
;;   • scheme — every record uses `"link-v1"`
;;   • hash — each self-hash matches `(chain-link-hash ...)`
;;   • link — each prev-hash matches predecessor's self-hash
;;   • sequence — no gaps, no duplicates
;;   • identity — all records share the same scenario-id

;; ---
;; ## 4. Order Invariance

;; The evidence hash set root (`:run-evidence-hash-set-v1`) commits to an
;; unordered set of content hashes — sorting and deduplication happen before
;; hashing. Same evidence, any order, produces the same root.
;;
;; Contrast: chain links are order-dependent; the hash set root is not.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def hash-set-root
  (chain/evidence-hash-set-root content-hashes))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px" :font-family "monospace" :border-radius "4px"}}
  [:div "Hash set root (sorted, deduped):   " [:strong {:style {:color "#22c55e"}} hash-set-root]]
  [:div "Same root with reversed order:     " [:strong {:style {:color "#22c55e"}} (chain/evidence-hash-set-root (reverse content-hashes))]]
  [:div {:style {:margin-top "8px" :color "#94a3b8" :font-size "12px"}}
   "The :run-evidence-hash-set-v1 intent explicitly excludes :artifact-order."]])

;; ---
;; ## 5. Chain Verification — Admitted vs Not Admitted

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn- build-chain-records
  "Convert chain-links to verify-scenario-chain format."
  [links]
  (mapv (fn [l]
          {:evidence/hash (:content-hash l)
           :evidence/chain-seq (:chain-seq l)
           :evidence/chain-prev-hash (:chain-prev-hash l)
           :evidence/chain-self-hash (:chain-self-hash l)
           :evidence/chain-hash-scheme "link-v1"
           :scenario/id "demo"})
        links))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def valid-chain (build-chain-records chain-links))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def chain-verification
  (chain/verify-scenario-chain valid-chain))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#064e3b" :border "1px solid #22c55e" :color "#e2e8f0" :padding "12px"
                :font-family "monospace" :border-radius "4px"}}
  [:span {:style {:color "#22c55e"}} "✓ "]
  "Chain status: " (pr-str (:chain/status chain-verification))
  "  |  Records: " (:chain/record-count chain-verification)
  "  |  Links valid? " (:chain/links-valid? chain-verification)])

;; Four failure modes that cause evidence to be *not admitted*:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def chain-failure-modes
  [{:label "Hash mismatch — tampered self-hash"
    :chain (let [bad (update (first valid-chain) :evidence/chain-self-hash (fn [_] "0xtampered"))
                rest (subvec valid-chain 1)]
             (into [bad] rest))
    :expected-reason :chain-link-hash-mismatch}
   {:label "Link break — wrong prev-hash"
    :chain (let [good (first valid-chain)
                bad (assoc (second valid-chain) :evidence/chain-prev-hash "0xbroken")]
             [good bad (nth valid-chain 2)])
    :expected-reason :predecessor-mismatch}
   {:label "Sequence gap — missing seq 2"
    :chain [(first valid-chain) (nth valid-chain 2)]
    :expected-reason :non-contiguous-sequence}
   {:label "Wrong scheme"
    :chain [(assoc (first valid-chain) :evidence/chain-hash-scheme "link-v0")
            (second valid-chain) (nth valid-chain 2)]
    :expected-reason :unsupported-chain-hash-scheme}])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head [:failure :status :reason]
  :rows (mapv (fn [{:keys [label chain expected-reason]}]
                (let [result (chain/verify-scenario-chain chain)
                      errors (:chain/errors result)
                      match (some #(= expected-reason (:reason %)) errors)]
                  [label (if match "DETECTED" "MISSED")
                   (pr-str expected-reason)]))
              chain-failure-modes)})

;; ---
;; ## 6. Chain Status Reference

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head [:status :meaning]
  :rows [[":empty" "No records in the chain"]
         [":verified" "All records valid: scheme, hashes, links, sequence"]
         [":invalid" "One or more validation errors — evidence not admitted"]]})

;; ===========================================================================
;; 7. Propagate Pro-Rata (Invariant)
;; ===========================================================================

;; The `:yield/pro-rata-propagation-complete` invariant checks that every
;; persisted shared pro-rata outcome has been applied exactly once and that
;; entitlement, capacity, and accounting are conserved.

;; Build a minimal valid world using production functions:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- build-minimal-propagation-world
  "Construct a world state with one valid pro-rata propagation, its matching
   decision, and the involved positions. Returns a map ready for invariant checks."
  []
  (let [;; Minimal allocation via the canonical pro-rata engine
        alloc (pro-rata/allocate
               {:allocation/id [:demo]
                :rows [{:row/id [:row "alice"]
                        :obligation/id "obl-1"
                        :requested 200
                        :weight 200
                        :cap 200}]
                :available 200
                :rounding-policy :largest-remainder
                :tie-break-policy :canonical-row-id
                :redistribution-policy :unallocated})
        mechanism-evidence (pro-rata-evidence/mechanism-evidence-artifact alloc)
        alloc-row {:key "alice" :obligation-id "obl-1" :source-position-id "pos-1"
                   :filled 200 :deferred 0 :effective-cap 200 :owed 200}
        decision-id "demo-decision-1"
        decision {:decision/id decision-id
                  :decision/hash (hc/domain-hash "DECISION_V1" {:id decision-id})
                  :evidence {:allocation-rows [alloc-row]
                             :allocation-mechanism {:allocation/id (:allocation/id alloc)
                                                   :allocation/hash (:allocation/hash alloc)
                                                   :mechanism (:mechanism alloc)}
                             :allocation-mechanism-evidence mechanism-evidence
                             :available-liquidity 200}
                  :allocation/invocation-context {:step 1 :scenario-id "demo"}
                  :token :USDC
                  :module/id :demo-module}
        raw-policy propagation-policy/shared-withdrawal-policy
        policy-selection {:policy-source :default}
        propagation (pf/pro-rata-propagation-artifact decision raw-policy policy-selection)
        prop-id (:propagation/id propagation)]
    {:yield/pro-rata-propagations {prop-id propagation}
     :yield/partial-fill-decisions {decision-id decision}
     :yield/positions {"alice" {:status :withdrawn :shortfall {:deferred-amount 0}}}}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def happy-world (build-minimal-propagation-world))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def propagation-invariant-result
  (yinv/check-pro-rata-propagation-complete happy-world))

;; The invariant returns per-check status and a `:holds?` summary:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [r propagation-invariant-result
       checks (dissoc r :holds? :violations)]
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :font-family "monospace" :border-radius "4px"}}
    [:div "Overall: " [:strong {:style {:color (if (:holds? r) "#22c55e" "#ef4444")}} (if (:holds? r) "PASS" "FAIL")]]
    (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px" :margin-top "8px"}}]
          (mapv (fn [[k v]]
                  (let [v-str (if (keyword? v) (name v) (pr-str v))
                        pass? (or (= :pass v) (= "pass" v-str))]
                    [:tr {:key (name k) :style {:border-bottom "1px solid #134e4a"}}
                     [:td {:style {:padding "4px 8px" :color "#94a3b8"}} (name k)]
                     [:td {:style {:padding "4px 8px" :color (if pass? "#22c55e" "#ef4444")}} v-str]]))
               (sort-by first checks)))]))

;; Now show a failure: entitlement not conserved.
;; The participant's fulfilled total no longer matches their eligible obligation:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def propagation-failure
  (let [w (reduce-kv (fn [m k v]
                       (assoc m k (-> v
                                     (assoc-in [:summary :allocated] 150)
                                     (assoc-in [:participants 0 :fulfilled] 150))))
                     {} (:yield/pro-rata-propagations happy-world))
        w2 (assoc happy-world :yield/pro-rata-propagations w)]
    (yinv/check-pro-rata-propagation-complete w2)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#450a0a" :border "1px solid #ef4444" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Overall: " [:strong {:style {:color "#ef4444"}} "FAIL"]]
  [:div {:style {:margin-top "8px" :font-size "13px"}}
   "Violations:"]
  (into [:ul {:style {:margin-top "4px" :font-size "12px" :color "#fbbf24"}}]
        (map (fn [v] [:li (pr-str v)]) (:violations propagation-failure)))])

;; ---
;; ## 8. Pro-Rata Accounting Reconciles (Invariant)

;; The `:yield/pro-rata-accounting-reconciles` invariant reconciles each
;; propagation with its committed application snapshot — checking hash
;; consistency, source arithmetic, and balanced entries.

;; Extend the happy world with a matching application record:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn- inject-application
  "Add a valid application record matching the propagation."
  [world]
  (let [prop (first (vals (:yield/pro-rata-propagations world)))
        app-hash (pf/application-hash
                  {:participants [{:participant-id "alice"
                                   :obligation-id "obl-1"
                                   :withdrawn {:token :USDC :before 200 :delta 200 :after 0}}]
                   :accounting-entries (:accounting-entries prop)
                   :propagation/reference {:propagation/id (:propagation/id prop)
                                           :propagation/hash (:propagation/hash prop)
                                           :propagation/content-hash (:propagation/content-hash prop)}
                   :allocation/invocation-context (:allocation/invocation-context prop)
                   :calculation-id (:calculation-ref prop)
                   :outcome-hash (:outcome-ref prop)
                   :policy-hash (get-in prop [:propagation-policy :policy/hash])
                   :accounting-entry-set-hash (pf/accounting-entry-set-hash (:accounting-entries prop))
                   :source-account {:before 200 :after 0 :delta -200}
                   :residual {:token :USDC :available 200 :allocated 200 :amount 0 :destination :protocol}})]
    (assoc-in world [:yield/applied-pro-rata-propagations (:propagation/id prop)]
              {:schema-version "pro-rata-propagation-application.v3"
               :application/hash app-hash
               :propagation/reference {:propagation/id (:propagation/id prop)
                                       :propagation/hash (:propagation/hash prop)
                                       :propagation/content-hash (:propagation/content-hash prop)}
               :allocation/invocation-context (:allocation/invocation-context prop)
               :calculation-id (:calculation-ref prop)
               :outcome-hash (:outcome-ref prop)
               :policy-hash (get-in prop [:propagation-policy :policy/hash])
               :accounting-entry-set-hash (pf/accounting-entry-set-hash (:accounting-entries prop))
               :source-account {:before 200 :after 0 :delta -200}
               :participants [{:participant-id "alice" :obligation-id "obl-1"
                               :withdrawn {:token :USDC :before 200 :delta 200 :after 0}}]
               :residual {:token :USDC :available 200 :allocated 200 :amount 0 :destination :protocol}})))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def accounting-reconcile-result
  (yinv/check-pro-rata-accounting-reconciles (inject-application happy-world)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [r accounting-reconcile-result
       checks (dissoc r :holds? :violations)]
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :font-family "monospace" :border-radius "4px"}}
    [:div "Overall: " [:strong {:style {:color (if (:holds? r) "#22c55e" "#ef4444")}} (if (:holds? r) "PASS" "FAIL")]]
    (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px" :margin-top "8px"}}]
          (mapv (fn [[k v]]
                  (let [v-str (if (keyword? v) (name v) (pr-str v))
                        pass? (or (= :pass v) (= "pass" v-str))]
                    [:tr {:key (name k) :style {:border-bottom "1px solid #134e4a"}}
                     [:td {:style {:padding "4px 8px" :color "#94a3b8"}} (name k)]
                     [:td {:style {:padding "4px 8px" :color (if pass? "#22c55e" "#ef4444")}} v-str]]))
               (sort-by first checks)))]))

;; Two targeted failures:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def accounting-failures
  [{:label "Application hash mismatch"
    :world (let [w (inject-application happy-world)
                 prop-id (-> w :yield/pro-rata-propagations keys first)]
             (assoc-in w [:yield/applied-pro-rata-propagations prop-id :application/hash] "0xtampered"))
    :expected-check :application-binding-valid}
   {:label "Accounting imbalance"
    :world (let [w (inject-application happy-world)
                 prop-id (-> w :yield/pro-rata-propagations keys first)]
             (update-in w [:yield/applied-pro-rata-propagations prop-id :source-account]
                        assoc :before 200 :after 100 :delta -50))
    :expected-check :application-binding-valid}])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head [:failure :check-status :detected?]
  :rows (mapv (fn [{:keys [label world expected-check]}]
                (let [r (yinv/check-pro-rata-accounting-reconciles world)
                      status (get-in r [:checks expected-check])]
                  [label (name status) (not= :pass status)]))
              accounting-failures)})

;; ---
;; ## 9. Summary

;; The three verification surfaces in this notebook enforce different properties:
;;
;; | Surface | What it verifies | Admission criterion |
;; |---------|-----------------|---------------------|
;; | **Chain verifier** | Link hashes, predecessor links, sequence contiguity, scheme | All checks pass → `:verified` |
;; | **Propagation complete** | Unique application, entitlement conservation, capacity bounds, position state effects | `:checks` all `:pass` |
;; | **Accounting reconciles** | Application hash integrity, source arithmetic, balanced entries | `:holds?` true |
;;
;; - The **chain verifier** ensures evidence is hash-linked and ordered correctly.
;; - **Propagation completeness** ensures a pro-rata outcome was applied once and that
;;   every participant's entitlement, capacity, and position reflect the outcome.
;; - **Accounting reconciliation** ensures the application snapshot is hash-consistent
;;   and that the source debits and participant credits balance.
;;
;; These are independent verification surfaces. A valid chain does not imply valid
;; propagation, and vice versa — each must be checked separately.
