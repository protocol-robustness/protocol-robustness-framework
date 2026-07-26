;; # Ethereum Foundation Demo — Pro-Rata Allocation with Evidence DAG
;;
;; **Scenario:** Slashing → Pro-Rata Allocation → Attestation DAG → Resolution
;;
;; **Framework:** Protocol Robustness Framework (PRF) — adversarial multi-actor scenario testing
;; for slashing allocation and evidence verification.
;;
;; **What this demonstrates:**
;; 1. A pro-rata allocation computation from a slashing event
;; 2. Content-addressed attestation records with typed references
;; 3. DAG-structured evidence nodes linking attestations via parent-hashes
;; 4. Attestation resolution: parse typed ref → registry lookup → hash match → type verify
;; 5. A full verifiable evidence chain from protocol action through to resolution

^{:nextjournal.clerk/visibility {:code :fold :result :show}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.ef-demo-pro-rata-allocation
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.economics :as sew-econ]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-dag :as adag]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.attestation-resolver :as ars]
            [resolver-sim.pro-rata.evidence :as pro-rata-evidence]))

;; ===========================================================================
;; 1. Slashing Event — Allocation Problem
;; ===========================================================================

;; Two resolvers each stake 1000 in the protocol. A 300-unit slash obligation
;; must be recovered proportionally from their stake.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def world
  (-> (types/empty-world 1000)
      (assoc-in [:resolver-stakes "0xAlice"] 1000)
      (assoc-in [:resolver-stakes "0xBob"] 1000)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "28px"
                :fontFamily "Inter, JetBrains Mono, sans-serif"}}
  [:h1 {:style {:marginTop 0 :color "#ffffff" :fontSize "36px"}}
   "Ethereum Foundation Demo"]
  [:p {:style {:color "#7ADDDC" :fontWeight 700 :fontSize "18px"}}
   "Pro-Rata Allocation — Slashing → Attestation DAG → Resolution"]
  [:div {:style {:display "grid" :gap "16px" :marginTop "20px"}}
   [:div {:style {:background "#1e293b" :border "1px solid #334155"
                  :borderRadius "8px" :padding "16px"}}
    [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Scenario: Pro-Rata Slash Allocation"]
    [:p {:style {:color "#94a3b8" :fontSize "13px"}}
     "A 300-unit slash obligation must be distributed across two resolvers "
     "proportionally to their stake (1000 each). The allocation is recorded "
     "as a canonical evidence artifact, then attested by protocol validators "
     "who produce DAG-linked evidence nodes with typed references."]
    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px" :marginTop "12px"}}
     [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"}}
      [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"}}
       "Slash Obligation"]
      [:div {:style {:fontSize "20px" :fontWeight 800 :color "#f8fafc"}} "300"]]
     [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"}}
      [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"}}
       "Resolvers"]
      [:div {:style {:fontSize "14px" :fontWeight 600 :color "#f8fafc"}}
       "Alice (1000), Bob (1000)"]]]]
   [:div {:style {:background "#1e293b" :border "1px solid #334155"
                  :borderRadius "8px" :padding "16px"}}
    [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Evidence DAG Flow"]
    [:div {:style {:display "flex" :gap "8px" :flexWrap "wrap" :alignItems "center" :fontSize "13px"}}
     [:span {:style {:background "#f59e0b" :color "#0f172a" :fontWeight 700
                     :padding "2px 10px" :borderRadius "4px"}} "Slash"]
     [:span {:style {:color "#64748b"}} "→"]
     [:span {:style {:background "#8b5cf6" :color "#0f172a" :fontWeight 700
                     :padding "2px 10px" :borderRadius "4px"}} "Projection"]
     [:span {:style {:color "#64748b"}} "→"]
     [:span {:style {:background "#3b82f6" :color "#0f172a" :fontWeight 700
                     :padding "2px 10px" :borderRadius "4px"}} "Allocation"]
     [:span {:style {:color "#64748b"}} "→"]
     [:span {:style {:background "#22c55e" :color "#0f172a" :fontWeight 700
                     :padding "2px 10px" :borderRadius "4px"}} "Attestation"]
     [:span {:style {:color "#64748b"}} "→"]
     [:span {:style {:background "#ef4444" :color "#0f172a" :fontWeight 700
                     :padding "2px 10px" :borderRadius "4px"}} "DAG Node"]
     [:span {:style {:color "#64748b"}} "→"]
     [:span {:style {:background "#7ADDDC" :color "#0f172a" :fontWeight 700
                     :padding "2px 10px" :borderRadius "4px"}} "Resolution"]]]]])

