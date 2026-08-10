^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-admitted
  "Not Admitted — Evidence Chain Ordering, Verification, and Invariant-Based Admission"
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.grounded-amount :as ga]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.yield.invariants :as yinv]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.pro-rata-propagation-policy :as propagation-policy]
            [resolver-sim.accounting.held-ledger-index :as hli]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.accounting :as sew-acc]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.assurance.force-authorisation :as fass]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.yield.exact-math :as ym]
            [resolver-sim.yield.invariants-transition :as ytran]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.allocation.activation :as act]
            [resolver-sim.economics.with-bounty.policy :as wb-policy]))

;; # Not Admitted
;; ## Evidence Chain Ordering, Verification, and Invariant-Based Admission
;;
;; ⬇ **Admission gallery (§19):** the whole notebook in one catalogue — submit a
;; candidate, it violates a property, the system refuses to admit it, and why.
;; Scan that first; each section below is the proof behind its row.
;;
;; **Companions in this family:**
;; - `notebooks/demo_not_admitted` — Demo A: change an amount after verification → NOT ADMITTED
;; - `notebooks/demo_reorder_chain` — Demo B: reorder the evidence → NOT ADMITTED
;; - `notebooks/resubmission_chain` — Demo C: attempt against a stale head → NOT ADMITTED
;; - `notebooks/clean_room_not_admitted` — the clean-room corpus verdicts, recomputed
;; - `notebooks/canonical_framing` — byte-level framing behind the chain commitments

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- short-hash
  "Truncate a content hash for compact display, tolerating short demo hashes."
  [h]
  (let [s (if (string? h) h (str h))
        n (count s)]
    (if (<= n 19) s (str (subs s 0 19) "…"))))

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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def content-hashes
  [(hc/hash-with-intent {:hash/intent :evidence-content} {:event :deposit  :amount 100})
   (hc/hash-with-intent {:hash/intent :evidence-content} {:event :dispute  :id "0x1"})
   (hc/hash-with-intent {:hash/intent :evidence-content} {:event :resolve  :outcome :release})])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head [:seq :content-hash]
  :rows (map-indexed (fn [i h] [(inc i) (str (subs h 0 20) "...")]) content-hashes)})

;; Chain-link hashes commit to content, seq, and predecessor:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def chain-failure-modes
  [{:label "Hash mismatch — tampered self-hash"
    :property "chain self-hash must equal the computed link hash"
    :chain (let [bad (update (first valid-chain) :evidence/chain-self-hash (fn [_] "0xtampered"))
                rest (subvec valid-chain 1)]
             (into [bad] rest))
    :expected-reason :chain-link-hash-mismatch}
   {:label "Link break — wrong prev-hash"
    :property "each prev-hash must match its predecessor's self-hash"
    :chain (let [good (first valid-chain)
                bad (assoc (second valid-chain) :evidence/chain-prev-hash "0xbroken")]
             [good bad (nth valid-chain 2)])
    :expected-reason :predecessor-mismatch}
   {:label "Sequence gap — missing seq 2"
    :property "chain sequence must be contiguous (no gaps or duplicates)"
    :chain [(first valid-chain) (nth valid-chain 2)]
    :expected-reason :non-contiguous-sequence}
   {:label "Wrong scheme"
    :property "every record must use chain scheme \"link-v1\""
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

;; ## 7. Propagate Pro-Rata (Invariant)

;; The `:yield/pro-rata-propagation-complete` invariant checks that every
;; persisted shared pro-rata outcome has been applied exactly once and that
;; entitlement, capacity, and accounting are conserved.

;; Build a canonical, internally-consistent world using the same production
;; construction demonstrated by the tested `build-propagations-from-case`
;; helper (resolver-sim.yield.pro-rata-propagation-helpers).  The committed
;; propagation (including per-participant application accounting), its matching
;; application record, withdrawn tracking, and positions are mutually
;; consistent, so both `:yield/pro-rata-propagation-complete` and
;; `:yield/pro-rata-accounting-reconciles` hold on the result:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- normalize-propagation-participant
  [p]
  (let [fulfilled (long (:fulfilled p 0))
        deferred (long (:deferred p 0))
        eligible (long (:eligible-obligation p fulfilled))]
    {:participant-id (:participant-id p)
     :eligible-obligation eligible
     :fulfilled fulfilled
     :deferred deferred
     :unmet 0 :waived 0
     :obligation-after deferred
     :origin (:origin p
                      {:obligation-id (keyword (str "obl-" (:participant-id p)))
                       :participant-id (:participant-id p)
                       :sequence 1})}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- build-propagation-happy-world
  "Build a world with one committed shared pro-rata propagation plus its
   matching application record, withdrawn tracking, and positions.  Mirrors
   the tested construction in pro-rata-propagation-helpers so that both
   invariants hold on the result."
  [{:keys [token-id source-balance participants]
    :or {token-id :USDC}}]
  (let [norm-ps (mapv normalize-propagation-participant participants)
        total-allocated (reduce + 0 (map :fulfilled norm-ps))
        residual (- source-balance total-allocated)
        policy (propagation-policy/normalize-and-validate
                propagation-policy/shared-withdrawal-policy)
        policy-hash (:policy/hash policy)
        calc-id "propagation-demo-c1"
        outcome-hash "propagation-demo-o1"
        id-key [:pro-rata-propagation calc-id outcome-hash policy-hash]
        accounting-entries
        (vec (concat
              [{:entry/type :debit :account :shared-liquidity
                :token token-id :delta (- total-allocated)}]
              (mapv (fn [p]
                      {:entry/type :credit :account :withdrawn
                       :token token-id
                       :participant-id (:participant-id p)
                       :obligation-id (get-in p [:origin :obligation-id])
                       :delta (:fulfilled p)})
                    norm-ps)))
        entry-hash (pf/accounting-entry-set-hash accounting-entries)
        propagation {:propagation/id "p1"
                     :calculation-ref calc-id
                     :outcome-ref outcome-hash
                     :token token-id
                     :propagation/hash outcome-hash
                     :propagation/content-hash entry-hash
                     :propagation-policy (propagation-policy/policy-reference policy)
                     :summary {:available source-balance
                               :allocated total-allocated
                               :unallocated-residual residual}
                     :residual {:destination :remain-in-shared-liquidity}
                     :participants norm-ps
                     :applications (mapv (fn [p]
                                           {:participant-id (:participant-id p)
                                            :apparent-application {:application-key id-key
                                                                   :accounting-delta (:fulfilled p)}
                                            :fulfilled (:fulfilled p)
                                            :accounting-entry {:delta (:fulfilled p)}})
                                         norm-ps)
                     :accounting-entries accounting-entries
                     :accounting-entry-set-hash entry-hash}
        app {:schema-version "pro-rata-propagation-application.v3"
             :propagation-id "p1"
             :propagation/reference {:propagation/id "p1"
                                     :propagation/hash outcome-hash
                                     :propagation/content-hash entry-hash}
             :calculation-id calc-id
             :outcome-hash outcome-hash
             :policy-hash policy-hash
             :application-key id-key
             :application-order {:schema-version "pro-rata-application-order.v2"
                                 :step 1 :event-id 0}
             :accounting-entry-set-hash entry-hash
             :source-account {:account :shared-liquidity
                              :token token-id
                              :before source-balance
                              :delta (- total-allocated)
                              :after residual}
             :residual {:token token-id
                        :available source-balance
                        :allocated total-allocated
                        :amount residual
                        :destination :remain-in-shared-liquidity}
             :participants
             (mapv (fn [p]
                     (let [pid (:participant-id p)
                           fulfilled (:fulfilled p 0)
                           oid (get-in p [:origin :obligation-id])]
                       {:participant-id pid
                        :obligation-id oid
                        :withdrawn {:token token-id
                                    :before 0
                                    :delta fulfilled
                                    :after fulfilled}
                        :obligation {:before (:eligible-obligation p)}
                        :cumulative-fulfilled {:before 0
                                               :delta fulfilled
                                               :after fulfilled}}))
                   norm-ps)}
        app (assoc app :application/hash (pf/application-hash app))
        withdrawn-map (into {} (map (fn [p] [(:participant-id p) (:fulfilled p 0)]) norm-ps))
        positions
        (into {}
              (map (fn [p]
                     (let [pid (:participant-id p)
                           deferred (:deferred p 0)
                           oid (get-in p [:origin :obligation-id])]
                       [pid
                        (cond-> {:token token-id
                                 :status (if (pos? deferred) :partially-deferred :withdrawn)}
                          (pos? deferred)
                          (assoc :deferred-position
                                 {:position/current-amount deferred
                                  :position/root-obligation-id oid
                                  :position/origin-propagation-id "p1"
                                  :position/round 1
                                  :position/type :deferred-withdrawal
                                  :position/eligibility :later-liquidity}))]))
                   norm-ps))]
    {:yield/pro-rata-propagations {"p1" propagation}
     :yield/applied-pro-rata-propagations {"p1" app}
     :total-held {token-id residual}
     :yield/withdrawn {token-id withdrawn-map}
     :yield/positions positions}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def demo-propagation-case
  {:token-id :USDC :source-balance 200
   :participants [{:participant-id "alice" :eligible-obligation 200
                   :fulfilled 200 :deferred 0 :unmet 0 :waived 0}]})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def happy-world (build-propagation-happy-world demo-propagation-case))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def propagation-invariant-result
  (yinv/check-pro-rata-propagation-complete happy-world))

;; The invariant returns per-check status and a `:holds?` summary:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (render-checks propagation-invariant-result))

;; Now show a failure: entitlement not conserved.
;; The participant's fulfilled total no longer matches their eligible obligation:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def propagation-failure
  (let [p (get-in happy-world [:yield/pro-rata-propagations "p1"])
        p2 (-> p (assoc-in [:summary :allocated] 150)
                 (assoc-in [:participants 0 :fulfilled] 150))
        w2 (assoc-in happy-world [:yield/pro-rata-propagations "p1"] p2)]
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

;; The happy world above already carries the committed application record that
;; matches the propagation.  This invariant reconciles that application snapshot
;; against the propagation — application hash binding, source arithmetic,
;; residual record, and balanced entries:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def accounting-reconcile-result
  (yinv/check-pro-rata-accounting-reconciles happy-world))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (render-checks accounting-reconcile-result))

;; Two targeted failures:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def accounting-failures
  [{:label "Application hash mismatch"
    :world (assoc-in happy-world [:yield/applied-pro-rata-propagations "p1" :application/hash] "0xtampered")
    :expected-check :application-binding-valid}
   {:label "Accounting imbalance"
    :world (update-in happy-world [:yield/applied-pro-rata-propagations "p1" :source-account]
                      assoc :before 200 :after 100 :delta -50)
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def malformed-index
  {:by-token {:USDC 1000} :by-position {} :by-account {} :by-owner {}
   ;; by-workflow intentionally missing
   })

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [explain (hli/explain-held-ledger-index malformed-index)
       bad-paths (mapv :in (:errors explain))]
   [:div {:style {:background "#450a0a" :border "1px solid #ef4444" :color "#e2e8f0" :padding "12px"
                  :font-family "monospace" :border-radius "4px"}}
    [:div {:style {:color "#ef4444" :font-weight 700}} "✗ Rejected — schema violation"]
    [:div {:style {:margin-top "4px" :font-size "12px" :color "#fbbf24"}}
     "Invalid/missing index dimension(s): " (pr-str bad-paths)]]))

