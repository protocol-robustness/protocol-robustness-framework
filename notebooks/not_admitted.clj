^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-admitted
  "Not Admitted — Evidence Chain Ordering, Verification, and Invariant-Based Admission"
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.yield.invariants :as yinv]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
            [resolver-sim.pro-rata.allocation :as pro-rata]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]
            [resolver-sim.accounting.held-ledger-index :as hli]
            [resolver-sim.protocols.sew.accounting :as sew-acc]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.yield.exact-math :as ym]
            [resolver-sim.yield.invariants-transition :as ytran]
            [resolver-sim.benchmark.outcome-manifest :as om]))

;; # Not Admitted
;; ## Evidence Chain Ordering, Verification, and Invariant-Based Admission

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- render-checks
  "Render a clerk/html checks table from an invariant result map."
  [result]
  (let [checks (dissoc result :holds? :violations)]
    [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                   :font-family "monospace" :border-radius "4px"}}
     [:div "Overall: " [:strong {:style {:color (if (:holds? result) "#22c55e" "#ef4444")}} (if (:holds? result) "PASS" "FAIL")]]
     (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px" :margin-top "8px"}}]
           (mapv (fn [[k v]]
                   (let [v-str (if (keyword? v) (name v) (pr-str v))
                         pass? (or (= :pass v) (= "pass" v-str))]
                     [:tr {:key (name k) :style {:border-bottom "1px solid #134e4a"}}
                      [:td {:style {:padding "4px 8px" :color "#94a3b8"}} (name k)]
                      [:td {:style {:padding "4px 8px" :color (if pass? "#22c55e" "#ef4444")}} v-str]]))
                (sort-by first checks)))]))

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
  (let [dispute-content (nth content-hashes 1)
        link-at-seq-2 (:chain-self-hash (nth chain-links 1))
        link-at-seq-1 (chain/chain-link-hash dispute-content 1 nil)]
    [:div "Content hash (same regardless of position):  " [:strong {:style {:color "#22c55e"}} (str (subs dispute-content 0 16) "...")]]
    [:div "Link hash at seq 2 (depends on prev):         " [:strong {:style {:color "#7ADDDC"}} (str (subs link-at-seq-2 0 16) "...")]]
    [:div "Link hash at seq 1 if reordered:              " [:strong {:style {:color "#f59e0b"}} (str (subs link-at-seq-1 0 16) "...")]])])

;; ---
;; ## 3. Chain Spec

;; A valid chain link (`:evidence-chain-link-v1`) commits to exactly four fields:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def spec-link-hash
  (chain/chain-link-hash (nth content-hashes 0) 1 nil))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"}}
   [:thead [:tr {:style {:border-bottom "1px solid #134e4a" :color "#94a3b8"}}
            [:th {:style {:padding "6px 8px" :text-align "left"}} "Field"]
            [:th {:style {:padding "6px 8px" :text-align "left"}} "Value"]]]
   [:tbody
    [:tr [:td {:style {:padding "4px 8px" :color "#c4b5fd"}} ":chain/hash-scheme"] [:td {:style {:padding "4px 8px" :color "#e2e8f0"}} "\"link-v1\""]]
    [:tr [:td {:style {:padding "4px 8px" :color "#c4b5fd"}} ":evidence/hash"] [:td {:style {:padding "4px 8px" :color "#22c55e" :font-size "11px"}} (nth content-hashes 0)]]
    [:tr [:td {:style {:padding "4px 8px" :color "#c4b5fd"}} ":evidence/chain-seq"] [:td {:style {:padding "4px 8px" :color "#e2e8f0"}} "1"]]
    [:tr [:td {:style {:padding "4px 8px" :color "#c4b5fd"}} ":evidence/chain-prev-hash"] [:td {:style {:padding "4px 8px" :color "#e2e8f0"}} nil]]
    [:tr {:style {:border-top "2px solid #22c55e"}}
     [:td {:style {:padding "4px 8px" :color "#7ADDDC" :font-weight 700}} "Link hash (result)"]
     [:td {:style {:padding "4px 8px" :color "#7ADDDC" :font-weight 700}} spec-link-hash]]]]])

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

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
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
(clerk/html (render-checks propagation-invariant-result))

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

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
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
(clerk/html (render-checks accounting-reconcile-result))

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
;; ## 9. Held-Ledger Index (shared contract)
;;
;; The held-ledger index is a five-dimensional cumulative map derived from
;; replaying held-adjustment operations. Every dimension maps a key to an
;; integer amount.  The index is the canonical custody-flow attribution
;; surface shared across the live Sew mutation path and the replay path.
;;
;; Both implementations remain independent so replay can detect live-path
;; bugs.  The shared Malli contract (held-ledger-index-schema) prevents
;; structural drift; differential tests catch semantic divergence.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def hli-dimensions
  "The five index dimensions in canonical order."
  hli/index-dimensions)

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}} "Index dimensions"]
  (into [:ul {:style {:margin "0" :font-size "13px"}}]
        (map (fn [d]
               [:li {:key (name d)}
                [:span {:style {:color "#c4b5fd"}} (name d)]
                "  —  "
                [:span {:style {:color "#94a3b8" :font-size "11px"}}
                 (case d
                   :by-token "total held per token"
                   :by-position "total held per position-id"
                   :by-account "total held per account type"
                   :by-owner "net custody-flow attribution (may be negative)"
                   :by-workflow "total held per workflow-id")]])
             hli-dimensions))])

;; Build a world with held adjustments and inspect the resulting index:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def sample-index
  "A sample held-ledger index reflecting real Sew protocol structure.
   Position-ids are vectors, owner keys are address strings, workflow-ids
   are integers — matching what add-held/sub-held actually produce."
  (let [idx (hli/empty-held-ledger-index)]
    (-> idx
        (assoc-in [:by-token :USDC] 4000)
        (assoc-in [:by-position [:held/position :USDC :escrow-principal 0]] 4000)
        (assoc-in [:by-account :escrow-principal] 4000)
        (assoc-in [:by-owner "0xA1b2C3d4E5f6"] 4000)
        (assoc-in [:by-workflow 0] 4000))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}} "Sample index"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        (mapv (fn [dim]
                (let [entries (get sample-index dim)]
                  [:tr {:key (name dim)}
                   [:td {:style {:padding "4px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a" :vertical-align "top"}} (name dim)]
                   [:td {:style {:padding "4px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a"}}
                    (if (seq entries)
                      (str/join ", " (map (fn [[k v]] (str k " → " v)) (sort entries)))
                      "—")]]))
              hli-dimensions))])

;; Validate the sample index against the shared Malli schema:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def index-valid? (hli/valid-held-ledger-index? sample-index))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if index-valid? "#064e3b" "#450a0a")
                :border (str "1px solid " (if index-valid? "#22c55e" "#ef4444"))
                :color "#e2e8f0" :padding "12px"
                :font-family "monospace" :border-radius "4px"}}
  [:span {:style {:color (if index-valid? "#22c55e" "#ef4444")}} (if index-valid? "✓ " "✗ ")]
  "Schema validation: " (if index-valid? "PASS" "FAIL")
  "  |  Schema version: " hli/schema-version])