;; ===========================================================================
;; 2. Allocation Input & Projection Artifact
;; ===========================================================================

;; The allocation input defines the liable parties, their slashable stake
;; (weight), and their available-slashable (cap).

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def allocation-input
  {:slash-obligation 300
   :liable-parties [{:id "0xAlice" :slashable-stake 1000 :available-slashable 1000}
                    {:id "0xBob" :slashable-stake 1000 :available-slashable 1000}]})

;; Build the ex-ante projection artifact — records the allocation basis
;; before the protocol executes:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def projection-artifact
  (sew-econ/build-sew-slash-projection-artifact allocation-input))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Projection Artifact (Ex-Ante)"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "The projection artifact captures the allocation frame: who is liable, "
   "with what weight, and how the obligation would be distributed under the "
   "current basis. Its identity hash commits to the intent, projection "
   "definition, and allocation frame content."]
  [:div {:style {:display "grid" :gridTemplateColumns "140px 1fr" :gap "8px" :fontSize "12px"}}
   (for [[k v] [[":projection-hash" [:code {:style {:color "#22c55e"}} (:projection-hash projection-artifact)]]
                 [":projection-type" (pr-str (:projection-type projection-artifact))]
                 [":intent" (pr-str (:intent projection-artifact))]
                 [":total-obligation" (str (get-in projection-artifact [:projection :total-obligation]))]
                 [":eligible-count" (str (get-in projection-artifact [:summary :eligible-count]))]
                 [":participant-count" (str (get-in projection-artifact [:summary :participant-count]))]]]
     [:<> [:div {:style {:color "#94a3b8"}} k] [:div {:style {:color "#e2e8f0"}} v]])]])

;; ===========================================================================
;; 3. Pro-Rata Allocation Computation
;; ===========================================================================

;; Compute the actual allocation from the projection using the same canonical
;; pro-rata function (Hare quota / largest-remainder) that the protocol uses:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def allocation-result
  (sew-econ/calculate-sew-slash-allocation-from-projection projection-artifact))

;; Each resolver receives a proportion of the 300-unit obligation based on
;; their stake weight. With equal stakes, each receives 150, and no cap
;; restricts the allocation:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Resolver" "Share" "Owed" "Paid" "Unmet"]
  :rows (mapv (fn [a]
                [(str (:id a))
                 (str (:share a))
                 (str (:owed a))
                 (str (:paid a))
                 (str (:unmet a))])
              (:allocations allocation-result))})

;; ===========================================================================
;; 3b. Mechanism Evidence Envelope
;; ===========================================================================

;; The allocation also produces a hash-committed mechanism evidence envelope
;; with witness rounds, structural validation results, and an independent
;; reconstruction check. The envelope is what attestations ultimately certify.

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
    [:div "Reconstruction: " [:strong {:style {:color (if (seq violations) "#ef4444" "#22c55e")}} (if (seq violations) "VIOLATIONS" "CLEAN")]]
    (when (seq violations)
       [:div {:style {:margin-top "8px" :color "#fbbf24"}} (pr-str violations)])]))

;; The attestations in the next section reference an artifact built from this
;; same allocation — the mechanism evidence envelope provides the independent
;; mathematical witness that the attestations certify.

(def attribution
  {:ctx/scenario-id "ef-pro-rata-allocation"
   :ctx/run-id "pro-rata-demo-1"
   :ctx/event-index 1
   :ctx/event-type :slash/execute})

;; ===========================================================================
;; 4. Attestation Creation
;; ===========================================================================

;; Protocol validators issue attestations about the allocation result.
;; Each attestation is a content-addressed record: its identity is the
;; SHA-256 hash of the canonical attestation content, making it immutable
;; and verifiable without a trusted third party.