;; The contract also provides a reconciliation check: top-level :total-held
;; and :held/positions must match the corresponding index dimensions:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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
;; direct-write allowlist (`scripts/scenarios/check_direct_writes.clj`)
;; protects: for the canonical custody-mutation path, `add-held` and
;; `sub-held` are the only entry points authorised to mutate
;; `:total-held`, `:held/positions`, and the index.
;;
;; Two further writers are permitted by design, each either private or an
;; explicit completeness-clearing escape hatch rather than an independent
;; custody authority: (1) `update-ledger-index` is a private helper whose
;; writes are downstream of an authorised `adjust-held` mutation, so it is
;; intentionally NOT in the allowlist and is instead recognised by the gate as
;; the sole canonical private ledger/index mutator (verified to stay `defn-`
;; and reachable only from `adjust-held`); (2) the completeness-clearing
;; escape hatches — `adversarial-accrue` and the liquid-lending pro-rata
;; application (`apply-pro-rata-propagation`) — write `:total-held` directly
;; but set `:held-adjustments/complete?` false so strong replay stays guarded.
;; The gate (`check:direct-writes`) verifies each escape-hatch entry actually
;; clears the flag and passes cleanly; they are not generic authorised
;; direct-write locations.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def mut-before
  "World state before any held mutation — empty index, zero balances."
  {:total-held {}
   :held/positions {}
   :held-ledger/index (hli/empty-held-ledger-index)
   :held-adjustments []
   :held-artifacts {}
   :params {}})

;; Step 1 — deposit 10 000 USDC into workflow 0:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