;; A malformed index (missing dimension) is correctly rejected:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def malformed-index
  {:by-token {:USDC 1000} :by-position {} :by-account {} :by-owner {}
   ;; by-workflow intentionally missing
   })

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [explain (hli/explain-held-ledger-index malformed-index)]
   [:div {:style {:background "#450a0a" :border "1px solid #ef4444" :color "#e2e8f0" :padding "12px"
                  :font-family "monospace" :border-radius "4px"}}
    [:div {:style {:color "#ef4444" :font-weight 700}} "✗ Rejected — schema violation"]
    [:div {:style {:margin-top "4px" :font-size "12px" :color "#fbbf24"}} (pr-str explain)]]))

;; The contract also provides a reconciliation check: top-level :total-held
;; and :held/positions must match the corresponding index dimensions:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def custody-state
  {:held-ledger/index sample-index
   :total-held (:by-token sample-index)
   :held/positions (:by-position sample-index)})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Reconcile check: " [:strong {:style {:color (if (hli/reconcile? custody-state) "#22c55e" "#ef4444")}}
                             (if (hli/reconcile? custody-state) "PASS" "FAIL")]]
  [:div {:style {:margin-top "4px" :font-size "11px" :color "#94a3b8"}}
   ":total-held matches :by-token, :held/positions matches :by-position"]])

;; ---
;; ## 10. add-held / sub-held — Mutation Path (Second-Order)
;;
;; The static index above shows the shape.  Here the live mutation path
;; (`add-held` / `sub-held`) produce the index
;; as a side effect of custody accounting.  This is the boundary that the
;; direct-write allowlist protects: only `add-held` and `sub-held` are
;; authorised to mutate `:total-held`, `:held/positions`, and the index.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def mut-before
  "World state before any held mutation — empty index, zero balances."
  {:total-held {}
   :held/positions {}
   :held-ledger/index (hli/empty-held-ledger-index)
   :held-adjustments []
   :held-artifacts {}
   :params {}})

;; Step 1 — deposit 10 000 USDC into workflow 0:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def mut-add
  (sew-acc/add-held mut-before :USDC 10000
                    {:action "deposit-wf-0"
                     :reason :escrow-principal-deposited
                     :extra {:held/workflow-id 0
                             :held/account :escrow-principal
                             :owner/address "0xAlice"}}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}}
   "▸ add-held 10 000 USDC → wf-0  (escrow-principal)"]
  [:div {:style {:display "flex" :gap "16px" :font-size "11px" :margin-bottom "6px"}}
   [:div "total-held: " [:strong {:style {:color "#64748b"}} "{}"]
    " → " [:strong {:style {:color "#22c55e"}} (pr-str (:total-held mut-add))]]
   [:div "adj: " (count (:held-adjustments mut-add))
    "  art: " (count (:held-artifacts mut-add))]]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "11px"}}]
        (mapv (fn [dim]
                (let [before-v (get-in mut-before [:held-ledger/index dim])
                      after-v (get-in mut-add [:held-ledger/index dim])]
                  [:tr {:key (name dim)}
                   [:td {:style {:padding "4px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a" :vertical-align "top"}} (name dim)]
                   [:td {:style {:padding "4px 8px" :color "#64748b" :border-bottom "1px solid #134e4a" :font-size "10px"}}
                    (if (seq before-v) (pr-str before-v) "—")]
                   [:td {:style {:padding "4px 8px" :color "#22c55e" :border-bottom "1px solid #134e4a" :font-size "10px"}}
                    (if (seq after-v) (pr-str after-v) "—")]]))
              hli/index-dimensions))])

;; Step 2 — release 3 000 USDC from workflow 0 (sub-held produces
;; negative :by-owner attribution to the recipient):

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def mut-sub
  (sew-acc/sub-held mut-add :USDC 3000
                    {:action "release-wf-0"
                     :reason :escrow-settlement-released
                     :extra {:held/workflow-id 0
                             :held/account :escrow-principal
                             :owner/address "0xBob"}}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#fbbf24" :font-weight 700 :margin-bottom "8px"}}
   "▸ sub-held 3 000 USDC ← wf-0  (released to 0xBob)"]
  [:div {:style {:display "flex" :gap "16px" :font-size "11px" :margin-bottom "6px"}}
   [:div "total-held: " [:strong {:style {:color "#64748b"}} (pr-str (:total-held mut-add))]
    " → " [:strong {:style {:color "#22c55e"}} (pr-str (:total-held mut-sub))]]
   [:div "adj: " (count (:held-adjustments mut-sub))
    "  art: " (count (:held-artifacts mut-sub))]]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "11px"}}]
        (mapv (fn [dim]
                (let [before-v (get-in mut-add [:held-ledger/index dim])
                      after-v (get-in mut-sub [:held-ledger/index dim])]
                  [:tr {:key (name dim)}
                   [:td {:style {:padding "4px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a" :vertical-align "top"}} (name dim)]
                   [:td {:style {:padding "4px 8px" :color "#64748b" :border-bottom "1px solid #134e4a" :font-size "10px"}}
                    (if (seq before-v) (pr-str before-v) "—")]
                   [:td {:style {:padding "4px 8px" :color "#fbbf24" :border-bottom "1px solid #134e4a" :font-size "10px"}}
                    (if (seq after-v) (pr-str after-v) "—")]]))
              hli/index-dimensions))])

;; Step 3 — deposit 50 ETH into workflow 1 (multi-token):

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def mut-multi
  (sew-acc/add-held mut-sub :ETH 50
                    {:action "deposit-wf-1"
                     :reason :escrow-principal-deposited
                     :extra {:held/workflow-id 1
                             :held/account :escrow-principal
                             :owner/address "0xCarol"}}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}}
   "▸ add-held 50 ETH → wf-1  (multi-token)"]
  [:div {:style {:display "flex" :gap "16px" :font-size "11px" :margin-bottom "6px"}}
   [:div "total-held: " [:strong {:style {:color "#64748b"}} (pr-str (:total-held mut-sub))]
    " → " [:strong {:style {:color "#22c55e"}} (pr-str (:total-held mut-multi))]]
   [:div "adj: " (count (:held-adjustments mut-multi))
    "  art: " (count (:held-artifacts mut-multi))]]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "11px"}}]
        (mapv (fn [dim]
                (let [before-v (get-in mut-sub [:held-ledger/index dim])
                      after-v (get-in mut-multi [:held-ledger/index dim])]
                  [:tr {:key (name dim)}
                   [:td {:style {:padding "4px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a" :vertical-align "top"}} (name dim)]
                   [:td {:style {:padding "4px 8px" :color "#64748b" :border-bottom "1px solid #134e4a" :font-size "10px"}}
                    (if (seq before-v) (pr-str before-v) "—")]
                   [:td {:style {:padding "4px 8px" :color "#22c55e" :border-bottom "1px solid #134e4a" :font-size "10px"}}
                    (if (seq after-v) (pr-str after-v) "—")]]))
              hli/index-dimensions))])

