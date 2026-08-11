;; # Decisions Stay Bound to Their Verified State
;;
;; **A public guide to the executable evidence.**
;;
;; > A result is accepted only in the exact state and history it was verified
;; > for. Stale, substituted, and conflicting attempts are refused.
;;
;; This page is deliberately brief. It introduces three bounded guarantees and
;; links to the notebooks that recompute each claim with the implementation.
;; It does not turn an operational result into an incentive claim, and it does
;; not treat a pipeline short-circuit as an admission verdict.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :hide}}
(ns notebooks.verification-overview
  (:require [nextjournal.clerk :as clerk]))

;; ## The three questions

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#f8fafc" :padding "28px"
                :borderRadius "10px" :marginBottom "24px" :maxWidth "960px"}}
  [:div {:style {:color "#7ADDDC" :fontFamily "monospace" :fontSize "12px"
                 :fontWeight 700 :letterSpacing "0.08em" :marginBottom "10px"}}
   "EXECUTABLE EVIDENCE, NOT A MARKETING CLAIM"]
  [:h1 {:style {:fontSize "28px" :margin "0 0 12px" :lineHeight "1.2"}}
   "Decisions stay bound to their verified state and history."]
  [:p {:style {:margin "0" :color "#cbd5e1" :fontSize "16px" :lineHeight "1.6"
               :maxWidth "760px"}}
   "The protocol refuses an attempted continuation based on obsolete history, "
   "and refuses a cancellation approval that no longer binds the exact target "
   "state it was approved for. Each statement below links to a live notebook "
   "that recomputes the relevant outcome."]])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fit, minmax(260px, 1fr))"
                :gap "16px" :maxWidth "960px"}}
  [:div {:style {:border "1px solid #0f766e" :borderRadius "8px" :padding "18px"
                 :background "#f0fdfa"}}
   [:div {:style {:fontSize "24px"}} "⛓️"]
   [:h2 {:style {:fontSize "18px" :margin "8px 0"}} "No stale resubmissions"]
   [:p {:style {:color "#334155" :lineHeight "1.55"}}
    "A candidate can extend only the current chain head. A candidate against an "
    "older, superseded parent is not admitted and does not move the head."]
   [:a {:href "/notebooks/resubmission_chain" :style {:color "#0f766e" :fontWeight 700}}
    "See the current-head proof →"]]

  [:div {:style {:border "1px solid #1d4ed8" :borderRadius "8px" :padding "18px"
                 :background "#eff6ff"}}
   [:div {:style {:fontSize "24px"}} "🔒"]
   [:h2 {:style {:fontSize "18px" :margin "8px 0"}} "No reusable cancellation approval"]
   [:p {:style {:color "#334155" :lineHeight "1.55"}}
    "A valid certificate is not sufficient by itself. The decision must bind the "
    "exact target version, lifecycle state, action, effects, validity window, "
    "and conflict key."]
   [:a {:href "/notebooks/canonical_cancellation" :style {:color "#1d4ed8" :fontWeight 700}}
    "See the cancellation-binding proof →"]]

  [:div {:style {:border "1px solid #b45309" :borderRadius "8px" :padding "18px"
                 :background "#fffbeb"}}
   [:div {:style {:fontSize "24px"}} "📜"]
   [:h2 {:style {:fontSize "18px" :margin "8px 0"}} "No unverified rejection claims"]
   [:p {:style {:color "#334155" :lineHeight "1.55"}}
    "The clean-room corpus re-runs its committed cases. A rejection is useful only "
    "when it still recomputes as rejected under the verifier that owns it."]
   [:a {:href "/notebooks/clean_room_not_admitted" :style {:color "#b45309" :fontWeight 700}}
    "See the clean-room corpus →"]]])

;; ## What each result does—and does not—say
;;
;; The proofs above are admission, binding, and corpus-recomputation claims.
;; They do not by themselves establish that every economic incentive is aligned.
;; Incentive evaluation and incentive compatibility require their own declared
;; deviation scope and evidence.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Result" "What it establishes" "What it does not establish"]
  :rows [["current-head admission"
          "A stale parent cannot extend the verified linear history."
          "Whether the submission's broader research claim is true."]
         ["canonical cancellation"
          "An approval cannot be applied to a substituted or stale target snapshot."
          "That cancellation is desirable or incentive compatible in every strategy domain."]
         ["clean-room corpus"
          "Committed corpus verdicts still recompute under their owner verifier."
          "Coverage of every possible malformed input or external implementation."]
         ["short-circuit execution"
          "Later pipeline nodes do not run after a runtime halt."
          "An admission decision; it is a separate execution-control result."]
         ["incentive compatibility"
          "Only a result evaluated against its declared deviation domain."
          "A universal claim about untested actors, coalitions, or strategies."]]})

;; ## Explore the supporting evidence

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1" :borderRadius "8px"
                :padding "18px" :maxWidth "960px"}}
  [:h2 {:style {:fontSize "18px" :marginTop 0}} "Supporting technical evidence"]
  [:ul {:style {:lineHeight "1.8" :color "#334155"}}
   [:li [:a {:href "/notebooks/not_admitted"} "Admission gallery"]
    " — evidence-chain and invariant-based admission failures."]
   [:li [:a {:href "/notebooks/demo_short_circuit"} "Short-circuit execution"]
    " — a separate composition demonstration: later nodes, effects, and node evidence are skipped after the halt."]
   [:li [:a {:href "/notebooks/demo_not_admitted"} "Changed-result demo"]
    " — the same custody check accepts an original result and rejects a changed amount."]
   [:li [:a {:href "/notebooks/research_resolution"} "Research resolution"]
    " — what a run established, qualified, or left contested."]]])

;; ## Reproduce
;;
;; The linked notebooks name their source namespaces and test commands. The
;; current-head proof is backed by `resolver-sim.resubmission.chain/admit!`; the
;; cancellation proof by `resolver-sim.assurance.canonical-force-authorisation`;
;; and the clean-room corpus by `resolver-sim.conformance.bundle/verify-bundle`.