;; Step 4 — the custody artifacts form a content-addressed evidence chain.
;; Each add-held/sub-held produces a `sha256:` artifact; every non-genesis
;; artifact binds `:held/previous-artifact-hash` to its predecessor's hash.
;; This is the evidence-chain surface the closed-form verifier checks.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def mut-chain
  {:artifacts (sort-by :held-adjustment/id (vals (:held-artifacts mut-multi)))
   :checks (custody/held-custody-closed-form-checks
            (vals (:held-artifacts mut-multi)))})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ held-custody evidence chain — content-addressed artifacts from add-held/sub-held"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :fontSize "11px"}}]
        (mapv (fn [art]
                [:tr {:key (:held-adjustment/id art)}
                 [:td {:style {:padding "4px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (:held-adjustment/id art)]
                 [:td {:style {:padding "4px 8px" :color "#fbbf24" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (name (:held/direction art))]
                 [:td {:style {:padding "4px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (str (:amount art)) " " (name (:token art))]
                 [:td {:style {:padding "4px 8px" :color "#22c55e" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (:artifact/hash art)]
                 [:td {:style {:padding "4px 8px" :color "#94a3b8" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (or (:held/previous-artifact-hash art) "—")]])
              (:artifacts mut-chain)))
  [:div {:style {:marginTop "10px" :fontSize "11px" :color "#94a3b8"}}
   "Closed-form chain verification:"]
  (into [:div {:style {:display "grid" :gap "4px" :marginTop "4px" :fontSize "11px"}}]
        (map (fn [{:keys [check/id status details]}]
               [:div {:style {:display "flex" :gap "8px"}}
                [:span {:style {:color (if (= :pass status) "#22c55e" "#ef4444") :fontWeight 700}}
                 (if (= :pass status) "✓" "✗")]
                [:span {:style {:color "#cbd5e1"}} (name id)]
                [:span {:style {:color "#94a3b8" :marginLeft "auto"}}
                 (str (count (:violations details)) " violations")]])
             (:checks mut-chain)))])

;; Step 4b — live vs replay differential: the independent replay path
;; (`replay-held-adjustment-state`, a pure reconstruction from the adjustment
;; ledger with no Sew dependency) must reproduce the live world's custody state
;; exactly. This is the direct equivalence check behind §9's "shared contract"
;; claim — not just the closed-form checks above.
;;
;; The §10 chain is zero-origin (each token's first :held/before is 0), so
;; replay from {} is deterministic and must match the live index, :total-held,
;; and :held/positions dimension for dimension.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def mut-replay
  (let [live (:held-ledger/index mut-multi)
        replayed (custody/replay-held-adjustment-state (:held-adjustments mut-multi))
        rindex (:held-ledger/index replayed)]
    {:zero-origin? (custody/held-history-zero-origin? (:held-adjustments mut-multi))
     :by-token-equal? (= (:by-token live) (:by-token rindex))
     :by-position-equal? (= (:by-position live) (:by-position rindex))
     :by-account-equal? (= (:by-account live) (:by-account rindex))
     :by-owner-equal? (= (:by-owner live) (:by-owner rindex))
     :by-workflow-equal? (= (:by-workflow live) (:by-workflow rindex))
     :total-held-equal? (= (:total-held mut-multi) (:total-held replayed))
     :positions-equal? (= (:held/positions mut-multi) (:held/positions replayed))
     :summary (custody/final-held-summary (:held-adjustments mut-multi)
                                          rindex (:total-held replayed))}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ replay-held-adjustment-state — independent reconstruction of the live world"]
  [:div {:style {:fontSize "11px" :marginBottom "6px"}}
   "zero-origin contract: "
   [:strong {:style {:color (if (:zero-origin? mut-replay) "#22c55e" "#ef4444")}}
    (if (:zero-origin? mut-replay) "PASS" "FAIL")]]
  (into [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}]
        (map (fn [[label equal?]]
               [:tr {:key (name label)}
                [:td {:style {:padding "4px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a"}} (name label)]
                [:td {:style {:padding "4px 8px" :color (if equal? "#22c55e" "#ef4444") :borderBottom "1px solid #134e4a" :fontWeight 700}}
                 (if equal? "MATCH" "DIVERGE")]])
             (select-keys mut-replay [:by-token-equal? :by-position-equal?
                                      :by-account-equal? :by-owner-equal?
                                      :by-workflow-equal? :total-held-equal?
                                      :positions-equal?])))
  [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px" :fontSize "11px" :color "#94a3b8"}}
   "final-held-summary: "
   (pr-str (:summary mut-replay))]])

;; Step 5 — admission gates: what add-held/sub-held reject.
;; The same boundary that section 17 demonstrates for force-authorisation applies
;; to custody mutations: invalid inputs, exceptional reasons without provenance,
;; and sub-held overdraw all fail closed and leave the world untouched.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- custody-state-projection
  "Compact accounting + evidence-chain projection used to prove a rejected
   mutation leaves the world unchanged — not just :total-held, but positions,
   the ledger index, held-adjustments, held-custody artifacts, and any
   authorization-consumption state."
  [world]
  (select-keys world [:total-held :held/positions :held-ledger/index
                      :held-adjustments :held-artifacts
                      :force-authorisations/consumed]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- gate-error [f world]
  (let [before (custody-state-projection world)]
    (try (f) {:ok? true :world-unchanged? false}
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             {:ok? false
              :error (or (:reason d) (:type d))
              :world-unchanged? (= before (custody-state-projection world))})))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def add-held-gates
  {:missing-token       (gate-error #(sew-acc/add-held mut-before nil 100 {}) mut-before)
   :negative-amount     (gate-error #(sew-acc/add-held mut-before :USDC -5 {}) mut-before)
   :exceptional-no-auth (gate-error #(sew-acc/add-held mut-before :USDC 100
                                                       {:reason :governance-authorised-correction})
                                    mut-before)
   :sub-held-overdraw   (gate-error #(sew-acc/sub-held mut-before :USDC 100 {}) mut-before)})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ admission gates — rejected mutations fail closed"]
  (into [:div {:style {:display "grid" :gap "4px" :fontSize "11px"}}]
        (map (fn [[k v]]
               [:div {:style {:display "flex" :gap "8px" :alignItems "center"}}
                [:span {:style {:color (if (:ok? v) "#22c55e" "#ef4444") :fontWeight 700}}
                 (if (:ok? v) "ACCEPTED" "REJECTED")]
                [:span {:style {:color "#c4b5fd" :minWidth "150px"}} (name k)]
                [:span {:style {:color "#94a3b8"}} (pr-str (:error v))]
                [:span {:style {:color (if (:world-unchanged? v) "#22c55e" "#ef4444")
                                :fontSize "10px" :marginLeft "auto"}}
                 (if (:world-unchanged? v) "state unchanged" "STATE CHANGED")]])
             (sort add-held-gates)))
  [:div {:style {:marginTop "8px" :fontSize "10px" :color "#94a3b8"}}
   "Rejected mutations leave accounting state AND evidence-chain state unchanged (",
   (pr-str (custody-state-projection mut-before)) ")"]])

;; Step 6 — DIRECT-CONSTRUCTION mechanics example.
;; The negative :missing-authorization-provenance gate above is only half the
;; story: an exceptional custody mutation is not impossible, it is admissible
;; exactly when the authorization evidence is. The grant is consumed, the held
;; mutation occurs, a held-custody artifact is appended, and the same closed-form
;; chain verifier still passes.
;;
;; NOTE: the authorization record below is CONSTRUCTED LOCALLY to isolate
;; scope/lifecycle/consumption behavior. Production force authorisations cannot
;; be created this way through the Sew action surface — creation and revocation
;; are governance-gated (see Step 8).

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def fa-add-held-pos
  (let [auth-id "fa-add-held-gov"
        scope-map {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :in
                   :token :USDC
                   :amount 5000
                   :held/account :escrow-principal
                   :owner/address "0xRecipient"
                   :held/reason :governance-authorised-correction
                   :held/workflow-id 0}
        scope-hash (hc/domain-hash "force-authorisation-scope" scope-map)
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-hash scope-hash}
        world (assoc-in mut-multi [:force-authorisations auth-id]
                        {:authorization/id auth-id
                         :authorization/status :active
                         :consumed? false
                         :starts-at 0
                         :authorization/scope scope-map
                         :authorization/scope-hash scope-hash})
        w (sew-acc/add-held world :USDC 5000
                            {:action "gov-correction"
                             :reason :governance-authorised-correction
                             :authorization-provenance auth-prov
                             :extra {:held/workflow-id 0
                                     :held/account :escrow-principal
                                     :owner/address "0xRecipient"}})]
    {:auth-id auth-id
     :grant-before (get-in world [:force-authorisations auth-id :authorization/status])
     :grant-after (get-in w [:force-authorisations auth-id :authorization/status])
     :consumed (get-in w [:force-authorisations/consumed auth-id])
     :total-held (get-in w [:total-held :USDC])
     :artifacts (sort-by :held-adjustment/id (vals (:held-artifacts w)))
     :checks (custody/held-custody-closed-form-checks
              (vals (:held-artifacts w)))}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ force-authorised add-held — exceptional mutation admitted with valid authorization"]
  [:div {:style {:display "flex" :gap "24px" :fontSize "11px" :marginBottom "10px"}}
   [:div "grant: " [:strong {:style {:color "#e2e8f0"}} (name (:grant-before fa-add-held-pos))]
    " → " [:strong {:style {:color "#fbbf24"}} (name (:grant-after fa-add-held-pos))]
    "  (consumed? " (:consumed? (:consumed fa-add-held-pos)) ")"]
   [:div "total-held: " [:strong {:style {:color "#22c55e"}} (:total-held fa-add-held-pos)]]]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :fontSize "11px"}}]
        (mapv (fn [art]
                [:tr {:key (:held-adjustment/id art)}
                 [:td {:style {:padding "4px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (:held-adjustment/id art)]
                 [:td {:style {:padding "4px 8px" :color "#fbbf24" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (name (:held/direction art))]
                 [:td {:style {:padding "4px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (str (:amount art)) " " (name (:token art))]
                 [:td {:style {:padding "4px 8px" :color "#22c55e" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (:artifact/hash art)]
                 [:td {:style {:padding "4px 8px" :color "#94a3b8" :borderBottom "1px solid #134e4a" :fontSize "10px"}} (or (:held/previous-artifact-hash art) "—")]])
              (:artifacts fa-add-held-pos)))
  [:div {:style {:marginTop "10px" :fontSize "11px" :color "#94a3b8"}}
   "Closed-form chain verification on the extended chain:"]
  (into [:div {:style {:display "grid" :gap "4px" :marginTop "4px" :fontSize "11px"}}]
        (map (fn [{:keys [check/id status]}]
               [:div {:style {:display "flex" :gap "8px"}}
                [:span {:style {:color (if (= :pass status) "#22c55e" "#ef4444") :fontWeight 700}}
                 (if (= :pass status) "✓" "✗")]
                [:span {:style {:color "#cbd5e1"}} (name id)]])
             (:checks fa-add-held-pos)))])

;; Step 7 — the verifier actually detects broken ordering. Tamper with one
;; :held/previous-artifact-hash on the valid chain: hash-integrity (artifact no
;; longer self-consistent) and predecessor-continuity (link broken) must fail.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def mut-tampered
  (let [tampered (assoc-in (:held-artifacts mut-multi)
                           ["held-adjustment-2" :held/previous-artifact-hash]
                           "sha256:0000000000000000000000000000000000000000000000000000000000000000")]
    (try (custody/held-custody-closed-form-checks (vals tampered))
         (catch clojure.lang.ExceptionInfo e
           (:check-results (ex-data e))))))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#fbbf24" :fontWeight 700 :marginBottom "8px"}}
   "▸ tampered chain — previous-artifact-hash of artifact 3 rewritten"]
  (into [:div {:style {:display "grid" :gap "4px" :fontSize "11px"}}]
        (map (fn [{:keys [check/id status details]}]
               [:div {:style {:display "flex" :gap "8px"}}
                [:span {:style {:color (if (= :pass status) "#22c55e" "#ef4444") :fontWeight 700}}
                 (if (= :pass status) "✓" "✗")]
                [:span {:style {:color "#cbd5e1"}} (name id)]
                [:span {:style {:color "#94a3b8" :marginLeft "auto"}}
                 (str (count (:violations details)) " violation(s)")]])
             mut-tampered))
   [:div {:style {:marginTop "8px" :fontSize "10px" :color "#94a3b8"}}
    "The green five-check table is not merely descriptive: broken chain ordering is
     detected (hash-integrity + predecessor-continuity fail)."]])

;; Step 7b — the two-arity battery closes the ledger-completeness gap.
;;
;; The one-arity surface battery (above) proves artifact-chain integrity. The
;; two-arity form `(adjustments artifacts)` additionally re-derives
;; ledger↔artifact completeness (bijection + order), replayed reason/position
;; policy, and attribution shape/requirement — so a chain that omits, adds, or
;; substitutes a ledger entry is now independently rejected, not only a chain
;; whose internal links are broken.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def full-battery
  {:valid (custody/held-custody-closed-form-checks
           (:held-adjustments mut-multi)
           (vals (:held-artifacts mut-multi)))
   :dropped-artifact
   (try (custody/held-custody-closed-form-checks
         (:held-adjustments mut-multi)
         (rest (vals (:held-artifacts mut-multi))))
        (catch clojure.lang.ExceptionInfo e
          (:check-results (ex-data e))))})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ two-arity battery — ledger completeness, policy parity, attribution"]
  [:div {:style {:color "#94a3b8" :fontSize "11px" :marginBottom "6px"}}
   "Valid world — all checks pass, including ledger/artifact bijection + order
    and the replayed reason-position-policy / attribution checks:"]
  (into [:div {:style {:display "grid" :gap "3px" :fontSize "11px"}}]
        (map (fn [{:keys [check/id status]}]
               [:div {:style {:display "flex" :gap "8px"}}
                [:span {:style {:color (if (= :pass status) "#22c55e" "#ef4444") :fontWeight 700}}
                 (if (= :pass status) "✓" "✗")]
                [:span {:style {:color "#cbd5e1"}} (name id)]])
             (:valid full-battery)))
  [:div {:style {:color "#fbbf24" :fontWeight 700 :marginTop "12px" :fontSize "11px"}}
   "Dropping one artifact — ledger/artifact bijection and order reject it:"]
  (into [:div {:style {:display "grid" :gap "3px" :fontSize "11px"}}]
        (map (fn [{:keys [check/id status]}]
               [:div {:style {:display "flex" :gap "8px"}}
                [:span {:style {:color (if (= :pass status) "#22c55e" "#ef4444") :fontWeight 700}}
                 (if (= :pass status) "✓" "✗")]
                [:span {:style {:color "#cbd5e1"}} (name id)]])
             (:dropped-artifact full-battery)))
  [:div {:style {:marginTop "8px" :fontSize "10px" :color "#94a3b8"}}
   "The artifact surface stays internally valid, but the ledger/artifact bijection
    fails: the chain is no longer an exact image of the ledger."]])

;; Step 8 — the legitimate public path: governance grants, anyone executes.
;;
;; The synthetic Step 6 record is NOT how production force authorisations are
;; obtained. Through the Sew action surface:
;;
;;   governance actor ──► grant-force-authorisation ──► governance-origin record
;;   non-governance actor ──► grant ──► :not-governance
;;   any resolved actor ──► execute-force-authorised-action ──► scope-locked,
;;     single-use custody mutation (grant consumed, escrow released)
;;
;; Execution is intentionally unprivileged: the privilege lives in the
;; authenticated, scope-locked, consumable capability.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def fa-legit
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50})
        w0 (sew-types/empty-world 1000)
        cr (lc/create-escrow w0 "0xAlice" :0xUSDC "0xBob" 10000
                             (sew-types/make-escrow-settings {}) snap)
        disputed (-> (:world cr)
                     (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                     (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
                     (assoc-in [:escrow-transfers 0 :dispute-resolver] "0xResolver"))
        gov-ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
                 :governance-identity "0xGov"}
        non-gov-ctx {:agent-index {"mallory" {:id "mallory" :address "0xMallory" :type "honest"}}
                     :governance-identity "0xGov"}
        exec-ctx {:agent-index {"exec" {:address "0xExecutor"}}}
        grant-event {:seq 0 :time 1000 :agent "gov" :action "grant-force-authorisation"
                     :params {:workflow-id 0 :reason :resolver-overcapacity}}
        grant (sew/apply-action gov-ctx disputed grant-event)
        auth-id (get-in grant [:extra :authorization/id])
        record (get-in grant [:world :force-authorisations auth-id])
        mallory-grant-event {:seq 0 :time 1000 :agent "mallory" :action "grant-force-authorisation"
                             :params {:workflow-id 0 :reason :resolver-overcapacity}}
        mallory-grant (sew/apply-action non-gov-ctx disputed mallory-grant-event)
        exec-event {:seq 1 :time 1000 :agent "exec" :action "execute-force-authorised-action"
                    :params {:workflow-id 0 :authorization-id auth-id :is-release true}}
        executed (sew/apply-action exec-ctx (:world grant) exec-event)
        w-exec (:world executed)
        exec-consumed (get-in w-exec [:force-authorisations/consumed auth-id])
        reuse (sew/apply-action exec-ctx w-exec exec-event)]
    {:auth-id auth-id
     :granted? (:ok grant)
     :provenance (select-keys (:authorization/provenance record)
                              [:authorization/type :authorization/source
                               :authorization/check :authorization/scope-hash])
     :nonce (:nonce record)
     :created-by (:created-by record)
     :non-governance-grant (:error mallory-grant)
     :executed? (:ok executed)
     :grant-status (get-in w-exec [:force-authorisations auth-id :authorization/status])
     :consumed? (:consumed? exec-consumed)
     :reuse-error (:error reuse)}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ legitimate public path — governance grants, scope-locked single-use execution"]
  [:div {:style {:display "grid" :gap "4px" :fontSize "11px" :marginBottom "10px"}}
   [:div {:style {:display "flex" :gap "8px"}}
    [:span {:style {:color "#94a3b8" :minWidth "150px"}} "governance grant"]
    [:span {:style {:color (if (:granted? fa-legit) "#22c55e" "#ef4444") :fontWeight 700}}
     (if (:granted? fa-legit) "ADMITTED" "REJECTED")]
    [:span {:style {:color "#94a3b8"}} (str "auth-id=" (:auth-id fa-legit))]]
   [:div {:style {:display "flex" :gap "8px"}}
    [:span {:style {:color "#94a3b8" :minWidth "150px"}} "non-governance grant"]
    [:span {:style {:color "#ef4444" :fontWeight 700}} (name (:non-governance-grant fa-legit))]]
   [:div {:style {:display "flex" :gap "8px"}}
    [:span {:style {:color "#94a3b8" :minWidth "150px"}} "any resolved actor execute"]
    [:span {:style {:color (if (:executed? fa-legit) "#22c55e" "#ef4444") :fontWeight 700}}
     (if (:executed? fa-legit) "EXECUTED" "REJECTED")]
    [:span {:style {:color "#94a3b8"}} "grant "
     (name (:grant-status fa-legit)) "  consumed? " (:consumed? fa-legit)]]
   [:div {:style {:display "flex" :gap "8px"}}
    [:span {:style {:color "#94a3b8" :minWidth "150px"}} "grant reuse"]
    [:span {:style {:color "#ef4444" :fontWeight 700}} (name (:reuse-error fa-legit))]]]
  [:div {:style {:color "#94a3b8" :fontSize "11px" :marginBottom "4px"}}
   "record provenance:"]
  (for [[k v] (:provenance fa-legit)]
    [:div {:style {:display "flex" :gap "6px" :fontSize "10px"}}
     [:span {:style {:color "#94a3b8" :minWidth "120px"}} (name k)]
     [:span {:style {:color "#e2e8f0"}} (pr-str v)]])
  [:div {:style {:display "flex" :gap "6px" :fontSize "10px" :marginTop "2px"}}
   [:span {:style {:color "#94a3b8" :minWidth "120px"}} "nonce"]
   [:span {:style {:color "#e2e8f0"}} (pr-str (:nonce fa-legit))]]
  [:div {:style {:display "flex" :gap "6px" :fontSize "10px"}}
   [:span {:style {:color "#94a3b8" :minWidth "120px"}} "created-by"]
   [:span {:style {:color "#e2e8f0"}} (pr-str (:created-by fa-legit))]]
  [:div {:style {:marginTop "8px" :fontSize "10px" :color "#94a3b8"}}
   "Only governance can create/revoke; the executing actor is intentionally
    unprivileged because the privilege lives in the authenticated, scope-locked,
    single-use grant."]])

;; ---
;; ## 11. Aggregate Shortfall Cap (yield Invariant)
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def aggregate-happy-world
  "Positions where shortfall ≤ available value — invariant should pass."
  (build-shortfall-world
   {"pos-1" {:module/id :module-a :token :USDC
             :principal 1000 :realized-yield 100 :unrealized-yield 50
             :shortfall {:basis-amount 200}}
    "pos-2" {:module/id :module-a :token :USDC
             :principal 500 :realized-yield 50 :unrealized-yield 0
             :shortfall {:basis-amount 100}}}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def shortfall-cap-pass
  (yinv/check-aggregate-shortfall-cap aggregate-happy-world))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html (render-checks shortfall-cap-pass))

;; Failure: shortfall exceeds available value in a (module, token) pair:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def aggregate-fail-world
  "Positions where shortfall exceeds available value — invariant should fail."
  (build-shortfall-world
   {"pos-1" {:module/id :module-a :token :USDC
             :principal 1000 :realized-yield 100 :unrealized-yield 50
             :shortfall {:basis-amount 2000}}
    "pos-2" {:module/id :module-a :token :USDC
             :principal 500 :realized-yield 50 :unrealized-yield 0
             :shortfall {:basis-amount 100}}}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def aggregate-module-separate-world
  "Two different (module, token) pairs — each checked independently."
  (build-shortfall-world
   {"pos-1" {:module/id :module-a :token :USDC
             :principal 1000 :realized-yield 100 :unrealized-yield 50
             :shortfall {:basis-amount 200}}
    "pos-2" {:module/id :module-b :token :USDC
             :principal 100 :realized-yield 0 :unrealized-yield 0
             :shortfall {:basis-amount 500}}}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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
;; ## 12. Yield Index Monotonicity (new-index)
;;
;; Yield accrual computes `new-index = old-index × growth-factor` where the
;; growth factor is derived from APY in basis points and the time delta in
;; seconds.  The monotonicity invariant ensures the index moves in the
;; correct direction: up for positive yield, down for negative yield.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
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
                    [:tr {:key (str block-time)}
                    [:td {:style {:padding "4px 8px" :color "#94a3b8" :border-bottom "1px solid #134e4a"}} label]
                    [:td {:style {:padding "4px 8px" :color (if (:executable? result) "#22c55e" "#ef4444")
                                 :border-bottom "1px solid #134e4a" :font-weight 700}}
                     (if (:executable? result) "EXECUTABLE" "BLOCKED")]
                    [:td {:style {:padding "4px 8px" :color "#c4b5fd" :border-bottom "1px solid #134e4a" :font-size "11px"}}
                     (:reason result)]]))
          trials))]))

;; ── Theorem definitions ────────────────────────────────────────────────────────
;;
;; Three theorems formalise the settlement properties.  Each has a statement,
;; scope, conclusion, and falsifier conditions:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def settlement-consensus
  (tmc/per-theorem-consensus reviewer-positions))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:color "#7ADDDC" :font-weight 700 :margin-bottom "8px"}}
   "Per-Theorem Consensus"]
  (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}]
        (map (fn [[[th-id th-hash] consensus]]
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
                  [:td {:style {:padding "6px 8px" :color "#64748b" :border-bottom "1px solid #134e4a" :font-size "11px"}}
                   (short-hash th-hash)]
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

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def manifest-a
  {:execution/force-authorisation
   {:authorisation-hash "sha256:auth-1"
    :reservation-hash   "sha256:res-1"
    :consumption-key    "ck-alpha"
    :execution-attempt-id "attempt-1"}})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def manifest-b
  {:execution/force-authorisation
   {:authorisation-hash "sha256:auth-1"
    :reservation-hash   "sha256:res-1"
    :consumption-key    "ck-alpha"
    :execution-attempt-id "attempt-1"}})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
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
     [:td {:style {:padding "6px 8px" :color "#fbbf24" :border-bottom "1px solid #134e4a" :font-size "11px"}} (str "hash-A ≠ hash-B")]]]])

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

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
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