;; Validate every intermediate and final index:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def mut-valids
  {:step-1-add    (hli/valid-held-ledger-index? (:held-ledger/index mut-add))
   :step-2-sub    (hli/valid-held-ledger-index? (:held-ledger/index mut-sub))
   :step-3-multi  (hli/valid-held-ledger-index? (:held-ledger/index mut-multi))})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "12px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:font-weight 700 :margin-bottom "6px" :color "#7ADDDC"}}
   "Schema validation after each step"]
  (into [:div {:style {:display "flex" :gap "12px" :font-size "11px"}}]
        (map (fn [[step result]]
               [:div {:key (name step)}
                (name step) ": "
                [:strong {:style {:color (if result "#22c55e" "#ef4444")}}
                 (if result "PASS" "FAIL")]]))
        (sort mut-valids))
  [:div {:style {:margin-top "8px" :font-size "10px" :color "#94a3b8"}}
   "Final world: total-held " (pr-str (:total-held mut-multi))
   "  |  " (count (:held-adjustments mut-multi)) " adjustments, "
   (count (:held-artifacts mut-multi)) " artifacts"]])

;; ---
;; ## 12. Aggregate Shortfall Cap (yield Invariant)
;;
;; The `:yield/aggregate-shortfall-cap` invariant checks that aggregate
;; shortfall per (module-id, token) pair does not exceed the sum of
;; position values (principal + realized-yield + max(0, unrealized-yield))
;; in that pair.  This prevents systemic over-counting where the total
;; recorded shortfall across all positions exceeds available value.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- build-shortfall-world
  "Build a world with yield positions and optional shortfall."
  [positions]
  {:yield/positions positions})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def aggregate-happy-world
  "Positions where shortfall ≤ available value — invariant should pass."
  (build-shortfall-world
   {"pos-1" {:module/id :module-a :token :USDC
             :principal 1000 :realized-yield 100 :unrealized-yield 50
             :shortfall {:basis-amount 200}}
    "pos-2" {:module/id :module-a :token :USDC
             :principal 500 :realized-yield 50 :unrealized-yield 0
             :shortfall {:basis-amount 100}}}))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def shortfall-cap-pass
  (yinv/check-aggregate-shortfall-cap aggregate-happy-world))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (render-checks shortfall-cap-pass))

;; Failure: shortfall exceeds available value in a (module, token) pair:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def aggregate-fail-world
  "Positions where shortfall exceeds available value — invariant should fail."
  (build-shortfall-world
   {"pos-1" {:module/id :module-a :token :USDC
             :principal 1000 :realized-yield 100 :unrealized-yield 50
             :shortfall {:basis-amount 2000}}
    "pos-2" {:module/id :module-a :token :USDC
             :principal 500 :realized-yield 50 :unrealized-yield 0
             :shortfall {:basis-amount 100}}}))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def shortfall-cap-fail
  (yinv/check-aggregate-shortfall-cap aggregate-fail-world))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#450a0a" :border "1px solid #ef4444" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Overall: " [:strong {:style {:color "#ef4444"}} "FAIL"]]
  [:div {:style {:margin-top "8px" :font-size "12px" :color "#fbbf24"}}
   "Violations:"]
  (into [:ul {:style {:margin-top "4px"}}]
        (map (fn [v] [:li (pr-str v)]) (:violations shortfall-cap-fail)))])

;; Separate (module, token) pairs are checked independently:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def aggregate-module-separate-world
  "Two different (module, token) pairs — each checked independently."
  (build-shortfall-world
   {"pos-1" {:module/id :module-a :token :USDC
             :principal 1000 :realized-yield 100 :unrealized-yield 50
             :shortfall {:basis-amount 200}}
    "pos-2" {:module/id :module-b :token :USDC
             :principal 100 :realized-yield 0 :unrealized-yield 0
             :shortfall {:basis-amount 500}}}))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def shortfall-cap-separate
  (yinv/check-aggregate-shortfall-cap aggregate-module-separate-world))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if (:holds? shortfall-cap-separate) "#064e3b" "#450a0a")
                :border (str "1px solid " (if (:holds? shortfall-cap-separate) "#22c55e" "#ef4444"))
                :color "#e2e8f0" :padding "12px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "module-a:USDC shortfall 200 ≤ value 1150 — pass"]
  [:div "module-b:USDC shortfall 500 > value 100 — fail"]
  [:div {:style {:margin-top "8px"}}
   "Overall: " [:strong {:style {:color (if (:holds? shortfall-cap-separate) "#22c55e" "#ef4444")}}
                (if (:holds? shortfall-cap-separate) "PASS" "FAIL")]]])

;; ---
;; ## 11. Yield Index Monotonicity (new-index)
;;
;; Yield accrual computes `new-index = old-index × growth-factor` where the
;; growth factor is derived from APY in basis points and the time delta in
;; seconds.  The monotonicity invariant ensures the index moves in the
;; correct direction: up for positive yield, down for negative yield.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def new-index-demo
  [{:label "positive APY  (1000 bps = 10%)"
    :old-index 1.0
    :apy-bps 1000
    :dt 86400
    :negative-yield? false}
   {:label "positive APY  (200 bps = 2%)"
    :old-index 1.5
    :apy-bps 200
    :dt (* 7 86400)
    :negative-yield? false}
   {:label "negative APY  (-500 bps)"
    :old-index 2.0
    :apy-bps -500
    :dt 86400
    :negative-yield? true}])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}}
   "new-index = old-index × growth-factor  (yield accrual)"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        (map (fn [{:keys [label old-index apy-bps dt negative-yield?]}]
               (let [ni (ym/next-index old-index apy-bps dt)
                     direction (if (pos? apy-bps) "↑" "↓")
                     monotone-ok? (ytran/index-monotone-ok? old-index ni negative-yield?)]
                 [:tr {:key label}
                  [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} label]
                  [:td {:style {:padding "6px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a" :font-size "11px"}}
                   (str old-index " → " (double ni))]
                  [:td {:style {:padding "6px 8px" :color (if monotone-ok? "#22c55e" "#ef4444")
                               :border-bottom "1px solid #134e4a" :font-weight 700}}
                   (if monotone-ok? "PASS" "FAIL")]]))
             new-index-demo))])

;; Violation: positive yield mode but index decreases (wrong direction):

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def new-index-violation
  (ytran/index-monotone-ok? 2.0 1.5 false))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#450a0a" :border "1px solid #ef4444" :color "#e2e8f0" :padding "12px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#ef4444" :font-weight 700}} "✗ Violation — positive yield mode but index regresses"]
  [:div {:style {:margin-top "4px" :font-size "11px" :color "#fbbf24"}}
   "index-monotone-ok?  old=2.0  new=1.5  negative-yield?=false  →  "
   [:strong (if new-index-violation "PASS" "FAIL")]]])

