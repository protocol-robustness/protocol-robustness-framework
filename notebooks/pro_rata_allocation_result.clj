(ns notebooks.pro-rata-allocation-result
  {:nextjournal.clerk/toc true
   :nextjournal.clerk/visibility {:code :fold :result :show}
   :nextjournal.clerk/dark-mode true}
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.protocols.sew.evidence.slashing :as slashing]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]))

;; # Pro-Rata Allocation Result Artifact
;; ## Demo: live artifact trail from slashing through verification

;; This notebook demonstrates how the framework produces a canonical
;; **pro-rata allocation result artifact** from a slashing event and links
;; it into an evidence record with full forensic provenance.

;; ### Demo narrative
;;
;; 1. A slashing event creates a shortfall allocation problem.
;; 2. The framework derives a pro-rata allocation frame from the world state.
;; 3. That frame is hashed as a canonical projection artifact.
;; 4. The actual allocation result is computed and hashed as a canonical
;;    pro-rata allocation result artifact.
;; 5. The evidence record links the action, world-before hash, world-after hash,
;;    projection frame hash, and allocation result hash.
;; 6. The result table shows who received what allocation and what remained unmet.
;; 7. The proof panel shows the hashes needed to verify the claim.
;;
;; Additional sections demonstrate:
;; - The mechanism evidence envelope with validation results and
;;   independent reconstruction checks (section 3b).
;; - The content-addressed evaluation API that produces a verified
;;   result package before artifact construction (section 5b).

;; ## 1. Setup: a slashing event creates a shortfall allocation problem

;; Two resolvers each stake 1000 in the protocol. A 300-unit slash obligation
;; must be recovered from their stake.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def world
  (-> (types/empty-world 1000)
      (assoc-in [:resolver-stakes "0xAlice"] 1000)
      (assoc-in [:resolver-stakes "0xBob"] 1000)
      (assoc-in [:resolver-epoch-slashed "0xAlice"] {:amount 0 :epoch-start 0})
      (assoc-in [:resolver-epoch-slashed "0xBob"] {:amount 0 :epoch-start 0})))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Resolver" "Stake" "slashable-stake" "available-slashable"]
  :rows [["0xAlice" "1000" "1000" "1000"]
         ["0xBob" "1000" "1000" "1000"]]})

;; The allocation input defines the liable parties, their slashable stake
;; (weight), and their available-slashable (cap).

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def allocation-input
  {:slash-obligation 300
   :liable-parties [{:id "0xAlice" :slashable-stake 1000 :available-slashable 1000}
                    {:id "0xBob" :slashable-stake 1000 :available-slashable 1000}]})

;; ## 2. The projection artifact records the allocation basis

;; The framework derives a pro-rata allocation frame from the world state.
;; This is the *ex-ante* projection — it records what the allocation *would*
;; look like under the current basis, before the protocol executes.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def projection-artifact
  (sew-econ/build-sew-slash-projection-artifact allocation-input))

(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Projection hash: " [:strong {:style {:color "#7ADDDC"}} (:projection-hash projection-artifact)]]
  [:div "Projection def:  " (:projection-definition-id projection-artifact)]
  [:div "Intent:         " (pr-str (:intent projection-artifact))]])

;; The projection artifact is canonical-safe: its identity hash commits to
;; the allocation frame, intent, and projection definition — but not to any
;; runtime-only fields or its own hash.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Field" "Value"]
  :rows [[":projection-hash" (:projection-hash projection-artifact)]
         [":projection-definition-id" (pr-str (:projection-definition-id projection-artifact))]
         [":projection-type" (pr-str (:projection-type projection-artifact))]
         [":total-obligation" (str (get-in projection-artifact [:projection :total-obligation]))]
         [":eligible-count" (str (get-in projection-artifact [:summary :eligible-count]))]
         [":participant-count" (str (get-in projection-artifact [:summary :participant-count]))]]})

;; ## 3. The actual allocation is computed

;; The framework allocates pro-rata across liable parties. Each resolver
;; receives a proportion of the obligation based on their stake weight,
;; subject to any cap (available-slashable). We compute the allocation from
;; the same projection used by the claims evaluator so the direct result
;; matches the projection — all claims pass.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def allocation-result
  (sew-econ/calculate-sew-slash-allocation-from-projection projection-artifact))

(clerk/table
 {:head ["Resolver" "Basis" "Share" "Owed" "Paid" "Unmet"]
  :rows (mapv (fn [a]
                [(str (:id a))
                 (str (:basis-amount a))
                 (str (:share a))
                 (str (:owed a))
                 (str (:paid a))
                 (str (:unmet a))])
              (:allocations allocation-result))})

;; Total requested: 300, allocated: 300, unmet: 0, remainder: 0 (the stake
;; is sufficient to cover the obligation).