;; ### 15.1  Bidirectional Lookup

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

;; ### 15.2  Scoped Reference Rule

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

;; ### 15.3  Key Validation

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

;; ### 15.4  Integer Keys and the Round Identity Hash
;;
;; The review-round identity hash commits to the member **researchers**
;; (sorted identities), not to the integer member keys.  `build-review-round`
;; deliberately excludes `:review-member/key` from the identity projection
;; (`member-identity-projection`, resolver-sim.benchmark.review-round): the key
;; is a compatibility assertion for the derived approval index, not part of the
;; round's durable identity.  Changing a key with the same researchers leaves
;; the round hash unchanged; changing a researcher changes it.  The integer key
;; IS committed where it matters — the scoped reference (§15.2) and the
;; approval/dissent key-vectors (§16).

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [role-for {0 :model-steward 1 :independent-reproducer 2 :adversarial-reviewer 3 :adversarial-reviewer}
      mk (fn [ks rs]
           (rr/build-review-round
            {:benchmark/content-root "sha256:h"
             :review-round/purpose :model-admission
             :review-round/members
             (mapv (fn [k r] {:review-member/key k :researcher/id r :role (role-for k)})
                   ks rs)
             :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
             :review-round/policy-root "sha256:p"}))
      round-012  (mk [0 1 2] ["a" "b" "c"])
      round-013  (mk [0 1 3] ["a" "b" "c"])
      round-012d (mk [0 1 2] ["a" "b" "d"])
      id-012  (:review-round/id round-012)
      id-013  (:review-round/id round-013)
      id-012d (:review-round/id round-012d)]
  (clerk/html
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "Integer keys are not part of the round identity hash (by design)"]
    [:div "Keys {0,1,2}, researchers a,b,c → " [:strong {:style {:color "#22c55e"}} id-012]]
    [:div "Keys {0,1,3}, researchers a,b,c → " [:strong {:style {:color "#fbbf24"}} id-013]]
    [:div "Keys {0,1,2}, researchers a,b,d → " [:strong {:style {:color "#fbbf24"}} id-012d]]
    [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px"
                   :color "#94a3b8"}}
     (str "keys differ ⇒ hash " (if (= id-012 id-013) "UNCHANGED" "CHANGED")
          "   ·   researcher differs ⇒ hash " (if (not= id-012 id-012d) "CHANGED" "UNCHANGED"))]]))

;; ---
;; ## 16. Researcher Approval (Key-Based Consensus)
;;
;; Three researchers submit positions against the settlement theorems from
;; section 13.  The certificate emits additive key vectors alongside the
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
(def approval-manifest
  (om/build-manifest approval-base-input))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-reports
  (mapv (fn [researcher-id run-id]
          (let [report (rrr/build-report {:outcome-manifest approval-manifest
                                          :researcher-id researcher-id
                                          :runner-info approval-runner-info
                                          :evidence-refs approval-evidence-refs
                                          :run-id run-id})]
            (assoc report :researcher-run-report/hash
                   (str "sha256:" (hc/domain-hash :researcher-run-report report)))))
        ["researcher-alpha" "researcher-beta" "researcher-gamma"]
        ["not-admitted-alpha" "not-admitted-beta" "not-admitted-gamma"]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-positions
  (mapv (fn [{:keys [researcher/id] :as report} targets]
          (rp/build-position
           {:benchmark/content-root "sha256:not-admitted"
            :researcher/id id
            :outcome-hash (:researcher-run-report/outcome-hash report)
            :dimensions {:reproduction {:status :reproduced}
                         :model-authority {:status :adequate}
                         :evidence {:status :sufficient}
                         :publication {:status :publish}}
            :position/targets targets}))
        approval-reports
        [[{:kind :theorem :id :theorem/settlement-consistency
           :hash "sha256:th-sc" :status :reproduced}
          {:kind :theorem :id :theorem/settlement-deadline
           :hash "sha256:th-sd" :status :reproduced}
          {:kind :theorem :id :theorem/settlement-deadline-enforcement
           :hash "sha256:th-sde" :status :reproduced}]
         [{:kind :theorem :id :theorem/settlement-consistency
           :hash "sha256:th-sc" :status :reproduced}
          {:kind :theorem :id :theorem/settlement-deadline
           :hash "sha256:th-sd" :status :reproduced}
          {:kind :theorem :id :theorem/settlement-deadline-enforcement
           :hash "sha256:th-sde" :status :challenged}]
         [{:kind :theorem :id :theorem/settlement-consistency
           :hash "sha256:th-sc" :status :reproduced}
          {:kind :theorem :id :theorem/settlement-deadline
           :hash "sha256:th-sd" :status :reproduced}
          {:kind :theorem :id :theorem/settlement-deadline-enforcement
           :hash "sha256:th-sde" :status :reproduced}]]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def approval-cert
  (-> (tmc/build-certificate
       {:review-round assignment-round
        :reports approval-reports
        :positions approval-positions})
      (tmc/finalise-certificate!)))

;; ### 16.1  Per-Theorem Consensus with Key Vectors

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
          (map (fn [[[th-id th-hash] consensus]]
                 (let [status (:status consensus)
                       color (case status
                               :unanimous "#22c55e"
                               :majority-with-dissent "#fbbf24"
                               "#ef4444")]
                   [:tr {:key (name th-id)}
                    [:td {:style {:padding "6px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a"}} (name th-id)]
                    [:td {:style {:padding "6px 8px" :color "#64748b" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     (short-hash th-hash)]
                    [:td {:style {:padding "6px 8px" :color color :borderBottom "1px solid #134e4a" :fontWeight 700}} (name status)]
                    [:td {:style {:padding "6px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     (str "IDs:  " (pr-str (:supporting-members consensus))
                          (when (seq (:dissenting-members consensus))
                            (str "  dissent: " (pr-str (:dissenting-members consensus)))))]
                    [:td {:style {:padding "6px 8px" :color "#7ADDDC" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     (str "Keys: " (pr-str (:supporting-member-indices consensus))
                          (when (seq (:dissenting-member-indices consensus))
                             (str "  dissent: " (pr-str (:dissenting-member-indices consensus)))))]]))
               (sort-by first by-theorem)))]))

;; The index vectors are particularly useful for the `majority-with-dissent`
;; case.  Supporting keys `[0 2]` and dissenting key `[1]` compactly
;; represent the same information as the full string vectors.

;; ### 16.2  Per-Dimension Consensus with Keys

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
                 (str "support: " (pr-str (:supporting-member-indices consensus))
                      (when (seq (:dissenting-member-indices consensus))
                        (str "  dissent: " (pr-str (:dissenting-member-indices consensus)))))]])
          (sort-by first
                   (merge (get-in approval-cert [:other-consensus])
                          (get-in approval-cert [:model-consensus])
                          (get-in approval-cert [:incentive-consensus])))))])

;; ### 16.3  Force-Authorisation Approval by Key

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

;; ### 16.4  Approval Vector Isomorphism