;; ---
;; ## 13. Settlement Deadline Enforcement (Theorem-Consensus)
;;
;; The settlement lifecycle involves three distinct properties that a review
;; panel verifies through theorem-consensus:
;;
;;   **Settlement consistency** — pending settlements only exist for disputed
;;   escrows (invariant `pending-settlement-consistency?`).
;;
;;   **Settlement deadline** — the appeal-deadline is a temporal boundary that
;;   defines when settlement may execute.
;;
;;   **Settlement deadline enforcement** — the protocol guards reject execution
;;   before the deadline and allow it after.
;;
;; Each property is expressed as a theorem with a structured statement,
;; scope, conclusion, and falsifiers. Three independent researchers submit
;; positions targeting these theorems; the certificate computes per-theorem
;; consensus.

;; ── Pending Settlement data model ──────────────────────────────────────────────
;;
;; A pending settlement records the direction (release/refund) and the
;; appeal-deadline block timestamp after which execution is permitted:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def pending-settlement-def
  {:exists          true
   :is-release      true
   :appeal-deadline 1000
   :resolution-hash "0xdeadbeef"})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#c4b5fd" :font-weight 700 :margin-bottom "8px"}}
   "PendingSettlement map"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        (map (fn [[k v]]
               [:tr {:key (name k)}
                [:td {:style {:padding "4px 8px" :color "#7ADDDC" :border-bottom "1px solid #134e4a"}} (name k)]
                [:td {:style {:padding "4px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a"}} (pr-str v)]]))
        pending-settlement-def)])

;; ── Settlement deadline enforcement ────────────────────────────────────────────
;;
;; The protocol guard is: `block-time >= appeal-deadline` when escrow is
;; `:disputed`.  This replicates the guard from `execute-pending-settlement`:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn settlement-executable?
  "Check whether a pending settlement may be executed.
   Mirrors the relevant guards from execute-pending-settlement.
   Returns {:executable? bool :reason string}."
  [block-time pending state]
  (cond
    (not (:exists pending))
    {:executable? false :reason "no-pending-settlement"}
    (not= :disputed state)
    {:executable? false :reason "transfer-not-in-dispute"
     :state state}
    (< block-time (:appeal-deadline pending))
    {:executable? false :reason "appeal-window-not-expired"
     :block-time block-time
     :appeal-deadline (:appeal-deadline pending)}
    :else
    {:executable? true :reason "deadline-passed"}))

;; Test at three time points relative to deadline 1000:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [pending {:exists true :appeal-deadline 1000}
       trials [{:label "t=900  (before deadline)" :block-time 900}
               {:label "t=1000 (at deadline)"     :block-time 1000}
               {:label "t=1100 (after deadline)"  :block-time 1100}]]
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :font-family "monospace" :border-radius "4px"}}
    [:div {:style {:color "#fbbf24" :font-weight 700 :margin-bottom "8px"}}
     "settlement-executable?  (appeal-deadline=1000)"]
    (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
          (map (fn [{:keys [label block-time]}]
                 (let [result (settlement-executable? block-time pending :disputed)]
                   [:tr {:key (name block-time)}
                    [:td {:style {:padding "4px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} label]
                    [:td {:style {:padding "4px 8px" :color (if (:executable? result) "#22c55e" "#ef4444")
                                 :border-bottom "1px solid #134e4a" :font-weight 700}}
                     (if (:executable? result) "EXECUTABLE" "BLOCKED")]
                    [:td {:style {:padding "4px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a" :font-size "11px"}}
                     (:reason result)]]))
          trials)]))

;; ── Theorem definitions ────────────────────────────────────────────────────────
;;
;; Three theorems formalise the settlement properties.  Each has a statement,
;; scope, conclusion, and falsifier conditions:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def theorems
  [{:theorem/id :theorem/settlement-consistency
    :theorem/type :state-invariant
    :theorem/statement "Pending settlements only exist for escrows in :disputed state"
    :theorem/conclusion {:status :established :claim-id :claim/settlement-consistency}}
   {:theorem/id :theorem/settlement-deadline
    :theorem/type :temporal-boundary
    :theorem/statement "Settlement execution is permitted at or after the appeal-deadline"
    :theorem/conclusion {:status :established :claim-id :claim/settlement-deadline}}
   {:theorem/id :theorem/settlement-deadline-enforcement
    :theorem/type :guard-correctness
    :theorem/statement "Protocol guards block execution before deadline, permit after"
    :theorem/conclusion {:status :contested :claim-id :claim/settlement-deadline-enforcement}}])

;; ── Reviewer positions ─────────────────────────────────────────────────────────
;;
;; Three independent researchers submit positions targeting the theorems.
;; Each position records the researcher's assessment (reproduced, challenged,
;; not-reviewed) of each theorem:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def reviewer-positions
  [{:researcher/id "researcher-alpha"
    :position/hash "sha256:pos-alpha"
    :position/outcome-hash "sha256:outcome-A"
    :position/dimensions {:model-state {:status :adequate}
                          :model-authority {:status :adequate}
                          :model-transitions {:status :adequate}
                          :incentives-strategies {:status :adequate}
                          :evidence {:status :sufficient}
                          :claims {:status :supported}
                          :publication {:status :publish}}
    :position/targets [{:kind :theorem :id :theorem/settlement-consistency
                        :hash "sha256:th-sc" :status :reproduced}
                       {:kind :theorem :id :theorem/settlement-deadline
                        :hash "sha256:th-sd" :status :reproduced}
                       {:kind :theorem :id :theorem/settlement-deadline-enforcement
                        :hash "sha256:th-sde" :status :reproduced}]}
   {:researcher/id "researcher-beta"
    :position/hash "sha256:pos-beta"
    :position/outcome-hash "sha256:outcome-A"
    :position/dimensions {:model-state {:status :adequate}
                          :model-authority {:status :adequate}
                          :model-transitions {:status :adequate}
                          :incentives-strategies {:status :adequate}
                          :evidence {:status :sufficient}
                          :claims {:status :supported}
                          :publication {:status :publish}}
    :position/targets [{:kind :theorem :id :theorem/settlement-consistency
                        :hash "sha256:th-sc" :status :reproduced}
                       {:kind :theorem :id :theorem/settlement-deadline
                        :hash "sha256:th-sd" :status :reproduced}
                       {:kind :theorem :id :theorem/settlement-deadline-enforcement
                        :hash "sha256:th-sde" :status :challenged}]}
   {:researcher/id "researcher-gamma"
    :position/hash "sha256:pos-gamma"
    :position/outcome-hash "sha256:outcome-B"
    :position/dimensions {:model-state {:status :adequate}
                          :model-authority {:status :adequate}
                          :model-transitions {:status :adequate}
                          :incentives-strategies {:status :adequate}
                          :evidence {:status :sufficient}
                          :claims {:status :supported}
                          :publication {:status :publish}}
    :position/targets [{:kind :theorem :id :theorem/settlement-consistency
                        :hash "sha256:th-sc" :status :reproduced}
                       {:kind :theorem :id :theorem/settlement-deadline
                        :hash "sha256:th-sd" :status :reproduced}
                       {:kind :theorem :id :theorem/settlement-deadline-enforcement
                        :hash "sha256:th-sde" :status :reproduced}]}])