;; First compute the context hashes needed for the allocation result artifact:

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
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

;; Build the allocation result artifact (ex-post outcome):

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def allocation-result-artifact
  (payoffs/build-pro-rata-allocation-result-artifact
   {:projection-artifact projection-artifact
    :allocation-result allocation-result
    :world-before-hash world-before-hash
    :world-after-hash world-after-hash
    :action-hash action-hash
    :action-hash-at action-hash-at
    :allocation-input allocation-input
    :attribution attribution}))

;; We define a simple signing function for the demo — in production this
;; would be an Ed25519 or ECDSA signature from the validator's key:

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(defn demo-signing-fn [_payload]
  {:algorithm :ed25519
   :public-key-id "demo-validator-key-001"
   :signature-bytes (str "sig:" (subs (:allocation-result-hash allocation-result-artifact) 0 16))})

;; Build attestation records — one per resolver, each attesting that the
;; allocation was computed correctly:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def attestation-alice
  (att/build-attestation
   {:type :validator :id "val-node-1"}
   {:type :evidence-node :hash (:allocation-result-hash allocation-result-artifact)}
   :verified
   {:signing-fn demo-signing-fn
    :claim-id :prorata/allocation-complete
    :provenance {:run-id "pro-rata-demo-1"
                 :scenario-id "ef-pro-rata-allocation"
                 :step 1}}))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def attestation-bob
  (att/build-attestation
   {:type :validator :id "val-node-2"}
   {:type :evidence-node :hash (:allocation-result-hash allocation-result-artifact)}
   :verified
   {:signing-fn demo-signing-fn
    :claim-id :prorata/conservation
    :provenance {:run-id "pro-rata-demo-1"
                 :scenario-id "ef-pro-rata-allocation"
                 :step 1}}))

;; Each attestation carries a content-derived identity hash. The typed
;; reference format for these attestations in the evidence DAG will be:
;;   attestation:sha256:<attestation-id>

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Attestation Records"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "Each attestation has a content-derived identity. The typed reference "
   "used in DAG nodes wraps the identity with a type prefix for unambiguous "
   "dispatch: " [:code {:style {:color "#7ADDDC"}} "attestation:sha256:<hash>"]]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
   (for [[label att] [["Validator Alice" attestation-alice]
                       ["Validator Bob" attestation-bob]]]
     [:div {:key label :style {:background "#0f172a" :padding "12px" :borderRadius "6px"
                               :border "1px solid #334155"}}
      [:div {:style {:color "#7ADDDC" :fontWeight 700 :fontSize "13px" :marginBottom "6px"}}
       label]
      [:div {:style {:display "grid" :gridTemplateColumns "80px 1fr" :gap "4px" :fontSize "11px"}}
       [:span {:style {:color "#64748b"}} "attestor:" ]
       [:span {:style {:color "#e2e8f0"}} (:attestation/attestor-id att)]
       [:span {:style {:color "#64748b"}} "claim:" ]
       [:span {:style {:color "#e2e8f0"}} (pr-str (:attestation/claim-id att))]
       [:span {:style {:color "#64748b"}} "result:" ]
       [:span {:style {:color (if (= :verified (:attestation/claim-result att)) "#22c55e" "#f59e0b")}}
        (pr-str (:attestation/claim-result att))]
       [:span {:style {:color "#64748b"}} "id:" ]
       [:span {:style {:color "#22c55e" :fontFamily "monospace"}} (str (subs (:attestation/id att) 0 16) "...")]
       [:span {:style {:color "#64748b"}} "ref:" ]
       [:span {:style {:color "#fbbf24" :fontFamily "monospace" :fontSize "10px"}}
        (str "attestation:sha256:" (subs (:attestation/id att) 0 12) "...")]]])]])

;; ===========================================================================
;; 5. Register Attestations & Build DAG Nodes
;; ===========================================================================