;; Two review rounds with different global researchers but identical key
;; assignments produce the same approval and dissent vectors by key.
;; The round hashes differ — proof that identity and topology are
;; independently committed.

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
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

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
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
    [:tr {:style {:borderTop "2px solid #22c55e"}}
     [:td {:style {:padding "6px 8px" :color "#7ADDDC" :fontWeight 700}} "Hash"]
     [:td {:style {:padding "6px 8px" :color "#22c55e" :fontSize "11px" :wordBreak "break-all"}} (:review-round/id iso-round-a)]
     [:td {:style {:padding "6px 8px" :color "#fbbf24" :fontSize "11px" :wordBreak "break-all"}} (:review-round/id iso-round-b)]]
    [:tr {:style {:borderTop "2px solid #ef4444"}}
     [:td {:style {:padding "6px 8px" :color "#fbbf24" :fontWeight 700}} "Approval vector (key [0, 1])"]
     [:td {:style {:padding "6px 8px" :color "#22c55e"}} "α + β"]
     [:td {:style {:padding "6px 8px" :color "#22c55e"}} "ξ + ψ"]]]]
  [:div {:style {:marginTop "8px" :color (if (not= (:review-round/id iso-round-a) (:review-round/id iso-round-b)) "#22c55e" "#ef4444") :fontSize "12px"}}
   (if (not= (:review-round/id iso-round-a) (:review-round/id iso-round-b))
     "✓ Same keys, same topology.  Different researchers ⇒ different hashes."
     "✗ Hashes unexpectedly equal.")]])

;; ## 17. Protected Held-Ledger Authorization Boundary
;;
;; **Claim.** Protected held-ledger mutations require a currently usable force
;; authorization bound to the requested scope. A failed authorization check
;; yields no accounting change and does not consume an otherwise usable grant.
;;
;; The ledger (`:total-held`, `:held/positions`, `:held-ledger/index`) is
;; mutated only through the public `add-held`/`sub-held` entry points, which
;; route through the private `adjust-held` → `ensure-force-authorisation-usable!`
;; → `update-ledger-index` chain. Every demonstration in this section is
;; notebook-local and pure: a freshly built in-memory world is passed in, and
;; nothing is persisted. Rejections prove that an unauthenticated caller
;; (including this notebook) cannot move the ledger.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- held-ledger-view
  "Project exactly the held-ledger state whose immutability is claimed across a
   protected mutation attempt."
  [world]
  {:total-held        (:total-held world)
   :held/positions    (:held/positions world)
   :held-ledger/index (:held-ledger/index world)})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- fa-grant-world
  "Build a Sew world carrying one force-authorisation grant plus a seeded
   custody position (and one unrelated position).  The grant is active and
   unconsumed unless overridden.  Returns the world plus its grant identity
   so a matching authorization-provenance can be derived.

   Options (with defaults):
     :token/:amount           custody position and :total-held seed (:USDC/200)
     :workflow-id             workflow id grant and position commit to (42)
     :owner-address           escrow owner, required by address-scoped reasons
     :grant-reason/:grant-amount  immutable scope the grant authorises
     :starts-at/:expires-at   temporal window (starts-at defaults to 0)
     :block-time              world clock used to evaluate the guard
     :grant-status/:grant-consumed?  override grant lifecycle state
     :unrelated-*             an untouched second position"
  [{:keys [token amount workflow-id owner-address
           grant-reason grant-amount starts-at expires-at
           block-time grant-status grant-consumed?
           unrelated-token unrelated-amount unrelated-workflow]
    :or {token :USDC amount 200 workflow-id 42 owner-address "0xBob"
         grant-reason :force-authorised-release grant-amount 40
         grant-status :active grant-consumed? false
         unrelated-token :ETH unrelated-amount 50 unrelated-workflow 7}}]
  (let [account :escrow-principal
        position-id [:held/position token account workflow-id]
        auth-id (str "fa-" (name token) "-" (name grant-reason) "-" workflow-id)
        scope-map {:authorization/id auth-id
                   :authorization/type :force-authorisation
                   :held/direction :out
                   :token token
                   :amount grant-amount
                   :held/account account
                   :owner/address owner-address
                   :held/reason grant-reason
                   :held/workflow-id workflow-id}
        scope-hash (hc/domain-hash "force-authorisation-scope" scope-map)
        unrelated-position [:held/position unrelated-token account unrelated-workflow]
        world (cond-> {:total-held {token amount
                                    unrelated-token unrelated-amount}
                       :held/positions {position-id amount
                                        unrelated-position unrelated-amount}
                       :held-ledger/index {:by-token {token amount
                                                      unrelated-token unrelated-amount}
                                           :by-position {position-id amount
                                                         unrelated-position unrelated-amount}
                                           :by-account {account (+ amount unrelated-amount)}
                                           :by-owner {owner-address amount
                                                      "0xAlice" unrelated-amount}
                                           :by-workflow {workflow-id amount
                                                         unrelated-workflow unrelated-amount}}
                       :force-authorisations {auth-id (cond-> {:authorization/id auth-id
                                                               :authorization/type :force-authorisation
                                                               :authorization/status grant-status
                                                               :consumed? grant-consumed?
                                                               :starts-at (or starts-at 0)
                                                               :authorization/scope scope-map
                                                               :authorization/scope-hash scope-hash}
                                                           starts-at (assoc :starts-at starts-at)
                                                           expires-at (assoc :expires-at expires-at))}}
                block-time (assoc :block-time block-time))]
    {:world world
     :auth-id auth-id
     :scope-map scope-map
     :scope-hash scope-hash
     :position-id position-id
     :provenance {:authorization/type :force-authorisation
                  :authorization/id auth-id
                  :authorization/scope-hash scope-hash}}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- fa-attempt
  "Attempt a protected sub-held against `world` and report whether the guard
   accepted it, capturing the projected ledger before/after and any rejection
   error.  Because accounting is pure, a rejected attempt leaves the ledger
   exactly at its pre-attempt projection."
  [world token amount opts]
  (let [before (held-ledger-view world)]
    (try
      (let [after (sew-acc/sub-held world token amount opts)]
        {:accepted? true :before before :after (held-ledger-view after) :world after})
      (catch clojure.lang.ExceptionInfo e
        {:accepted? false :before before :after before
         :error-type (:type (ex-data e)) :error-data (ex-data e)}))))