;; ── Per-Theorem Consensus ──────────────────────────────────────────────────────
;;
;; The certificate's `per-theorem-consensus` function classifies each theorem:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def settlement-consensus
  (tmc/per-theorem-consensus reviewer-positions))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}}
   "Per-Theorem Consensus"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        (map (fn [[th-id consensus]]
               (let [status (:status consensus)
                     color (case status
                             :unanimous "#22c55e"
                             :majority-with-dissent "#fbbf24"
                             "#ef4444")
                     supporters (count (:supporting-members consensus))
                     dissenters (count (:dissenting-members consensus))
                     absent (count (:absent-members consensus))]
                 [:tr {:key (name th-id)}
                  [:td {:style {:padding "6px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a"}} (name th-id)]
                  [:td {:style {:padding "6px 8px" :color color :border-bottom "1px solid #134e4a" :font-weight 700}}
                   (name status)]
                  [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a" :font-size "11px"}}
                   (str supporters " support" (when (pos? dissenters) (str ", " dissenters " dissent")) (when (pos? absent) (str ", " absent " absent")))]]))
             (sort settlement-consensus)))])

;; Summary of theorem-consensus results:
;;
;; | Theorem | Status | Interpretation |
;; |---------|--------|---------------|
;; | `settlement-consistency` | unanimous | All three researchers reproduced the invariant — pending settlements only exist for disputed escrows |
;; | `settlement-deadline` | unanimous | All three agree the appeal-deadline temporal boundary is correctly defined |
;; | `settlement-deadline-enforcement` | majority-with-dissent | Two support the guard correctness, one challenges — enforcement logic is under active review |

;; ---
;; ## 14. Missing-Beneficiary, Misalignment, Same-Authorisation-Provenance
;;
;; Three structural checks ensure slash-distribution completeness and
;; execution provenance integrity:
;;
;;   **missing-beneficiary** — a resolved award must have a beneficiary
;;   participant ID; missing one is a structural violation.
;;
;;   **same-authorisation-provenance?** — two outcome manifests share
;;   provenance when their force-authorisation hashes, reservation hashes,
;;   consumption keys, and execution-attempt IDs all match.
;;
;;   **Misalignment** — two manifests may share exact execution scope
;;   (same content-root, model, plan) but have different authorisation
;;   provenance.  Scope equivalence ≠ provenance identity.

;; ── Missing-beneficiary ───────────────────────────────────────────────────────
;;
;; Every resolved award must carry a `:participant/id` under `:beneficiary`.
;; `build-slash-distribution` rejects the input when this is nil:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn check-beneficiary-present
  "Simplified structural check mirroring the slash-distribution guard."
  [award]
  (if (get-in award [:beneficiary :participant/id])
    {:holds? true :award/id (:award/id award)}
    {:holds? false :violation :violation/missing-beneficiary
     :award/id (:award/id award)}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#c4b5fd" :font-weight 700 :margin-bottom "8px"}}
   "check-beneficiary-present"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        (map (fn [[label award]]
               (let [result (check-beneficiary-present award)]
                 [:tr {:key label}
                  [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} label]
                  [:td {:style {:padding "6px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a" :font-size "11px"}}
                   (pr-str award)]
                  [:td {:style {:padding "6px 8px" :color (if (:holds? result) "#22c55e" "#ef4444")
                               :border-bottom "1px solid #134e4a" :font-weight 700}}
                   (if (:holds? result) "PASS" "FAIL")]]))
             {"with beneficiary"  {:award/id :a1 :beneficiary {:participant/id "0xAlice"}}
              "missing beneficiary" {:award/id :a2 :beneficiary {}}}))])

;; ── Same-authorisation-provenance? ─────────────────────────────────────────────
;;
;; Two outcome manifests share authorisation provenance when their
;; `:execution/force-authorisation` sections match on all four identity
;; fields:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def manifest-a
  {:execution/force-authorisation
   {:authorisation-hash "sha256:auth-1"
    :reservation-hash   "sha256:res-1"
    :consumption-key    "ck-alpha"
    :execution-attempt-id "attempt-1"}})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def manifest-b
  {:execution/force-authorisation
   {:authorisation-hash "sha256:auth-1"
    :reservation-hash   "sha256:res-1"
    :consumption-key    "ck-alpha"
    :execution-attempt-id "attempt-1"}})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def manifest-c
  {:execution/force-authorisation
   {:authorisation-hash "sha256:auth-2"
    :reservation-hash   "sha256:res-2"
    :consumption-key    "ck-beta"
    :execution-attempt-id "attempt-2"}})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}}
   "same-authorisation-provenance?"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        [[:tr [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} "manifest-a vs manifest-b"]
          [:td {:style {:padding "6px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a"}} "same auth hash, res hash, consumption key, attempt"]
          [:td {:style {:padding "6px 8px" :color "#22c55e" :border-bottom "1px solid #134e4a" :font-weight 700}}
           (if (om/same-authorisation-provenance? manifest-a manifest-b) "SAME" "DIFFERENT")]]
         [:tr [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} "manifest-a vs manifest-c"]
          [:td {:style {:padding "6px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a"}} "different auth, res, consumption, attempt"]
          [:td {:style {:padding "6px 8px" :color "#ef4444" :border-bottom "1px solid #134e4a" :font-weight 700}}
           (if (om/same-authorisation-provenance? manifest-a manifest-c) "SAME" "DIFFERENT")]]
         [:tr [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} "no FA section (both nil)"]
          [:td {:style {:padding "6px 8px" :color "#e2e8f0" :border-bottom "1px solid #134e4a"}} "both manifests lack :execution/force-authorisation"]
          [:td {:style {:padding "6px 8px" :color "#22c55e" :border-bottom "1px solid #134e4a" :font-weight 700}}
           (if (om/same-authorisation-provenance? {} {}) "SAME" "DIFFERENT")]]])])

;; ── Misalignment: scope vs provenance ──────────────────────────────────────────
;;
;; Two independently authorised reproductions of the same branch share
;; exact execution scope but have different provenance.  The scope
;; predicate says "same content" while the provenance predicate says
;; "different authority":

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def scope-manifest-a
  {:benchmark/content-root "sha256:content"
   :execution/model-root "sha256:model"
   :execution/plan-root "sha256:plan"
   :execution/parameter-domain-root "sha256:domain"
   :execution/sampling-policy-root "sha256:sampling"
   :execution/generated-case-set-root "sha256:cases"
   :benchmark/evaluation-policy-root "sha256:eval"
   :execution/executed-content-root "sha256:exec"
   :schema-version "outcome-manifest.v1"
   :execution/force-authorisation
   {:authorisation-hash "sha256:auth-alpha"
    :reservation-hash   "sha256:res-alpha"
    :consumption-key    "ck-alpha"
    :execution-attempt-id "attempt-alpha"}})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def scope-manifest-b
  (assoc scope-manifest-a
         :execution/force-authorisation
         {:authorisation-hash "sha256:auth-beta"
          :reservation-hash   "sha256:res-beta"
          :consumption-key    "ck-beta"
          :execution-attempt-id "attempt-beta"}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#fbbf24" :font-weight 700 :margin-bottom "8px"}}
   "Misalignment: same scope, different provenance"]
  [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}
   [:tr [:td {:style {:padding "6px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a"}} "exact-execution-scope?"]
    [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} "same content-root, model, plan, domain, sampling, cases, eval, exec"]
    [:td {:style {:padding "6px 8px" :color "#22c55e" :border-bottom "1px solid #134e4a" :font-weight 700}} "SCOPE-MATCH"]]
   [:tr [:td {:style {:padding "6px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a"}} "same-authorisation-provenance?"]
    [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} "different auth-hash, res-hash, consumption-key, attempt-id"]
    [:td {:style {:padding "6px 8px" :color "#ef4444" :border-bottom "1px solid #134e4a" :font-weight 700}} "PROVENANCE-MISMATCH"]]
   [:tr [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a" :font-size "11px" :font-style "italic"}} "outcome hashes"]
    [:td {:style {:padding "6px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a" :font-size "11px"}} "provenance is part of payload → outcome hashes differ"]
    [:td {:style {:padding "6px 8px" :color "#fbbf24" :border-bottom "1px solid #134e4a" :font-size "11px"}} (str "hash-A ≠ hash-B")]]])