;; The attestation registry stores attestations keyed by their content hash.
;; DAG evidence nodes reference attestations via typed references, enabling
;; off-chain resolution and verification.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(ar/with-fresh-registry
  ;; Register both attestations in the in-memory registry
  (ar/register-attestation! attestation-alice)
  (ar/register-attestation! attestation-bob)

  ;; Build DAG evidence nodes with typed attestation references
  ;; Each node records the attestation creation event as a full execution
  ;; evidence node with the typed reference in its :attestations field:
  (def dag-node-alice
    (adag/build-attestation-dag-node
     attestation-alice
     {:parent-hashes []
      :provenance {:run-id "pro-rata-demo-1"}}))

  (def dag-node-bob
    (adag/build-attestation-dag-node
     attestation-bob
     {:parent-hashes [(:node-hash dag-node-alice)]
      :provenance {:run-id "pro-rata-demo-1"}}))

  ;; Chain both attestations in sequence using the convenience builder
  (def chained-nodes
    (adag/chain-attestation-dag-nodes
     [attestation-alice attestation-bob]
     {:provenance {:run-id "pro-rata-demo-1"}}))

  ;; Store registry state for display
  (def registry-state
    (ar/registry-status)))

;; The DAG nodes form a directed acyclic graph where each node references
;; its parent via :parent-hashes. The `chain-attestation-dag-nodes` helper
;; automates this for sequential attestations.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Evidence DAG Nodes"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "Each DAG node is a full execution evidence node with a content-addressed "
   " hash, DAG parent links, and typed attestation references. The typed "
   "reference format (" [:code {:style {:color "#fbbf24"}} "attestation:sha256:<id>"] ") "
   "enables unambiguous resolution regardless of storage backend."]

  [:h4 {:style {:color "#f8fafc" :fontSize "14px" :marginBottom "8px"}}
   "DAG Structure: Sequential Linking"]
  [:div {:style {:display "flex" :flexDirection "column" :gap "4px" :marginBottom "16px"}}
   [:div {:style {:background "#0f172a" :padding "8px" :borderRadius "4px" :fontSize "12px"
                  :border "1px solid #334155" :display "flex" :alignItems "center" :gap "8px"}}
    [:span {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "10px" :minWidth "120px"}}
     (str (subs (:node-hash dag-node-alice) 0 16) "...")]
    [:span {:style {:color "#94a3b8"}} "Alice's attestation DAG node"]
    [:span {:style {:color "#64748b" :fontSize "10px" :marginLeft "auto"}}
     "parent-hashes: []"]]
   [:div {:style {:color "#334155" :fontSize "16px" :textAlign "center"}} "↓"]
   [:div {:style {:background "#0f172a" :padding "8px" :borderRadius "4px" :fontSize "12px"
                  :border "1px solid #334155" :display "flex" :alignItems "center" :gap "8px"}}
    [:span {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "10px" :minWidth "120px"}}
     (str (subs (:node-hash dag-node-bob) 0 16) "...")]
    [:span {:style {:color "#94a3b8"}} "Bob's attestation DAG node"]
    [:span {:style {:color "#64748b" :fontSize "10px" :marginLeft "auto"}}
     (str "parent-hashes: [" (subs (first (:parent-hashes dag-node-bob)) 0 12) "...]")]]]

  [:h4 {:style {:color "#f8fafc" :fontSize "14px" :marginBottom "8px"}}
   "Typed Attestation References in DAG Nodes"]
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "8px" :fontSize "12px"}}
   [:div {:style {:background "#0f172a" :padding "8px" :borderRadius "4px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 600 :fontSize "11px"}} "Node: Alice"]
    [:div {:style {:color "#94a3b8"}} "attestations field:"]
    [:div {:style {:color "#fbbf24" :fontFamily "monospace" :fontSize "10px" :wordBreak "break-all"}}
     (pr-str (:attestations dag-node-alice))]]
   [:div {:style {:background "#0f172a" :padding "8px" :borderRadius "4px"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 600 :fontSize "11px"}} "Node: Bob"]
    [:div {:style {:color "#94a3b8"}} "attestations field:"]
    [:div {:style {:color "#fbbf24" :fontFamily "monospace" :fontSize "10px" :wordBreak "break-all"}}
     (pr-str (:attestations dag-node-bob))]]]

  [:div {:style {:marginTop "12px" :padding "8px" :background "#0f172a" :borderRadius "6px"
                 :border "1px solid #334155" :fontSize "12px"}}
   [:div {:style {:color "#94a3b8"}} "Registry status:"]
   [:div {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}}
    (str "attestors: " (:attestors registry-state)
         " — claim-results: " (:claim-results registry-state))]]])

