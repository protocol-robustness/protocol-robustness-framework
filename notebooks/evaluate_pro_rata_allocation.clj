^{:nextjournal.clerk/visibility {:code :show :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.evaluate-pro-rata-allocation
  "Pro-Rata Evaluation API — self-contained allocation with content-addressed verification.
   No Sew protocol dependency. Pure evaluation pipeline."
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]))

;; # Pro-Rata Evaluation API
;; ## Content-Addressed Allocation with Deterministic Replay

;; The `evaluate-pro-rata-allocation` function is a self-contained evaluation
;; pipeline: it normalizes a canonical request, builds a projection, allocates,
;; replays for determinism, runs validation checks, and returns a
;; content-addressed result package.

;; ## 1. Canonical Evaluation Request

;; A canonical request uses `:participants` with `:weight` and `:cap`, never
;; protocol-specific accessors. The `:policy` selects rounding, tie-breaking,
;; and cap-redistribution behavior.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def request
  {:allocation/id :demo
   :use-case :slashing
   :unit :USDC
   :amount 1000
   :participants [{:id "Alice" :weight 600 :cap 400}
                  {:id "Bob"   :weight 300 :cap 300}
                  {:id "Cara"  :weight 100 :cap 100}]
   :policy {:rounding :floor-with-largest-remainder
            :tie-break :input-order
            :algorithm :weighted-pro-rata
            :cap-treatment :redistribute}
   :source {:type :demo}})

;; ## 2. Evaluate

;; `evaluate-pro-rata-allocation` normalizes the request once, then allocates
;; and replays from the same normalized data. The result package includes the
;; canonical request, projection hash, full allocation witness, and validation
;; results — all committed under a domain-hash.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def evaluation
  (payoffs/evaluate-pro-rata-allocation request))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "20px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:font-size "11px" :color "#7ADDDC" :text-transform "uppercase" :font-weight 700 :margin-bottom "12px"}}
   "Evaluation Result Package"]
  [:div "Result hash: " [:strong {:style {:color "#FF9800"}} (get-in evaluation [:result :artifact/hash])]]
  [:div "Result type: " (pr-str (get-in evaluation [:result :artifact/type]))]
  [:div "Projection:  " (get-in evaluation [:projection :artifact/hash])]
  [:div "Allocation ID: " (pr-str (:allocation/id evaluation))]
  [:div {:style {:margin-top "12px" :border-top "1px solid #134e4a" :padding-top "8px"}}
   [:div "Validation:   " [:strong {:style {:color (if (= :passed (get-in evaluation [:validation :status])) "#22c55e" "#ef4444")}} (name (get-in evaluation [:validation :status]))]]
   [:div "Checks:       " (str (get-in evaluation [:validation :evaluated-check-count]) " evaluated")]
   [:div "Coverage:     " (name (get-in evaluation [:validation :coverage-status]))]]])

;; ## 3. Verify the Content-Addressed Hash

;; The result hash commits to the full evaluation outcome. `validate-pro-rata-evaluation-package!`
;; recomputes the domain hash and throws on mismatch — demonstrating the integrity guarantee.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def verified-evaluation
  (payoffs/validate-pro-rata-evaluation-package! evaluation))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#064e3b" :border "1px solid #22c55e" :color "#e2e8f0"
                :padding "12px" :font-family "monospace" :border-radius "4px" :font-size "13px"}}
  [:span {:style {:color "#22c55e"}} "✓ "]
  "Hash verification passed — result package is intact"])

;; Tampering with any field in the result value produces a different hash.
;; The validation function detects this immediately:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def tampered-hash-check
  (try
    (let [tampered-value (assoc-in evaluation [:result :artifact/value :amount] 999)
          tampered (assoc-in evaluation [:result :artifact/value] tampered-value)]
      (payoffs/validate-pro-rata-evaluation-package! tampered)
      :unexpected-pass)
    (catch Exception e
      :hash-mismatch-detected)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if (= :hash-mismatch-detected tampered-hash-check) "#450a0a" "#064e3b")
                :border (str "1px solid " (if (= :hash-mismatch-detected tampered-hash-check) "#ef4444" "#22c55e"))
                :color "#e2e8f0" :padding "12px" :font-family "monospace" :border-radius "4px" :font-size "13px"}}
  (if (= :hash-mismatch-detected tampered-hash-check)
    [:span "⛔ Hash mismatch detected — tampered result correctly rejected"]
    [:span "⚠ Unexpected — tampered result was accepted"])])

;; ## 4. Build an Artifact from the Evaluation

;; The evaluation result plugs directly into `build-pro-rata-allocation-result-artifact`
;; via the `:evaluation` key. The builder verifies the content-addressed hash and
;; cross-checks the projection hash against the evaluation's projection.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def artifact
  (payoffs/build-pro-rata-allocation-result-artifact
   {:projection-artifact (get-in evaluation [:projection :artifact/value])
    :evaluation evaluation
    :allocation-result (:allocation evaluation)
    :world-before-hash (hc/hash-with-intent {:hash/intent :world-structure} {:step 0})
    :world-after-hash (hc/hash-with-intent {:hash/intent :world-structure} {:step 1})
    :action-hash (hc/hash-with-intent {:hash/intent :action} {:action/type :demo})
    :action-hash-at (hc/hash-with-intent {:hash/intent :action-at} {:action-hash "dummy" :step 0 :block-time 0})}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "20px"
                :font-family "monospace" :border-radius "4px"}}
  [:div {:style {:font-size "11px" :color "#7ADDDC" :text-transform "uppercase" :font-weight 700 :margin-bottom "12px"}}
   "Result Artifact"]
  [:div "Artifact hash: " [:strong {:style {:color "#7ADDDC"}} (:allocation-result-hash artifact)]]
  [:div "Artifact ID:   " (:allocation-result-id artifact)]
  [:div "Eval hash:     " (get-in artifact [:evaluation-result-hash])]
  [:div {:style {:margin-top "8px"}}
   "The artifact embeds the evaluation's content-addressed hash under "
   "`:evaluation-result-hash`, linking the ex-post artifact to the "
   "independently verifiable evaluation outcome."]])

;; ## 5. Allocation Table

;; The allocation result is available directly from the evaluation, identical
;; to what the artifact embeds:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Party" "Weight" "Cap" "Paid" "Unmet"]
  :rows (mapv (fn [a]
                [(str (:id a))
                 (str (:basis-amount a))
                 (str (:cap a))
                 (str (:paid a))
                 (str (:unmet a))])
              (:allocations (:allocation evaluation)))})

;; ## 6. Validation Checks Detail

;; Each check reports a status of `:passed`, `:failed`, or `:not-evaluated`:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [checks (get-in evaluation [:validation :checks])
       evaluated (filter #(not= :not-evaluated (:status %)) checks)]
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                  :font-family "monospace" :border-radius "4px"}}
    [:div {:style {:font-size "11px" :color "#7ADDDC" :text-transform "uppercase" :font-weight 700 :margin-bottom "8px"}}
     "Validation checks"]
    (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"}}]
          (mapv (fn [c]
                  [:tr {:key (name (:check c)) :style {:border-bottom "1px solid #134e4a"}}
                   [:td {:style {:padding "6px 8px" :color "#94a3b8"}} (name (:check c))]
                   [:td {:style {:padding "6px 8px" :color (if (= :passed (:status c)) "#22c55e" "#ef4444")}} (name (:status c))]])
               evaluated))]))