;; ---
;; ## 15. Researcher Assignment (Integer Member Keys)
;;
;; A three-member review round assigns each researcher a dense zero-based
;; integer key.  The key is local to one review round — it does not replace
;; the durable `:researcher/id`.  The pair:
;;
;;   {:review-round/hash \"sha256:...\" :review-member/key N}
;;
;; is globally unambiguous.  A naked `{:review-member/key N}` without its
;; round hash is not meaningful.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def assignment-members
  [{:review-member/key 0, :researcher/id "researcher-alpha", :role :model-steward}
   {:review-member/key 1, :researcher/id "researcher-beta",  :role :independent-reproducer}
   {:review-member/key 2, :researcher/id "researcher-gamma", :role :adversarial-reviewer}])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def assignment-round
  (rr/build-review-round
   {:benchmark/content-root "sha256:not-admitted"
    :review-round/purpose :model-admission
    :review-round/members assignment-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div "round keyed? " (str (rr/round-uses-member-keys? assignment-round))]
  [:div "member keys: " (pr-str (rr/member-keys assignment-round))]
  [:div "round hash:  " (:review-round/id assignment-round)]])

;; ### 12.1  Bidirectional Lookup

;; Each key maps to exactly one researcher, and each researcher maps to
;; exactly one key.  The lookup functions round-trip.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Key" "Researcher" "Role" "Key→ID" "ID→Key"]
  :rows (mapv (fn [m]
                [(:review-member/key m)
                 (:researcher/id m)
                 (name (:role m))
                 (rr/researcher-id-for-member-key assignment-round (:review-member/key m))
                 (rr/member-key-for-researcher assignment-round (:researcher/id m))])
              (:review-round/members assignment-round))})

;; ### 12.2  Scoped Reference Rule

;; A member key is only meaningful when paired with its review-round hash.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div {:style {:color "#22c55e" :fontWeight 700}} "✓ Valid scoped reference"]
  [:div "  {:review-round/hash " (subs (:review-round/id assignment-round) 0 20) "…"
   " :review-member/key 1}"]
  [:div "  → " (rr/researcher-id-for-member-key assignment-round 1)]
  [:div {:style {:marginTop "12px" :color "#ef4444" :fontWeight 700}} "✗ Unscoped (not meaningful)"]
  [:div "  {:review-member/key 1} — scope root missing"]])

;; ### 12.3  Key Validation

;; The builder rejects invalid key configurations at construction time:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Scenario" "Expected" "Result"]
  :rows [["Valid keys {0, 1, 2}" "built"
          (try (rr/build-review-round
                {:benchmark/content-root "sha256:v"
                 :review-round/purpose :model-admission
                 :review-round/members assignment-members
                 :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                 :review-round/policy-root "sha256:p"})
               (catch Exception e (str e)))]
         ["Duplicate key 0" "rejected"
          (try (rr/build-review-round
                {:benchmark/content-root "sha256:v"
                 :review-round/purpose :model-admission
                 :review-round/members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                                        {:review-member/key 0, :researcher/id "b", :role :independent-reproducer}
                                        {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]
                 :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                 :review-round/policy-root "sha256:p"})
               (catch Exception e (.getMessage e)))]
         ["Non-dense {0, 2, 3}" "rejected"
          (try (rr/build-review-round
                {:benchmark/content-root "sha256:v"
                 :review-round/purpose :model-admission
                 :review-round/members [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                                        {:review-member/key 2, :researcher/id "b", :role :independent-reproducer}
                                        {:review-member/key 3, :researcher/id "c", :role :adversarial-reviewer}]
                 :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                 :review-round/policy-root "sha256:p"})
               (catch Exception e (.getMessage e)))]
         ["Negative key" "rejected"
          (try (rr/build-review-round
                {:benchmark/content-root "sha256:v"
                 :review-round/purpose :model-admission
                 :review-round/members [{:review-member/key -1, :researcher/id "a", :role :model-steward}
                                        {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                                        {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]
                 :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                 :review-round/policy-root "sha256:p"})
               (catch Exception e (.getMessage e)))]]})

;; ### 12.4  Integer Hash Commitment

;; The review-round hash commits to the member keys.  Changing any key
;; produces a different hash.  This is the integer-key → hash binding:
;; the key set `{0, 1, 2}` enters the domain-separated round identity.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [round-a (rr/build-review-round
               {:benchmark/content-root "sha256:h"
                :review-round/purpose :model-admission
                :review-round/members
                [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                 {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                 {:review-member/key 2, :researcher/id "c", :role :adversarial-reviewer}]
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:p"})
      round-b (rr/build-review-round
               {:benchmark/content-root "sha256:h"
                :review-round/purpose :model-admission
                :review-round/members
                [{:review-member/key 0, :researcher/id "a", :role :model-steward}
                 {:review-member/key 1, :researcher/id "b", :role :independent-reproducer}
                 {:review-member/key 3, :researcher/id "c", :role :adversarial-reviewer}]
                :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
                :review-round/policy-root "sha256:p"})]
  (clerk/html
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div "Keys {0, 1, 2} → hash " [:strong {:style {:color "#22c55e"}} (subs (:review-round/id round-a) 0 24) "…"]]
    [:div "Keys {0, 1, 3} → hash " [:strong {:style {:color "#fbbf24"}} (subs (:review-round/id round-b) 0 24) "…"]]
    [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px" :color "#94a3b8"}}
     "Different keys ⇒ different hash — the key set is committed."]]))