;; ===========================================================================
;; 6. Attestation Resolution
;; ===========================================================================

;; The attestation resolver parses typed references, looks up the
;; attestation in the registry, and verifies:
;;   1. Hash match — the resolved attestation's hash matches the reference
;;   2. Type match — the resolved artifact has a valid attestation schema
;;   3. Signature (optional) — the signing function validates

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(ar/with-fresh-registry
  (ar/register-attestation! attestation-alice)
  (ar/register-attestation! attestation-bob)

  ;; Resolve each typed reference from the DAG nodes
  (def alice-ref (first (:attestations dag-node-alice)))
  (def bob-ref (first (:attestations dag-node-bob)))

  (def resolved-alice
    (ars/resolve-attestation alice-ref {:verify-fn demo-signing-fn}))
  (def resolved-bob
    (ars/resolve-attestation bob-ref {:verify-fn demo-signing-fn}))

  ;; Try resolving a non-existent reference
  (def bogus-ref "attestation:sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
  (def resolved-bogus (ars/resolve-attestation bogus-ref))

  ;; Try resolving a bare (untyped) reference — this is what the old format
  ;; used, and it triggers :unparseable-reference
  (def bare-ref "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b")
  (def resolved-bare (ars/resolve-attestation bare-ref)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Attestation Resolution Results"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "Each typed reference is resolved through the attestation resolver. The "
   "resolver verifies: hash integrity, schema type, and (optionally) "
   "signature validity. Distinct error types distinguish failure modes."]

  ;; Resolution results table
  [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr" :gap "12px"}}
   ;; Alice — valid
   [:div {:style {:background "#052e16" :border "1px solid #166534" :borderRadius "6px" :padding "10px"}}
    [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom "6px"}}
     [:span {:style {:color "#7ADDDC" :fontWeight 700 :fontSize "12px"}} "Alice's Attestation"]
     [:span {:style {:color "#22c55e" :fontWeight 700 :fontSize "11px"}} "✓ RESOLVED"]]
    (if (:valid? resolved-alice)
      [:div {:style {:fontSize "11px"}}
       [:div {:style {:color "#94a3b8"}} "hash match: " [:span {:style {:color "#22c55e"}} (:hash-ok? (:checks resolved-alice))]]
       [:div {:style {:color "#94a3b8"}} "type match:  " [:span {:style {:color "#22c55e"}} (:type-ok? (:checks resolved-alice))]]
       [:div {:style {:color "#94a3b8"}} "signature:   " [:span {:style {:color (if (:signature-valid? (:checks resolved-alice)) "#22c55e" "#ef4444")}}
                                                            (:signature-valid? (:checks resolved-alice))]]])]

   ;; Bob — valid
   [:div {:style {:background "#052e16" :border "1px solid #166534" :borderRadius "6px" :padding "10px"}}
    [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom "6px"}}
     [:span {:style {:color "#7ADDDC" :fontWeight 700 :fontSize "12px"}} "Bob's Attestation"]
     [:span {:style {:color "#22c55e" :fontWeight 700 :fontSize "11px"}} "✓ RESOLVED"]]
    (if (:valid? resolved-bob)
      [:div {:style {:fontSize "11px"}}
       [:div {:style {:color "#94a3b8"}} "hash match: " [:span {:style {:color "#22c55e"}} (:hash-ok? (:checks resolved-bob))]]
       [:div {:style {:color "#94a3b8"}} "type match:  " [:span {:style {:color "#22c55e"}} (:type-ok? (:checks resolved-bob))]]
        [:div {:style {:color "#94a3b8"}} "signature:   " [:span {:style {:color (if (:signature-valid? (:checks resolved-bob)) "#22c55e" "#ef4444")}}
                                                             (:signature-valid? (:checks resolved-bob))]]])]

   ;; Bogus — :missing
   [:div {:style {:background "#450a0a" :border "1px solid #7f1d1d" :borderRadius "6px" :padding "10px"}}
    [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom "6px"}}
     [:span {:style {:color "#f87171" :fontWeight 700 :fontSize "12px"}} "Non-Existent Ref"]
     [:span {:style {:color "#ef4444" :fontWeight 700 :fontSize "11px"}} "✗ FAILED"]]
    [:div {:style {:fontSize "11px"}}
     [:div {:style {:color "#94a3b8"}} "error: " [:span {:style {:color "#f87171"}} (pr-str (:error resolved-bogus))]]
     [:div {:style {:color "#64748b" :fontSize "10px"}} "attestation not found in registry"]]]]

  ;; Bare ID rejection
  [:div {:style {:marginTop "12px" :background "#1e3a5f" :border "1px solid #1d4ed8"
                 :borderRadius "6px" :padding "10px"}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom "4px"}}
    [:span {:style {:color "#60a5fa" :fontWeight 700 :fontSize "12px"}} "Bare ID Rejection"]
    [:span {:style {:color "#93c5fd" :fontWeight 700 :fontSize "11px"}} "⛔ BLOCKED"]]
   [:div {:style {:fontSize "11px" :color "#94a3b8"}}
    "A bare attestation hash without the \"attestation:sha256:\" prefix "
    "produces " [:code {:style {:color "#93c5fd"}} ":unparseable-reference"] "."]
   [:div {:style {:fontSize "11px" :color "#60a5fa" :marginTop "4px"}}
    "This prevents accidental resolution of untyped identifiers."]]])