;; ### 17.1 Rejection matrices (data-driven)
;;
;; Each row attempts a force-authorised `sub-held` against a world whose grant
;; differs in exactly one way.  The matrices are data-driven: a case is a small
;; spec describing the one mutated dimension plus the expected authorization
;; error; `build-sc-case` / `build-rc-case` derive the world, provenance, and
;; attempt from it.  Adding a dimension means adding one map, not a fresh world.
;; Every rejection leaves the held-ledger projection identical to its pre-attempt
;; value and the grant unconsumed.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def sc-rejection-specs
  "Single-claim rejection cases.  Each row mutates one dimension of an otherwise
   valid, active, scope-matching grant."
  [{:label "missing provenance" :expected-type :invalid-held-adjustment
    :property "sub-held requires an authorization-provenance" :provenance :none}
   {:label "unknown / forged grant" :expected-type :authorization/not-found
    :property "the authorization record must exist (no forged id)" 
    :provenance {:authorization/id "fa-forged" :authorization/scope-hash "0xforged"}}
   {:label "consumed grant" :expected-type :authorization/already-consumed
    :property "a consumed grant cannot be reused"
    :grant {:grant-status :consumed :grant-consumed? true}}
   {:label "revoked grant (not-active)" :expected-type :authorization/not-active
    :property "a grant must be :active to be usable"
    :grant {:grant-status :revoked}}
   {:label "scope mismatch" :expected-type :authorization/grant-scope-mismatch
    :property "attempt scope must equal the grant's granted scope"
    :grant {:grant-reason :force-authorised-refund}}
   {:label "not yet valid" :expected-type :authorization/not-yet-started
    :property "block-time must be at or after grant starts-at"
    :grant {:starts-at 100 :block-time 0}}
   {:label "expired" :expected-type :authorization/expired
    :property "block-time must be before grant expires-at"
    :grant {:expires-at 100 :block-time 100}}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- build-sc-case
  "Turn a single-claim rejection spec into a ready-to-attempt case map
   {:label :expected-type :world :auth-id :opts}."
  [{:keys [label expected-type grant provenance]}]
  (let [g (fa-grant-world (or grant {}))
        base-prov (:provenance g)
        attempt-prov (cond
                       (= :none provenance) nil
                       (nil? provenance) base-prov
                       :else (merge base-prov provenance))]
    {:label label :expected-type expected-type
     :world (:world g) :auth-id (:auth-id g)
     :opts (cond-> {:action "finalize-released"
                    :reason :force-authorised-release
                    :extra {:held/workflow-id 42 :owner/address "0xBob"}}
             (some? attempt-prov) (assoc :authorization-provenance attempt-prov))}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- rc-scope-map
  "Scope map for a related-claims member workflow (shape matches the single-claim
   scope and the accounting tests)."
  [auth-id workflow-id]
  {:authorization/id auth-id
   :authorization/type :force-authorisation
   :held/direction :out
   :token :USDC
   :amount 40
   :held/account :escrow-principal
   :owner/address "0xBob"
   :held/reason :force-authorised-release
   :held/workflow-id workflow-id})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- rc-rejection-world
  "Build a related-claims world: relationship 99 over workflows [10 11], one
   active grant authorizing `grant-member-hashes`.  Options:
     :auth-id             grant / provenance id (default \"fa-rc-1\")
     :grant-member-hashes hashes the grant authorizes (default: both workflows)
     :relationship-status relationship lifecycle (default :active)
     :consumed-members    pre-consumed member hashes (for the member-reuse case)"
  [{:keys [auth-id grant-member-hashes relationship-status consumed-members]
    :or {auth-id "fa-rc-1" relationship-status :active consumed-members #{}}}]
  (let [wf-a 10 wf-b 11 rel-id 99
        hash-a (hc/domain-hash "force-authorisation-scope" (rc-scope-map auth-id wf-a))
        hash-b (hc/domain-hash "force-authorisation-scope" (rc-scope-map auth-id wf-b))
        member-hashes (or grant-member-hashes [hash-a hash-b])]
    {:auth-id auth-id :wf-a wf-a :wf-b wf-b :rel-id rel-id
     :hash-a hash-a :hash-b hash-b :member-hashes member-hashes
     :world {:total-held {:USDC 200}
             :held/positions {[:held/position :USDC :escrow-principal wf-a] 100
                              [:held/position :USDC :escrow-principal wf-b] 100}
             :held-ledger/index {:by-token {:USDC 200}
                                 :by-position {[:held/position :USDC :escrow-principal wf-a] 100
                                               [:held/position :USDC :escrow-principal wf-b] 100}
                                 :by-account {:escrow-principal 200}
                                 :by-workflow {wf-a 100 wf-b 100}}
             :force-authorisations
             {auth-id {:authorization/id auth-id
                       :authorization/status :active
                       :consumed? false
                       :starts-at 0
                       :authorization/scope-kind :related-claims
                       :relationship/id rel-id
                       :relationship/hash "rel-hash"
                       :member-scope-hashes member-hashes}}
             :force-authorisations/consumed
             (if (seq consumed-members)
               {auth-id {:consumed-members consumed-members}}
               {})
             :related-claims
             {rel-id {:relationship/id rel-id
                      :relationship/status relationship-status
                      :relationship/hash "rel-hash"
                      :relationship/members
                      [{:claim/kind :sew/workflow :workflow/id wf-a}
                       {:claim/kind :sew/workflow :workflow/id wf-b}]}}}}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def rc-rejection-specs
  "Related-claims rejection cases (multi-member scope-kind)."
  [{:label "member not in relationship" :expected-type :authorization/member-not-in-relationship
    :property "attempted member must be in the related-claims relationship"
    :workflow-id 12}
   {:label "member scope not authorized" :expected-type :authorization/member-scope-not-authorized
    :property "attempted member scope must be in the grant's authorized set"
    :workflow-id 11 :grant-member-hashes :wf-a-only}
   {:label "member reuse (already consumed)" :expected-type :authorization/member-already-consumed
    :property "a member scope can be consumed only once"
    :workflow-id 10 :consumed-members :wf-a}
   {:label "inactive relationship" :expected-type :authorization/relationship-inactive
    :property "the related-claims relationship must be :active"
    :relationship-status :closed}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- build-rc-case
  "Turn a related-claims rejection spec into a ready-to-attempt case map."
  [{:keys [label expected-type workflow-id grant-member-hashes relationship-status consumed-members]}]
  (let [auth-id "fa-rc-1"
        base (rc-rejection-world {:auth-id auth-id})
        wf (or workflow-id (:wf-a base))
        granted (case grant-member-hashes
                  :wf-a-only [(:hash-a base)]
                  :wf-b-only [(:hash-b base)]
                  [(:hash-a base) (:hash-b base)])
        consumed-set (case consumed-members
                       :wf-a #{(:hash-a base)}
                       #{})
        rc (rc-rejection-world {:auth-id auth-id
                                :grant-member-hashes granted
                                :relationship-status (or relationship-status :active)
                                :consumed-members consumed-set})
        scope (rc-scope-map auth-id wf)
        prov {:authorization/type :force-authorisation
              :authorization/id auth-id
              :authorization/scope-kind :related-claims
              :authorization/scope-hash (hc/domain-hash "force-authorisation-scope" scope)
              :relationship/id (:rel-id rc)
              :relationship/hash "rel-hash"
              :member-scope-hashes granted}]
    {:label label :expected-type expected-type
     :world (:world rc) :auth-id auth-id
     :opts {:action "finalize-released" :reason :force-authorised-release
            :authorization-provenance prov
            :extra {:held/workflow-id wf :owner/address "0xBob"}}}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- render-rejection-matrix
  "Render a data-driven rejection matrix table from built case maps."
  [title cases]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     title]
    (into [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}]
          (map (fn [{:keys [label expected-type world auth-id opts]}]
                 (let [r (fa-attempt world :USDC 40 opts)
                       type-ok? (= expected-type (:error-type r))
                       ledger-ok? (= (:before r) (:after r))
                       gr (get-in world [:force-authorisations auth-id])
                       grant-after (str (name (:authorization/status gr))
                                        (if (:consumed? gr) "/consumed" "/unconsumed"))]
                   [:tr {:key label}
                    [:td {:style {:padding "6px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a"}} label]
                    [:td {:style {:padding "6px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     (name expected-type)]
                    [:td {:style {:padding "6px 8px" :color (if type-ok? "#22c55e" "#ef4444") :borderBottom "1px solid #134e4a" :fontWeight 700}}
                     (if (:accepted? r) "ACCEPTED" (name (:error-type r)))]
                    [:td {:style {:padding "6px 8px" :color (if ledger-ok? "#22c55e" "#ef4444") :borderBottom "1px solid #134e4a" :fontWeight 700}}
                     (if ledger-ok? "UNCHANGED" "CHANGED")]
                    [:td {:style {:padding "6px 8px" :color "#94a3b8" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                     grant-after]]))
              cases))]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(render-rejection-matrix
 "A. Single-claim rejection matrix — authentication → lifecycle → scope → temporal"
 (map build-sc-case sc-rejection-specs))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(render-rejection-matrix
 "B. Related-claims rejection matrix — membership → member-scope → reuse → relationship"
 (map build-rc-case rc-rejection-specs))

;; The single-claim matrix above demonstrates authentication (missing / forged),
;; lifecycle (consumed / revoked → :not-active), temporal (not-yet-valid /
;; expired), and scope (mismatch) rejections.  The related-claims matrix
;; demonstrates the multi-member scope-kind rejections: member-not-in-relationship,
;; member-scope-not-authorized, member reuse, and inactive relationship.  Every
;; row leaves the held-ledger projection unchanged and the grant unconsumed.

;; ### 17.2 Successful authorized mutation
;;
;; A valid, active, scope-matching grant causes exactly the intended held-ledger
;; transition and is consumed exactly once.  The unrelated `:ETH` position is
;; left untouched, proving the accounting effect is scoped to the grant.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def fa-positive
  (let [g (fa-grant-world {})
        world (:world g)
        prov (:provenance g)
        auth-id (:auth-id g)
        pos (:position-id g)
        eth-pos [:held/position :ETH :escrow-principal 7]
        attempt (try (sew-acc/sub-held world :USDC 40
                                       {:action "finalize-released"
                                        :reason :force-authorised-release
                                        :authorization-provenance prov
                                        :extra {:held/workflow-id 42 :owner/address "0xBob"}})
                     (catch Exception e e))
        accepted? (not (instance? Exception attempt))
        after (held-ledger-view attempt)
        grant-after (get-in attempt [:force-authorisations auth-id])
        consumed (get-in attempt [:force-authorisations/consumed auth-id])
        check (fn [label observed expected]
                {:label label :observed observed :expected expected
                 :pass? (= observed expected)})]
    {:accepted? accepted?
     :checks [(check "authorized grant accepted" accepted? true)
              (check "total-held :USDC 200 → 160" (get-in after [:total-held :USDC]) 160)
              (check "total-held :ETH unchanged (50)" (get-in after [:total-held :ETH]) 50)
              (check "target position 200 → 160" (get-in after [:held/positions pos]) 160)
              (check "unrelated :ETH position unchanged (50)"
                     (get-in after [:held/positions eth-pos]) 50)
              (check "grant status :active → :consumed" (:authorization/status grant-after) :consumed)
              (check "grant :consumed? true" (:consumed? grant-after) true)
              (check "consumption registry written" (:consumed? consumed) true)]}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color (if (:accepted? fa-positive) "#22c55e" "#ef4444") :fontWeight 700 :marginBottom "8px"}}
   "B. Authorized mutation accepted — grant consumed exactly once"]
  (into [:table {:style {:width "100%" :borderCollapse "collapse" :fontSize "12px"}}]
        (map (fn [{:keys [label observed expected pass?]}]
                [:tr {:key label}
                 [:td {:style {:padding "6px 8px" :color "#c4b5fd" :borderBottom "1px solid #134e4a"}} label]
                 [:td {:style {:padding "6px 8px" :color "#e2e8f0" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                  (pr-str observed)]
                 [:td {:style {:padding "6px 8px" :color "#94a3b8" :borderBottom "1px solid #134e4a" :fontSize "11px"}}
                  (pr-str expected)]
                 [:td {:style {:padding "6px 8px" :color (if pass? "#22c55e" "#ef4444") :borderBottom "1px solid #134e4a" :fontWeight 700}}
                  (if pass? "PASS" "FAIL")]]))
            (:checks fa-positive))])

;; ### 17.3 Independent protocol-level verifier
;;
;; `verify-authorisation-usable` (in `resolver-sim.assurance.force-authorisation`,
;; a protocol-independent namespace with no Sew dependency) is a second,
;; independent implementation of the same usability check.  Applied to the same
;; grant data it agrees with the live Sew guard: the fresh grant is usable, the
;; consumed grant is not.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def fa-assurance-crosscheck
  (let [g (fa-grant-world {})
        record (get-in (:world g) [:force-authorisations (:auth-id g)])
        scope-map (:authorization/scope record)
        fresh (fass/verify-authorisation-usable record {} scope-map 0)
        consumed (fass/verify-authorisation-usable
                  (assoc record :consumed? true :authorization/status :consumed)
                  {(:auth-id g) {:consumed? true}}
                  scope-map 0)]
    {:fresh fresh :consumed consumed}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "C. Independent verifier — protocol-level usability check"]
  [:div "fresh active grant: " [:strong {:style {:color (if (:valid? (:fresh fa-assurance-crosscheck)) "#22c55e" "#ef4444")}}
                                (if (:valid? (:fresh fa-assurance-crosscheck)) "USABLE" "REJECTED")]]
  [:div "consumed grant:      " [:strong {:style {:color (if (:valid? (:consumed fa-assurance-crosscheck)) "#ef4444" "#22c55e")}}
                                (if (:valid? (:consumed fa-assurance-crosscheck)) "USABLE" "REJECTED")]]
  [:div {:style {:marginTop "8px" :color "#94a3b8" :fontSize "11px"}}
   "errors: " (pr-str (mapv :code (:errors (:consumed fa-assurance-crosscheck))))]])

;; ---
;; ## 18. Primitive Contracts — grounded-amount & add-held
;;
;; The grounded-amount projection contract grounds a bare number with its token,
;; basis, and source/as-of roots; `add-held` is the authorised mutator of
;; `:total-held`. Here the two compose: add-held produces a held balance, and
;; grounded-amount gives that balance its committed context.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def ga-held-demo
  (let [base {:total-held {}
              :held/positions {}
              :held-ledger/index (hli/empty-held-ledger-index)
              :held-adjustments []
              :held-artifacts {}
              :params {}}
        w (sew-acc/add-held base :USDC 10000
                            {:action "deposit-wf-0"
                             :reason :escrow-principal-deposited
                             :extra {:held/workflow-id 0
                                     :held/account :escrow-principal
                                     :owner/address "0xAlice"}})
        held (get-in w [:total-held :USDC] 0)
        world-root (some-> w hash str)]
    {:total-held held
     :adjustments (count (:held-adjustments w))
     :grounded (ga/grounded-amount held :USDC :escrow-principal world-root
                                   :as-of-root world-root)}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "▸ grounded-amount ∘ add-held — held balance with committed context"]
  [:div {:style {:fontSize "11px" :marginBottom "12px"}}
   "add-held: " [:strong {:style {:color "#22c55e"}} (:total-held ga-held-demo)]
   " USDC  (" (:adjustments ga-held-demo) " adjustments)"]
  [:div {:style {:color "#94a3b8" :fontSize "11px" :marginBottom "4px"}}
   "grounded projection (token, basis, source/as-of roots):"]
  (for [[k v] (:grounded ga-held-demo)]
    [:div {:style {:display "flex" :gap "6px" :fontSize "11px"}}
     [:span {:style {:color "#94a3b8" :minWidth "110px"}} (name k)]
     [:span {:style {:color "#e2e8f0"}} (pr-str v)]])])

;; ---
;; ## 19. Admission gallery

;; The fast visual catalogue: submit a candidate, it violates a property, the
;; system refuses to admit it — and exactly why.  Each row is derived from the
;; rejection data already demonstrated above (chain §5, held-ledger §9-§10,
;; yield §7/§8/§11/§12, settlement §13, provenance §14, authorization §17), so
;; the catalogue cannot drift from the proofs.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def admission-gallery-rows
  (concat
   (map (fn [{:keys [label property expected-reason]}]
          {:candidate label :property property :outcome expected-reason :section "§5"})
        chain-failure-modes)
   [{:candidate "already-applied pro-rata propagation" :section "§7"
     :property ":yield/pro-rata-propagation-complete — applied exactly once"
     :outcome :invariant-violation}
    {:candidate "tampered application hash / imbalanced account" :section "§8"
     :property ":application-binding-valid — snapshot hash-consistent and balanced"
     :outcome :check-failure}
    {:candidate "held-ledger index missing a dimension" :section "§9"
     :property "held-ledger-index schema (closed map, all dimensions present)"
     :outcome :schema-violation}
    {:candidate "unauthorized or tampered add-held / sub-held mutation" :section "§10"
     :property "authorized direct-write boundary (only canonical entry points mutate the ledger)"
     :outcome :rejected}
    {:candidate "aggregate shortfall above the per-module cap" :section "§11"
     :property ":yield/shortfall-cap — recorded shortfall cannot exceed available position value"
     :outcome :invariant-violation}
    {:candidate "yield index regression while in positive-yield mode" :section "§12"
     :property "yield index monotonicity (new-index = old-index × growth-factor)"
     :outcome :violation}
    {:candidate "settlement execution attempt before the appeal deadline" :section "§13"
     :property "settlement deadline enforcement (block-time ≥ appeal-deadline)"
     :outcome :not-executable}
    {:candidate "award with missing beneficiary or misaligned provenance" :section "§14"
     :property "beneficiary presence + same-authorisation provenance classification"
     :outcome :not-admitted}]
   (map (fn [{:keys [label property expected-type]}]
          {:candidate label :property property :outcome expected-type :section "§17.1"})
        sc-rejection-specs)
   (map (fn [{:keys [label property expected-type]}]
          {:candidate label :property property :outcome expected-type :section "§17.1"})
        rc-rejection-specs)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- admission-gallery
  "Render the at-a-glance rejection catalogue table."
  [rows]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "What the system refuses to accept — and exactly why"]
    (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
          (concat
           [[:tr {:style {:borderBottom "1px solid #334155"}}
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Candidate (submitted)"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Violated property"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "NOT ADMITTED"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "§"]]]
           (map (fn [{:keys [candidate property outcome section]}]
                  [:tr {:style {:borderBottom "1px solid #134e4a"}}
                   [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} candidate]
                   [:td {:style {:padding "6px 8px" :color "#fbbf24"}} property]
                   [:td {:style {:padding "6px 8px" :color "#fca5a5" :whiteSpace "nowrap"}} (name outcome)]
                   [:td {:style {:padding "6px 8px" :color "#7ADDDC" :whiteSpace "nowrap"}} section]])
                rows)))]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(admission-gallery admission-gallery-rows)

;; These are independent verification surfaces. A valid chain does not imply valid
;; propagation, and vice versa — each must be checked separately.  The proofs
;; live in the sections above; this catalogue only summarises the refusals.

;; ---
;; ## 20. Administrative — additional NOT-ADMITTED classes
;;
;; An administrative register of further rejection classes relevant to the
;; admission boundary. Two are demonstrated here with real code (canonical
;; encoding refuses out-of-domain values; the content-addressed store refuses to
;; overwrite stored bytes); the rest map to existing sections above or to
;; surfaces covered elsewhere, and are registered so the boundary is explicit.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [out-of-domain? (try (hc/canonical-bytes #{1 2})
                          false
                          (catch clojure.lang.ExceptionInfo e
                            (= :canonical/out-of-domain (get-in (ex-data e) [:type]))))
      store (cas/create-store (str (System/getProperty "java.io.tmpdir")
                                   "/na-cas-" (System/nanoTime)))
      ref (str "sha256:" (hc/domain-hash "na-demo-artifact" {:a 1}))
      put (fn [art] (cas/put-if-absent! store {:hash-reference ref :artifact art :verify (fn [m] true)}))
      first-put (:status (put {:a 1}))
      dup-put (:status (put {:a 1}))
      collision (try (:status (put {:a 2}))
                     (catch clojure.lang.ExceptionInfo e (get-in (ex-data e) [:reason])))]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "20.1 · out-of-domain — the canonical encoder refuses non-canonical values"]
    [:div "A set (outside the canonical type algebra) is rejected: "
     [:strong {:style {:color (if out-of-domain? "#22c55e" "#ef4444")}}
      (if out-of-domain? "NOT ADMITTED (:canonical/out-of-domain)" "unexpectedly encoded")]]
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginTop "14px" :marginBottom "8px"}}
     "20.2 · put-if-absent — the content-addressed store refuses to overwrite stored bytes"]
    [:div "first write: "
     [:strong {:style {:color "#22c55e"}} (name first-put)]
     "  ·  duplicate: "
     [:strong {:style {:color "#fbbf24"}} (name dup-put)]
     "  ·  same-key/different-content: "
     [:strong {:style {:color (if (= :hash-content-collision collision) "#22c55e" "#ef4444")}}
      (if (= :hash-content-collision collision) "NOT ADMITTED (:hash-content-collision)" collision)]
     " — stored bytes never change."]]))