;; ## 3b. Mechanism evidence envelope
;;
;; The allocation also produces a hash-committed mechanism evidence envelope
;; with witness rounds, validation results (cap-respecting, quota-bounded,
;; round-trace-coherent, residual-valid, canonical-remainder-assignment),
;; and a compact evidence reference for cross-linking.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def direct-allocation
  (sew-econ/calculate-sew-slash-allocation allocation-input))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Evidence ref hash: " [:strong {:style {:color "#7ADDDC"}} (get-in direct-allocation [:mechanism/evidence-reference :evidence/hash])]]
  [:div "Mechanism:         " (pr-str (get-in direct-allocation [:mechanism/evidence-reference :mechanism]))]
  [:div "Allocation ID:     " (pr-str (get-in direct-allocation [:mechanism/evidence-reference :allocation/id]))]])

;; The mechanism evidence envelope commits the full allocation witness.
;; `evidence-violations` independently reconstructs the allocation from
;; the canonical request and compares the full semantic witness.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
  (let [me (:mechanism/evidence direct-allocation)
        vr (:mechanism/validation-results me)
        all-pass? (every? :holds? vr)]
    [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                   :font-family "monospace" :border-radius "4px" :margin-top "12px"}}
     [:div "Validation: " [:strong {:style {:color (if all-pass? "#22c55e" "#ef4444")}} (if all-pass? "ALL PASSED" "FAILURES DETECTED")]]
     (into [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px" :margin-top "8px"}}]
           (mapv (fn [r]
                   [:tr {:style {:border-bottom "1px solid #134e4a"}}
                    [:td {:style {:color "#94a3b8" :padding "4px 8px"}} (name (:claim/id r))]
                    [:td {:style {:color (if (:holds? r) "#22c55e" "#ef4444") :padding "4px 8px"}} (if (:holds? r) "PASSED" "FAILED")]])
                vr))]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [violations (pro-rata-evidence/evidence-violations (:mechanism/evidence direct-allocation))]
   [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "12px"
                  :font-family "monospace" :font-size "13px" :border-radius "4px" :margin-top "8px"}}
    [:div "Reconstruction check: " [:strong {:style {:color (if (seq violations) "#ef4444" "#22c55e")}} (if (seq violations) "VIOLATIONS" "CLEAN")]]
    (when (seq violations)
      [:div {:style {:margin-top "8px" :color "#fbbf24"}} (pr-str violations)])]))

;; ## 4. The allocation result artifact records what was actually allocated

;; This is the *ex-post* outcome artifact. It captures the actual allocation
;; and links it to the projection frame, world state hashes, and the action.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def attribution
  {:ctx/scenario-id "pro-rata-demo"
   :ctx/run-id "demo-001"
   :ctx/event-index 1
   :ctx/event-type :slash/execute})

(def world-before-hash
  (hc/hash-with-intent {:hash/intent :world-structure} world))

(def world-after-hash
  (hc/hash-with-intent {:hash/intent :world-structure}
                       (assoc world :step 1)))

(def action-map
  {:action/type :slash/execute
   :slash-id "demo-slash-1"
   :resolver "0xAlice"
   :amount 300
   :reason :fraud-slash})

(def action-hash
  (hc/hash-with-intent {:hash/intent :action} action-map))

(def action-hash-at
  (hc/hash-with-intent {:hash/intent :action-at}
                       {:action-hash action-hash
                        :step 1
                        :block-time 1000}))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def result-artifact
  (payoffs/build-pro-rata-allocation-result-artifact
   {:projection-artifact projection-artifact
    :allocation-result allocation-result
    :world-before-hash world-before-hash
    :world-after-hash world-after-hash
    :action-hash action-hash
    :action-hash-at action-hash-at
    :allocation-input allocation-input
    :attribution attribution
    :claims []
    :invariant-links []}))

(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Allocation result hash: " [:strong {:style {:color "#FF9800"}} (:allocation-result-hash result-artifact)]]
  [:div "Allocation result ID:   " (:allocation-result-id result-artifact)]
  [:div "Artifact kind:          " (pr-str (:artifact-kind result-artifact))]])

;; The allocation result hash is a self-hash: it commits to the full artifact
;; content (provenance, allocation, claims, invariant links) but excludes its
;; own hash field. Adding a shortfall-outcome or changing the allocation would
;; produce a different hash.

;; ## 5. The evidence record links everything together

;; The evidence record wraps the allocation result artifact into the protocol's
;; evidence chain. It links the action, world-before, world-after, projection
;; frame, allocation result, and claim evaluations into a single hash-addressed
;; envelope.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def evidence-result
  (slashing/build-prorata-slash-evidence
   {:world world
    :slash-id "demo-slash-1"
    :workflow-id 0
    :epoch 0
    :trigger :fraud-slash
    :allocation-input allocation-input
    :projection-artifact projection-artifact
    :allocation-result allocation-result
    :world-before-hash world-before-hash
    :action-hash action-hash
    :action-hash-at action-hash-at
    :transition-dependencies []
    :attribution attribution}))

;; No claims were submitted in this demo, so claim-count is 0 and holds? is
;; vacuously true. A real workflow would attach slashing claims to the evidence.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def evidence (:evidence evidence-result))
(def embedded-artifact (:artifact evidence-result))