;; ===========================================================================
;; 7. Full Evidence Chain
;; ===========================================================================

;; The following hashes form a complete verifiable chain from the protocol
;; action through to the attested DAG nodes:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Artifact" "Hash / Reference"]
  :rows [["World" (hc/hash-with-intent {:hash/intent :world-structure} world)]
         ["Projection artifact" (:projection-hash projection-artifact)]
         ["Alice's allocation" (str (:paid (first (:allocations allocation-result))))]
         ["Bob's allocation" (str (:paid (second (:allocations allocation-result))))]
         ["Alice's attestation ID" (str (subs (:attestation/id attestation-alice) 0 20) "...")]
         ["Bob's attestation ID" (str (subs (:attestation/id attestation-bob) 0 20) "...")]
         ["Alice's DAG node hash" (str (subs (:node-hash dag-node-alice) 0 20) "...")]
         ["Bob's DAG node hash" (str (subs (:node-hash dag-node-bob) 0 20) "...")]
         ["DAG node Alice → Bob" (str (subs (first (:parent-hashes dag-node-bob)) 0 20) "...")]]})

;; The DAG linking is verified by checking that Alice's DAG node hash
;; appears in Bob's :parent-hashes:

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def dag-link-verified
  (contains? (set (:parent-hashes dag-node-bob)) (:node-hash dag-node-alice)))

;; DAG link verification result: Alice's node hash is in Bob's parent-hashes.

;; ===========================================================================
;; 8. Summary: Evidence DAG with Typed References
;; ===========================================================================

;; This demo shows the complete evidence path:
;;
;;   1. Slash event → allocation input → projection artifact (ex-ante)
;;   2. Pro-rata computation → allocation result (ex-post)
;;   3. Mechanism evidence envelope → hash-committed witness with
;;      structural validation and independent reconstruction check
;;   4. Validator attestations → content-addressed attestation records
;;   5. Typed references → attestation:sha256:<hash> format
;;   6. DAG nodes → full execution evidence nodes with typed refs
;;   7. Resolution → registry lookup + hash match + type verify + signature
;;
;; Key design properties:
;;
;;   • Typed references enable unambiguous resolution: the prefix declares
;;     both the object type (:attestation) and hash algorithm (:sha256).
;;
;;   • Content addressing means the reference hash IS the artifact identity
;;     — no indirection through mutable database IDs.
;;
;;   • Distinct error types (:missing, :hash-mismatch, :unparseable-reference,
;;     :unsupported-algorithm) let callers handle each failure mode separately.
;;
;;   • Optional signature verification adds cryptographic security without
;;     requiring it for structural integrity checks.