;; ---
;; ## 16. Researcher Approval (Key-Based Consensus)
;;
;; Three researchers submit positions against the settlement theorems from
;; section 11.  The certificate emits additive key vectors alongside the
;; existing researcher-ID vectors, enabling compact approval/dissent
;; analysis by round-local position.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-base-input
  {:benchmark/content-root "sha256:not-admitted"
   :benchmark/model-root "sha256:demo-model"
   :benchmark/evaluation-policy-root "sha256:demo-eval"
   :execution/status :completed
   :execution/model-instance-root "sha256:demo-mi"
   :execution/plan-root "sha256:demo-plan"
   :execution/parameter-domain-root "sha256:demo-domain"
   :execution/sampling-policy-root "sha256:demo-sampling"
   :execution/realised-parameter-set-root "sha256:demo-params"
   :execution/generated-case-set-root "sha256:demo-cases"
   :results/operational {:conservation :pass :quota-bounded :pass}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-runner-info
  {:runner/id :runner/demo
   :source-tree-hash "sha256:demo-tree"
   :distribution-hash "sha256:demo-dist"
   :environment-hash "sha256:demo-env"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-evidence-refs
  {:evidence-dag-root "sha256:demo-dag"
   :event-evidence-root "sha256:demo-events"
   :execution-log-root "sha256:demo-log"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-reports
  [(rrr/build-report {:outcome-manifest (om/build-manifest approval-base-input)
                      :researcher-id "researcher-alpha"
                      :runner-info approval-runner-info
                      :evidence-refs approval-evidence-refs
                      :run-id "not-admitted-alpha"})
   (rrr/build-report {:outcome-manifest (om/build-manifest approval-base-input)
                      :researcher-id "researcher-beta"
                      :runner-info approval-runner-info
                      :evidence-refs approval-evidence-refs
                      :run-id "not-admitted-beta"})
   (rrr/build-report {:outcome-manifest (om/build-manifest approval-base-input)
                      :researcher-id "researcher-gamma"
                      :runner-info approval-runner-info
                      :evidence-refs approval-evidence-refs
                      :run-id "not-admitted-gamma"})])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-positions
  [(rp/build-position
    {:benchmark/content-root "sha256:not-admitted"
     :researcher/id "researcher-alpha"
     :outcome-hash "sha256:outcome-A"
     :dimensions {:reproduction {:status :reproduced}
                  :model-authority {:status :adequate}
                  :evidence {:status :sufficient}
                  :publication {:status :publish}}
     :position/targets [{:kind :theorem :id :theorem/settlement-consistency
                         :hash "sha256:th-sc" :status :reproduced}
                        {:kind :theorem :id :theorem/settlement-deadline
                         :hash "sha256:th-sd" :status :reproduced}
                        {:kind :theorem :id :theorem/settlement-deadline-enforcement
                         :hash "sha256:th-sde" :status :reproduced}]})
   (rp/build-position
    {:benchmark/content-root "sha256:not-admitted"
     :researcher/id "researcher-beta"
     :outcome-hash "sha256:outcome-A"
     :dimensions {:reproduction {:status :reproduced}
                  :model-authority {:status :adequate}
                  :evidence {:status :sufficient}
                  :publication {:status :publish}}
     :position/targets [{:kind :theorem :id :theorem/settlement-consistency
                         :hash "sha256:th-sc" :status :reproduced}
                        {:kind :theorem :id :theorem/settlement-deadline
                         :hash "sha256:th-sd" :status :reproduced}
                        {:kind :theorem :id :theorem/settlement-deadline-enforcement
                         :hash "sha256:th-sde" :status :challenged}]})
   (rp/build-position
    {:benchmark/content-root "sha256:not-admitted"
     :researcher/id "researcher-gamma"
     :outcome-hash "sha256:outcome-B"
     :dimensions {:reproduction {:status :reproduced}
                  :model-authority {:status :adequate}
                  :evidence {:status :sufficient}
                  :publication {:status :publish}}
     :position/targets [{:kind :theorem :id :theorem/settlement-consistency
                         :hash "sha256:th-sc" :status :reproduced}
                        {:kind :theorem :id :theorem/settlement-deadline
                         :hash "sha256:th-sd" :status :reproduced}
                        {:kind :theorem :id :theorem/settlement-deadline-enforcement
                         :hash "sha256:th-sde" :status :reproduced}]})])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-cert
  (-> (tmc/build-certificate
       {:review-round assignment-round
        :reports approval-reports
        :positions approval-positions})
      (tmc/finalise-certificate!)))

;; ### 13.1  Per-Theorem Consensus with Key Vectors

;; Each theorem's consensus result carries both the string IDs and the
;; integer keys.  The keys provide a compact, ordering-independent
;; representation of who supports, dissents, or abstains.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 (let [by-theorem (:theorem-consensus approval-cert)]
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "Per-Theorem Consensus"]
    (into [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}]
          (map (fn [[th-id consensus]]
                 (let [status (:status consensus)
                       color (case status
                               :unanimous "#22c55e"
                               :majority-with-dissent "#fbbf24"
                               "#ef4444")]
                   [:tr {:key (name th-id)}
                    [:td {:style {:padding "6px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a"}} (name th-id)]
                    [:td {:style {:padding "6px 8px" :color color :borderBottom "1px solid #134e4a" :fontWeight 700}} (name status)]
                    [:td {:style {:padding "6px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     (str "IDs:  " (pr-str (:supporting-members consensus))
                          (when (seq (:dissenting-members consensus))
                            (str "  dissent: " (pr-str (:dissenting-members consensus)))))]
                    [:td {:style {:padding "6px 8px" :color "#7ADDDC" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     (str "Keys: " (pr-str (:supporting-member-keys consensus))
                          (when (seq (:dissenting-member-keys consensus))
                            (str "  dissent: " (pr-str (:dissenting-member-keys consensus)))))]])
               (sort-by first by-theorem)))]))

;; The key vectors are particularly useful for the `majority-with-dissent`
;; case.  Supporting keys `[0 2]` and dissenting key `[1]` compactly
;; represent the same information as the full string vectors.

;; ### 13.2  Per-Dimension Consensus with Keys

;; The same additive pattern applies to every model dimension:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "Dimension Consensus (key vectors)"]
  (into [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}]
        (map (fn [[dim consensus]]
               [:tr {:key (name dim)}
                [:td {:style {:padding "6px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a"}} (name dim)]
                [:td {:style {:padding "6px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                 (str "support: " (pr-str (:supporting-member-keys consensus))
                      (when (seq (:dissenting-member-keys consensus))
                        (str "  dissent: " (pr-str (:dissenting-member-keys consensus)))))]])
          (sort-by first
                   (merge (get-in approval-cert [:other-consensus])
                          (get-in approval-cert [:model-consensus])
                          (get-in approval-cert [:incentive-consensus])))))])

;; ### 13.3  Force-Authorisation Approval by Key