(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Evidence hash:          " [:strong {:style {:color "#7ADDDC"}} (:evidence/hash evidence)]]
  [:div "Evidence type:          " (pr-str (:evidence/type evidence))]
  [:div "Projection hash:        " (get-in evidence [:result :projection :projection-hash])]
  [:div "Allocation result hash: " (get-in evidence [:result :pro-rata :allocation-result-hash])]
  [:div "Claim count:            " (str (get-in evidence [:result :pro-rata :summary :claim-count]))]
  [:div "All claims hold?        " (str (get-in evidence [:result :pro-rata :summary :holds?]))]])

;; The evidence record's `:allocation-result-hash` (under `:pro-rata`) is a
;; **reference hash** — it commits the evidence to a specific allocation result
;; artifact. Unlike self-hashes, reference hashes are part of the canonical
;; content and affect the evidence's identity.

;; ## 5b. Evaluation path — content-addressed evaluation API
;;
;; The `evaluate-pro-rata-allocation` function provides a self-contained
;; evaluation pipeline: it normalizes the request, builds the projection,
;; allocates, replays for determinism, runs 4 validation checks
;; (conservation, bounds, completeness, deterministic-replay), and returns
;; a content-addressed result package whose hash commits to the full
;; evaluation outcome.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def evaluation-request
  {:allocation/id :demo-evaluation
   :use-case :slashing
   :unit :USDC
   :amount 300
   :participants [{:id "0xAlice" :weight 1000 :cap 1000}
                  {:id "0xBob" :weight 1000 :cap 1000}]
   :policy {:rounding :floor-with-largest-remainder
            :tie-break :input-order
            :algorithm :weighted-pro-rata
            :cap-treatment :redistribute}
   :source {:type :slashing-demo}})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def evaluation
  (payoffs/evaluate-pro-rata-allocation evaluation-request))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Evaluation hash: " [:strong {:style {:color "#FF9800"}} (get-in evaluation [:result :artifact/hash])]]
  [:div "Type:            " (pr-str (get-in evaluation [:result :artifact/type]))]
  [:div "Validation:      " [:strong {:style {:color (if (= :passed (get-in evaluation [:validation :status])) "#22c55e" "#ef4444")}} (name (get-in evaluation [:validation :status]))]]
  [:div "Checks passed:   " (str (get-in evaluation [:validation :evaluated-check-count]))]])

;; The evaluation result can be piped directly into
;; `build-pro-rata-allocation-result-artifact` via the `:evaluation` key.
;; The builder verifies the content-addressed hash and cross-checks the
;; projection artifact hash against the evaluation's projection.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def result-artifact-via-evaluation
  (payoffs/build-pro-rata-allocation-result-artifact
   {:projection-artifact (get-in evaluation [:projection :artifact/value])
    :evaluation evaluation
    :allocation-result (:allocation evaluation)
    :world-before-hash world-before-hash
    :world-after-hash world-after-hash
    :action-hash action-hash
    :action-hash-at action-hash-at
    :allocation-input allocation-input
    :attribution attribution}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :border-radius "4px"}}
  [:div "Artifact hash: " [:strong {:style {:color "#7ADDDC"}} (:allocation-result-hash result-artifact-via-evaluation)]]
  [:div "Artifact ID:   " (:allocation-result-id result-artifact-via-evaluation)]
  [:div "Eval hash:     " (get-in result-artifact-via-evaluation [:evaluation-result-hash])]])

;; The artifact produced via the evaluation path is identical to the direct
;; path — the evaluation step simply adds validated provenance before the
;; artifact build.

;; ## 6. Result table — who received what

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(clerk/html
 [:pre {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :font-size "13px" :line-height "1.5"
                :border-radius "4px"}}
  (payoffs/format-pro-rata-result-table result-artifact)])

;; ## 7. Proof panel — hashes needed to verify the claim

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(clerk/html
 [:pre {:style {:background "#0f172a" :color "#e2e8f0" :padding "16px"
                :font-family "monospace" :font-size "13px" :line-height "1.5"
                :border-radius "4px"}}
  (payoffs/format-proof-panel result-artifact)])

;; ## Hash chain summary

;; The following hashes form a verifiable chain from the protocol action
;; through to the allocation result:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Artifact" "Hash"]
  :rows [["World before" world-before-hash]
         ["Action" action-hash]
         ["Action-at" action-hash-at]
         ["Projection artifact" (:projection-hash projection-artifact)]
         ["Allocation result artifact" (:allocation-result-hash result-artifact)]
         ["Evidence record" (:evidence/hash evidence)]
         ["World after" world-after-hash]]})

;; ## Verifying the chain

;; To verify a claim from the evidence:
;;
;; 1. Read the evidence record → extract `:allocation-result-hash`
;; 2. Locate the allocation result artifact with that hash
;; 3. Verify the artifact's self-hash matches its content
;; 4. Read the projection frame hash from the artifact → locate the projection
;; 5. Verify all world hashes link to known world states
;; 6. Check that claim results satisfy the allocation invariants