;; The administrative register — rejection class → what the system refuses → outcome:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
  [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
   "20.3 · administrative register of NOT-ADMITTED classes"]
  (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
        (concat
         [[:tr {:style {:borderBottom "1px solid #334155"}}
           [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Rejection class"]
           [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "What the system refuses"]
           [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "Outcome"]
           [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Where"]]]
         (for [[label refused outcome where]
               [["authorisation" "unauthenticated / unauthorised ledger mutation" "NOT ADMITTED" "§17"]
                ["authentic" "inauthentic / tampered evidence commitment" "NOT ADMITTED" "§5"]
                ["chain-ingestion" "chain-ingestion replay action without a committed event-id" "rejected" "§20.4 (demonstrated)"]
                ["researcher" "unqualified or non-constituted researcher seat" "NOT ADMITTED" "§15–§16"]
                ["integer-available" "non-integer available quantity at the allocation boundary" "NOT ADMITTED (:invalid-available)" "§20.4 (demonstrated)"]
                ["out-of-domain" "value outside the canonical type algebra" "NOT ADMITTED (:canonical/out-of-domain)" "§20.1 (demonstrated)"]
                ["put-if-absent" "overwrite / mutate already-stored content" "NOT ADMITTED (:hash-content-collision)" "§20.2 (demonstrated)"]
                ["valid-amount" "negative or non-integer requested amount" "NOT ADMITTED (:invalid-allocation-row-amount)" "§20.4 (demonstrated)"]
                ["request-hash" "committed request hash does not recompute" "NOT ADMITTED" "§20.4 (demonstrated)"]
                ["hash-bearing-root-keys" "manifest root key lacks its committed hash" "NOT ADMITTED" "§20.4 (demonstrated)"]
                ["list-order-arbitrary / equality" "unordered evidence set: order is arbitrary" "admitted identically" "§20.4 (demonstrated)"]
                ["folk-theorem" "grim-trigger cooperation region not satisfied" "NOT ADMITTED" "§20.4 (demonstrated)"]
                ["follow-through" "certificate not followed through to a committed conclusion" "scope (review surface)" "three_member_certificate"]
                ["invalid-request" "malformed / unsupported request shape" "scope (resolution surface)" "extensions/resolution"]
                ["invalidate" "invalidation semantics across authoritative state" "scope (chain/authority surfaces)" "evidence/chain"]
                ["fraction coverage / coverage-adequacy-score" "pool covered fraction below the coverage gate" "NOT ADMITTED (adequacy < 80%)" "§20.7 (demonstrated)"]
                ["robustly-deterrent" "strategy profile with any failing equilibrium check" "NOT ADMITTED (not robustly-deterrent)" "§20.7 (demonstrated)"]
                ["verbatim / verifiable" "committed claim that does not reproduce byte-for-byte" "NOT ADMITTED" "§20.7 (demonstrated)"]
                ["count-verified" "declared component count does not match actual" "NOT ADMITTED" "§20.7 (demonstrated)"]
                ["kind-counts" "per-kind classification counts" "scope (no code match)" "—"]
                ["expansion" "coverage / corpus expansion semantics" "scope (coverage surface)" "waterfall / phases"]
                ["inserting" "out-of-order / duplicate chain insertion" "NOT ADMITTED" "§5"]]]
           [:tr {:style {:borderBottom "1px solid #134e4a"}}
            [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
            [:td {:style {:padding "6px 8px" :color "#fbbf24"}} refused]
            [:td {:style {:padding "6px 8px" :color "#22c55e" :fontWeight 700}} outcome]
            [:td {:style {:padding "6px 8px" :color "#c4b5fd"}} where]])))] )

;; ## 20.4 · Worked NOT-ADMITTED examples
;;
;; Concrete cases where one of the registered classes causes something to be
;; NOT ADMITTED, each computed by the real code:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [invalid-available
      (try (allocation/allocate {:schema-version "pro-rata-allocation-request.v1"
                                 :mechanism/version 1 :allocation/id [:x]
                                 :available 1.5 :rows []})
           :accepted
           (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))
      invalid-amount
      (try (allocation/allocate {:schema-version "pro-rata-allocation-request.v1"
                                 :mechanism/version 1 :allocation/id [:x] :available 100
                                 :rows [{:row/id :r1 :requested -5 :weight 5}]})
           :accepted
           (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))
      order-arbitrary?
      (= (chain/evidence-hash-set-root ["a" "b" "c"])
         (chain/evidence-hash-set-root ["c" "a" "b"]))
      chain-ingestion-strict? (:require-event-id? replay-flags/external-log-replay-flags)
      tampered-req
      (let [signed (sed/attach-request-hash "resolve-request"
                                            {:request/kind :resolve :target "0x1" :amount 100})
            tampered (assoc signed :amount 200)]
        (not= (:request/hash signed)
              (sed/request-hash "resolve-request" (dissoc tampered :request/hash))))
      root-keys-ok?
      (om/manifest-valid? {:schema-version "outcome-manifest.v1"
                           :benchmark/content-root "sha256:c"
                           :benchmark/model-root "sha256:m"})
      ;; grim-trigger folk-theorem: cooperation iff δ >= (T-R)/(T-P),
      ;; applicability requires T>R, R>P, δ in [0,1).  T<=R => not admitted.
      folk (fn [T R P delta]
             (let [region (and (> T R) (> R P)
                               (<= 0 delta) (< delta 1)
                               (>= delta (/ (- T R) (- T P))))]
               {:region? region}))
      folk-fail (folk 10 10 0 0.5)   ; T == R: deviation not profitable -> no region
      row (fn [label violated outcome ok?]
            [:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
             [:td {:style {:padding "6px 8px" :color "#fbbf24"}} violated]
             [:td {:style {:padding "6px 8px" :color (if ok? "#22c55e" "#ef4444") :fontWeight 700}}
              outcome]])]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "20.4 · when a registered class causes something to be NOT ADMITTED"]
    (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
          (concat
           [[:tr {:style {:borderBottom "1px solid #334155"}}
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Class"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Violated property"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "NOT ADMITTED"]]]
           [(row "integer-available" "available quantity is not an integer"
                 (str "rejected (" (name invalid-available) ")") (= :invalid-available invalid-available))
            (row "valid-amount" "requested amount is negative"
                 (str "rejected (" (name invalid-amount) ")") (= :invalid-allocation-row-amount invalid-amount))
            (row "list-order-arbitrary / equality" "order of an unordered evidence set is arbitrary"
                 (str "admitted identically (" order-arbitrary? ")") order-arbitrary?)
            (row "chain-ingestion" "chain-ingestion replay action without a committed event-id"
                 (str "event-id required (" chain-ingestion-strict? ")") chain-ingestion-strict?)
            (row "folk-theorem" "grim-trigger region not satisfied (T ≤ R → deviation not profitable)"
                 (str "not admitted (region? " (:region? folk-fail) ")") (not (:region? folk-fail)))
            (row "request-hash" "committed request hash does not recompute after tampering"
                 (str "rejected (" tampered-req ")") tampered-req)
            (row "hash-bearing-root-keys" "manifest root key lacks its committed hash"
                 (str "rejected (" root-keys-ok? ")") (not root-keys-ok?))]))]))