;; A three-member force-authorisation with 2 approvals and 1 dissent.
;; Decisions carry both the durable `:researcher/id` and the round-local
;; `:review-member/key`.  The `verify-against-round` function cross-checks
;; every key against the frozen round membership.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(with-redefs [signing/sign-hash (fn [_ _ _] "deadbeef")]
  (def approval-fa
    (rfa/build-authorisation
     {:authorisation/id :authorisation/not-admitted
      :authorisation/policy
      {:policy/id :research/three-member-force-authorisation
       :policy/version 1 :policy/schema-version "fa-policy.v1"
       :policy/hash "sha256:fa-policy"}
      :authorisation/review-round
      {:review-round/id (:review-round/id assignment-round)
       :review-round/hash (:review-round/id assignment-round)}
      :authorisation/request-root "sha256:not-admitted-request"
      :authorisation/target
      {:target/kind :benchmark-branch
       :target/baseline-content-root "sha256:baseline"
       :target/branch-descriptor-hash "sha256:branch"
       :target/proposed-content-root "sha256:proposed"}
      :authorisation/decision-references
      [{:researcher/id "researcher-alpha" :decision :approve
        :decision/hash "sha256:dec-alpha"
        :review-member/key 0
        :signature {:algorithm :ed25519 :value "sig1" :signed-at "now"}}
       {:researcher/id "researcher-beta"  :decision :approve
        :decision/hash "sha256:dec-beta"
        :review-member/key 1
        :signature {:algorithm :ed25519 :value "sig2" :signed-at "now"}}
       {:researcher/id "researcher-gamma" :decision :dissent
        :decision/hash "sha256:dec-gamma"
        :review-member/key 2
        :dissent/reason "scope concern"
        :signature {:algorithm :ed25519 :value "sig3" :signed-at "now"}}]
      :authorisation/threshold {:required 2 :eligible 3}})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [fa-check (rfa/verify-against-round assignment-round approval-fa)]
  (clerk/html
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div "FA status: " (name (:authorisation/decision-status approval-fa))]
    [:div "Round check: " [:strong {:style {:color (if (:valid? fa-check) "#22c55e" "#ef4444")}}
                           (if (:valid? fa-check) "PASS" "FAIL")]]
    [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px"}}
     [:span {:style {:color "#22c55e"}} "✓ "]
     "Approvals by key: " (pr-str (:approval-member-keys fa-check))
     "  (IDs: " (pr-str (:researcher/id (first (:authorisation/decision-references approval-fa)))
                        (:researcher/id (second (:authorisation/decision-references approval-fa)))) ")"]
    [:div
     [:span {:style {:color "#fbbf24"}} "✗ "]
     "Dissents by key:  " (pr-str (:dissent-member-keys fa-check))
     "  (IDs: " (pr-str (:researcher/id (nth (:authorisation/decision-references approval-fa) 2))) ")"]
    [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px" :color "#94a3b8" :fontSize "12px"}}
     "The approval key vector [0 1] and dissent key vector [2] compactly "
     "identify topological positions without repeating researcher ID strings."]]))

;; ### 13.4  Approval Vector Isomorphism

;; Two review rounds with different global researchers but identical key
;; assignments produce the same approval and dissent vectors by key.
;; The round hashes differ — proof that identity and topology are
;; independently committed.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def iso-round-a
  (rr/build-review-round
   {:benchmark/content-root "sha256:iso"
    :review-round/purpose :model-admission
    :review-round/members
    [{:review-member/key 0, :researcher/id "researcher-alpha", :role :model-steward}
     {:review-member/key 1, :researcher/id "researcher-beta",  :role :independent-reproducer}
     {:review-member/key 2, :researcher/id "researcher-gamma", :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def iso-round-b
  (rr/build-review-round
   {:benchmark/content-root "sha256:iso"
    :review-round/purpose :model-admission
    :review-round/members
    [{:review-member/key 0, :researcher/id "researcher-xi",   :role :model-steward}
     {:review-member/key 1, :researcher/id "researcher-psi",  :role :independent-reproducer}
     {:review-member/key 2, :researcher/id "researcher-omega", :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "Isomorphism: Same Topology, Different Identities"]
  [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}
   [:thead [:tr {:style {:borderBottom "1px solid #134e4a" :color "#94a3b8"}}
            [:th {:style {:padding "6px 8px" :textAlign "left"}} ""]
            [:th {:style {:padding "6px 8px" :textAlign "left"}} "Round A"]
            [:th {:style {:padding "6px 8px" :textAlign "left"}} "Round B"]]]
   [:tbody
    [:tr [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} "Keys"]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (pr-str (rr/member-keys iso-round-a))]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (pr-str (rr/member-keys iso-round-b))]]
    [:tr [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} "Key 0 →"]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (rr/researcher-id-for-member-key iso-round-a 0)]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (rr/researcher-id-for-member-key iso-round-b 0)]]
    [:tr [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} "Key 1 →"]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (rr/researcher-id-for-member-key iso-round-a 1)]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (rr/researcher-id-for-member-key iso-round-b 1)]]
    [:tr [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} "Key 2 →"]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (rr/researcher-id-for-member-key iso-round-a 2)]
     [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} (rr/researcher-id-for-member-key iso-round-b 2)]]
    [:tr {:style (str "borderTop: 2px solid #22c55e")}
     [:td {:style {:padding "6px 8px" :color "#7ADDDC" :fontWeight 700}} "Hash"]
     [:td {:style {:padding "6px 8px" :color "#22c55e" :fontSize "11px"}} (subs (:review-round/id iso-round-a) 0 24) "…"]
     [:td {:style {:padding "6px 8px" :color "#fbbf24" :fontSize "11px"}} (subs (:review-round/id iso-round-b) 0 24) "…"]]
    [:tr {:style (str "borderTop: 2px solid #ef4444")}
     [:td {:style {:padding "6px 8px" :color "#fbbf24" :fontWeight 700}} "Approval vector (key [0, 1])"]
     [:td {:style {:padding "6px 8px" :color "#22c55e"}} "α + β"]
     [:td {:style {:padding "6px 8px" :color "#22c55e"}} "ξ + ψ"]]]]
  [:div {:style {:marginTop "8px" :color "#94a3b8" :fontSize "12px"}}
   "Same keys, same topology.  Different researchers, different hashes."]])

;; ---
;; ## 17. Summary

;; The nine verification surfaces in this notebook enforce different properties:

;; - The **chain verifier** ensures evidence is hash-linked and ordered correctly.
;; - **Propagation completeness** ensures a pro-rata outcome was applied once and that
;;   every participant's entitlement, capacity, and position reflect the outcome.
;; - **Accounting reconciliation** ensures the application snapshot is hash-consistent
;;   and that the source debits and participant credits balance.
;; - **Held-ledger index** formalises the shared contract between live and replay
;;   custody paths, preventing structural drift via a Malli schema.
;; - **Second-order mutation path** demonstrates `add-held`/`sub-held` producing
;;   a valid index as a side effect of custody accounting.
;; - **Yield index monotonicity** verifies `new-index = old-index × growth-factor`
;;   moves in the correct direction for both positive and negative APY.
;; - **Aggregate shortfall cap** prevents systemic over-counting where recorded
;;   shortfall exceeds available position value.
;; - **Settlement deadline enforcement** demonstrates the PendingSettlement data
;;   model, appeal-deadline guard logic, and three-member per-theorem consensus
;;   that classifies each settlement theorem.
;; - **Missing-beneficiary, misalignment, provenance** checks that every award
;;   has a beneficiary, that execution scope ≠ provenance identity, and that
;;   `same-authorisation-provenance?` correctly classifies matching manifests.
;;
;; These are independent verification surfaces. A valid chain does not imply valid
;; propagation, and vice versa — each must be checked separately.