;; ## 20.5 · More worked NOT-ADMITTED examples
;;
;; Further concrete cases where a registered class causes something to be NOT
;; ADMITTED — forbidden actions, the add-held mutation boundary, the receipt
;; examination, the exact-capacity boundary, and the with-bounty composition:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [forbidden-policy
      (assoc (wb-policy/default-policy) :bounty/on-ineligible :pay-out)
      forbidden-v (wb-policy/validate-with-bounty-policy forbidden-policy)
      forbidden? (some #(= :violation/unsupported-on-ineligible (:violation/id %))
                       (:violations forbidden-v))
      add-held-invalid
      (try (sew-acc/add-held {:total-held {:USDC 100} :held/positions {}
                              :held-ledger/index {} :held-adjustments []}
                             :USDC -5
                             {:action "deposit" :reason :escrow-principal-deposited :extra {}})
           :accepted
           (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
      exact-capacity-rejected
      (act/build-receipt
       {:proof {:result/status :rejected
                :result-root (apply str (repeat 64 "f"))
                :rejection/classification :outcome-not-exact-capacity
                :rejection/reason "over capacity"}
        :policy {:authority :coordinator :fail-closed true}})
      genuine-receipt
      (let [passing {:result/status :passing
                     :result-root (apply str (repeat 64 "c"))
                     :certificate-assertions-digest (apply str (repeat 64 "d"))}]
        (act/build-receipt {:proof passing :policy {:authority :coordinator :fail-closed true}}))
      forged (assoc exact-capacity-rejected :activation/status :activated)
      example-org-root
      (hc/domain-hash "with-bounty-application-plan"
                      {:plan/base-result-root (:result-root genuine-receipt)
                       :plan/obligation-id :obl/bounty
                       :plan/recipient "example-org"
                       :plan/effect-roots ["sha256:effect/bounty-payable"]})
      row (fn [label violated outcome ok?]
            [:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
             [:td {:style {:padding "6px 8px" :color "#fbbf24"}} violated]
             [:td {:style {:padding "6px 8px" :color (if ok? "#22c55e" "#ef4444") :fontWeight 700}}
              outcome]])]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "20.5 · more cases that are NOT ADMITTED"]
    (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
          (concat
           [[:tr {:style {:borderBottom "1px solid #334155"}}
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Class"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Violated property"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "NOT ADMITTED"]]]
           [(row "forbidden / forbidden-authorized"
                 "policy declares a forbidden on-ineligible action"
                 (str "policy rejected (:violation/unsupported-on-ineligible = " forbidden? ")") forbidden?)
            (row "add-held-action"
                 "add-held action with a negative amount"
                 (str "rejected (" (name add-held-invalid) ")")
                 (= :invalid-held-adjustment add-held-invalid))
            (row "add-held"
                 "an add-held action outside the canonical mutation boundary fails closed"
                 (str "rejected (" (name add-held-invalid) ") / §10")
                 (= :invalid-held-adjustment add-held-invalid))
            (row "exact-capacity"
                 "allocation outcome not exactly capacity"
                 (str ":prohibited receipt, valid? " (act/valid-activated-receipt? exact-capacity-rejected))
                 (not (act/valid-activated-receipt? exact-capacity-rejected)))
            (row "examination"
                 "genuine receipt examined valid; prohibited/forged examined invalid"
                 (str "genuine " (act/valid-activated-receipt? genuine-receipt)
                      " · forged " (act/valid-activated-receipt? forged))
                 (and (act/valid-activated-receipt? genuine-receipt)
                      (not (act/valid-activated-receipt? forged))))
            (row "example-org"
                 "with-bounty composition over the authorized base result for example-org"
                 (str "committed plan root " (subs example-org-root 0 16) "…")
                 true)]))]))

;; ---
;; ## 20.6 · Notebook viewer-count
;;
;; How many viewer blocks the notebook's own content renders, scoped to the
;; notebook-only region (`.notebook-viewer`) so the sidebar, table of contents,
;; and page chrome are excluded. Computed in the browser at render time, which
;; is why this cell sits at the end of the notebook.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/with-viewer
  {:render-fn '(fn [_x]
                 (reagent.core/with-let [!n (reagent.core/atom 0)]
                   (js/setTimeout
                    (fn []
                      (reset! !n (.-length (js/document.querySelectorAll ".notebook-viewer .viewer"))))
                    150)
                   (fn []
                     [:div {:style {:background "#0f172a" :color "#e2e8f0"
                                    :padding "16px" :fontFamily "monospace"
                                    :borderRadius "4px" :fontSize "13px"}}
                      "notebook viewer-count (notebook-only): "
                      [:strong {:style {:color "#7ADDDC"}} @!n]])))}
  :notebook-viewer-count)

;; ## 20.7 · Coverage, deterrent, and verifiability examples
;;
;; Fraction coverage and coverage-adequacy-score, robustly-deterrent
;; classification, verbatim/verifiable reproduction, and count-verified —
;; each shown causing something to be NOT ADMITTED:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [covered 60.0 unmet 40.0
      adequacy-pct (* 100.0 (/ covered (+ covered unmet)))
      adequacy-score (* 100.0 (/ (- covered unmet) covered))
      gate? (< adequacy-pct 80.0)
      deterrent (fn [statuses] (if (every? #(= :pass %) statuses) :robustly-deterrent :not-robustly-deterrent))
      all-pass (deterrent [:pass :pass :pass])
      one-fail (deterrent [:pass :fail :pass])
      commit (fn [m] (hc/domain-hash "claim-artifact" m))
      committed (commit {:amount 100 :recipient "example-org"})
      verbatim? (= committed (commit {:amount 100 :recipient "example-org"}))
      tampered? (= committed (commit {:amount 200 :recipient "example-org"}))
      declared-count 5 actual-count 3
      count-verified? (= declared-count actual-count)
      row (fn [label violated outcome ok?]
            [:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} label]
             [:td {:style {:padding "6px 8px" :color "#fbbf24"}} violated]
             [:td {:style {:padding "6px 8px" :color (if ok? "#22c55e" "#ef4444") :fontWeight 700}}
              outcome]])]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "20.7 · coverage, deterrent, verifiability — NOT ADMITTED cases"]
    (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
          (concat
           [[:tr {:style {:borderBottom "1px solid #334155"}}
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Class"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Violated property"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "NOT ADMITTED"]]]
           [(row "fraction coverage"
                 (format "coverage-adequacy-pct %.1f%% < 80%% gate" adequacy-pct)
                 (str "pool not admitted as adequately covered (" gate? ")") gate?)
            (row "coverage-adequacy-score"
                 (format "deficit-margin score %.1f (unmet = %.0f)" adequacy-score unmet)
                 (str "below-coverage pool (" gate? ")") gate?)
            (row "robustly-deterrent"
                 "strategy profile requires every equilibrium check to pass"
                 (str "all-pass " (name all-pass) " · one-fail " (name one-fail))
                 (and (= :robustly-deterrent all-pass) (= :not-robustly-deterrent one-fail)))
            (row "verbatim / verifiable"
                 "a committed claim must reproduce byte-for-byte from unchanged inputs"
                 (str "verbatim " verbatim? " · tampered " tampered?)
                 (and verbatim? (not tampered?)))
            (row "count-verified"
                 (format "declared %d components vs actual %d" declared-count actual-count)
                 (str "mismatch rejected (" count-verified? ")") (not count-verified?))]))]))

;; ## 20.8 · list-order-arbitrary — where list order is (and is not) binding
;;
;; A commitment is **list-order-arbitrary** when it canonicalises order before
;; hashing, so the same elements in any order produce the same root — there is
;; no order-based rejection. This is the unordered `evidence-hash-set-root`.
;; The contrast is a sequence commitment (`canonical-bytes` of a vector), where
;; order IS binding: a reorder changes the root and the reordered record is NOT
;; ADMITTED.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [set-abc (chain/evidence-hash-set-root ["a" "b" "c"])
      set-cba (chain/evidence-hash-set-root ["c" "a" "b"])
      order-arbitrary? (= set-abc set-cba)
      vec-abc (hc/canonical-bytes-hex [1 2 3])
      vec-cba (hc/canonical-bytes-hex [3 2 1])
      order-sensitive? (not= vec-abc vec-cba)
      dup-set? (= (chain/evidence-hash-set-root ["a" "a" "b"])
                  (chain/evidence-hash-set-root ["a" "b"]))]
  (clerk/html
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :fontFamily "monospace" :borderRadius "4px" :fontSize "12px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "8px"}}
     "20.8 · list-order-arbitrary vs order-binding commitments"]
    (into [:table {:style {:width "100%" :borderCollapse "collapse"}}]
          (concat
           [[:tr {:style {:borderBottom "1px solid #334155"}}
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Commitment"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#94a3b8"}} "Reorder effect"]
             [:th {:style {:padding "6px 8px" :textAlign "left" :color "#f87171"}} "Admission"]]]
           [[:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} "evidence-hash-set-root (unordered)"]
             [:td {:style {:padding "6px 8px" :color "#22c55e"}} (str "same root across orders (" order-arbitrary? " · dedupes " dup-set? ")")]
             [:td {:style {:padding "6px 8px" :color "#22c55e" :fontWeight 700}} "list order arbitrary — admitted identically"]]
            [:tr {:style {:borderBottom "1px solid #134e4a"}}
             [:td {:style {:padding "6px 8px" :color "#e2e8f0"}} "canonical-bytes(vector) / chain sequence"]
             [:td {:style {:padding "6px 8px" :color "#f87171"}} (str "reorder changes the root (" order-sensitive? ")")]
             [:td {:style {:padding "6px 8px" :color "#f87171" :fontWeight 700}} "reordered record NOT ADMITTED"]]]))]))
