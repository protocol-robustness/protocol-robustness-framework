;; # Canonical Cancellation
;;
;; **Audience:** Newcomers → technical. No PRF vocabulary required to follow the
;; story.
;;
;; **One question:**
;;
;; > Can a valid approval be reused to cancel the wrong thing?
;;
;; A cancellation decision is authorised by a three-member certificate. But
;; authorisation alone is not enough. This notebook shows that a canonical
;; cancellation binds the **complete** target/action/state context — and that
;; incomplete or forbidden substitutions never become authorised, no matter how
;; valid the certificate looks.
;;
;; **The contract in one line:**
;;
;; > A cancellation decision must bind the whole outcome over the exact target
;; > snapshot; otherwise members could agree to cancel the same logical target
;; > while relying on different target versions or lifecycle snapshots.
;;
;; Everything below is recomputed with the real cancellation machinery
;; (`resolver-sim.assurance.canonical-force-authorisation`). Nothing is
;; narrated from a fixture.
;;
;; **Companions in this family:**
;; - `notebooks/clean_room_not_admitted` — can this evidence be accepted?
;; - `notebooks/not_admitted` — the evidence-chain and invariant admission analysis
;; - `notebooks/research_resolution` — is the claim actually established?

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.canonical-cancellation
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]))

;; ## What a cancellation decision must bind
;;
;; The certificate signs a decision. That decision commits to the whole
;; snapshot it targets — not just "cancel thing X". Every field below is part
;; of the binding. Missing one is not a small omission; it means the members
;; may have agreed to different things.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def cancellation-binding-fields
  (sort (map name cfa/cancellation-binding-fields)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Binding field" "What it commits to"]
  :rows (map (fn [f]
               [f
                (case f
                  "target/id"              "which logical target"
                  "target/hash"            "which exact version of the target"
                  "lifecycle/profile-id"   "which lifecycle the target lives under"
                  "lifecycle/profile-version" "which lifecycle version"
                  "target/state-evidence-root" "the state evidence the decision was made against"
                  "cancellation/action"    "the exact authorised action"
                  "cancellation/effects"   "what the cancellation must effect"
                  "cancellation/reason"    "why"
                  "decision/profile-id"    "which decision profile authorised it"
                  "policy/instance"        "which policy instance"
                  "decision/validity-window" "when the decision is valid"
                  "conflict/consumption-key" "the atomic conflict key it must win"
                  f)])
             cancellation-binding-fields)})

;; ## A complete binding
;;
;; A decision with **every** field populated is a complete binding:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def snapshot
  {:target/id "alloc/9" :target/hash "sha256:0"
   :lifecycle/profile-id :prf.lifecycle-window/probabilistic-allocation
   :lifecycle/profile-version 1
   :target/state-evidence-root "sha256:1"
   :cancellation/action :cancel :cancellation/effects :prevent
   :cancellation/reason :mistake :decision/profile-id "2-3"
   :policy/instance "pol/1" :decision/validity-window "t0..t1"
   :conflict/consumption-key "ck"})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def complete-binding
  {:complete? (cfa/cancellation-binding-complete? snapshot)
   :missing (cfa/missing-cancellation-binding-fields snapshot)})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#052e16" :border "1px solid #22c55e"
                :borderRadius "8px" :padding "12px 16px"
                :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
  [:span {:style {:color "#22c55e" :fontWeight 700}} "✓ COMPLETE"]
  " — every binding field is populated. Missing fields: "
  (if (seq (:missing complete-binding)) (pr-str (:missing complete-binding)) "none")])

;; ## What happens when a field is dropped
;;
;; Now remove the target version. The decision no longer says *which* version of
;; the target it cancels. It is **not** a complete binding — and a cancellation
;; that cannot name its exact target is not canonical.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def incomplete-binding
  {:complete? (cfa/cancellation-binding-complete? (dissoc snapshot :target/hash))
   :missing (cfa/missing-cancellation-binding-fields (dissoc snapshot :target/hash))})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#450a0a" :border "1px solid #ef4444"
                :borderRadius "8px" :padding "12px 16px"
                :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
  [:span {:style {:color "#f87171" :fontWeight 700}} "✕ INCOMPLETE"]
  " — missing: " (pr-str (:missing incomplete-binding))
  ". A blank placeholder counts as missing, not as present."])

;; ---
;; ## Substituting the target
;;
;; Here is the attack the binding exists to stop. A certificate is signed for
;; `alloc/9` at `sha256:0`. Someone tries to apply it to a *different* snapshot
;; — same logical id, different version, or a wholly different target. The
;; decision profile still conforms. The certificate still validates. But the
;; binding no longer matches the current snapshot, so the cancellation is **not
;; executable**.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn gates
  "Run classify-cancellation-gates with explicit gate inputs."
  [& {:keys [target-state certificate binding race window]
      :or {certificate (cfa/declare-profile {:member-count 3 :threshold 2
                                             :profile-id "cert/2-3"})}}]
  (cfa/classify-cancellation-gates
   {:decision-opts {:profile-id "cfa/2-3"}
    :target-state target-state
    :certificate certificate
    :snapshot-binding-valid? binding
    :transition-won? race
    :window window}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def correct-snapshot
  (select-keys snapshot cfa/cancellation-binding-fields))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def substituted-snapshot
  (assoc correct-snapshot :target/hash "sha256:999"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def substitution-verdict
  (gates :target-state :proposed
         :binding (cfa/current-snapshot-binding-valid?
                   correct-snapshot substituted-snapshot)
         :race true))

;; The declared three-member certificate profile is conforming. This model
;; result is not cryptographic authorisation; it only shows profile shape. Yet
;; the execution-composition gate stays closed:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Gate" "Value"]
  :rows [["certificate profile conforms?" (if (:cancellation/certificate-profile-conforming? substitution-verdict) "✓" "✕")]
         ["binds current snapshot?" (if (:cancellation/snapshot-binding-valid? substitution-verdict) "✓" "✕")]
         ["executable?" (if (:cancellation/executable? substitution-verdict) "✓" "✕")]
         ["blocking reason(s)" (str/join ", " (map name (:cancellation/blocking-reasons substitution-verdict)))]]})

;; **Why:** verified authorisation and binding are independent admission facts.
;; This profile-only model cannot rescue a stale or substituted binding. The
;; decision is not rewritten; it simply cannot be applied to a snapshot it never
;; committed to.

;; ---
;; ## The window gate
;;
;; Even a complete binding against the correct snapshot is not enough if the
;; target's lifecycle window has closed. An open window does not authorise
;; cancellation; a closed window defeats a valid certificate.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def closed-window-verdict
  (gates :target-state :consumed
         :binding true
         :race true))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Gate" "Value"]
  :rows [["window open?" (if (:cancellation/window-possible? closed-window-verdict) "✓" "✕")]
         ["certificate profile conforms?" (if (:cancellation/certificate-profile-conforming? closed-window-verdict) "✓" "✕")]
         ["executable?" (if (:cancellation/executable? closed-window-verdict) "✓" "✕")]
         ["blocking reason(s)" (str/join ", " (map name (:cancellation/blocking-reasons closed-window-verdict)))]]})

;; ---
;; ## The full gate ladder
;;
;; Cancellation authority is four layers, each independently owned. They must
;; not be collapsed into one flag — a single "can we cancel?" hides which layer
;; refused.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Layer" "Gate" "Owned by"]
  :rows [["window"      "is the lifecycle window open?"            "lifecycle reconciliation"]
         ["authority"   "does the three-member certificate conform?" "certificate layer"]
         ["executable"  "window AND authority AND snapshot binding" "composition"]
         ["committable" "executable AND the conflict-key transition won" "transition race"]]})

;; ---
;; ## What a canonical cancellation is not
;;
;; Some operations look like cancellations but must stay deterministic — they
;; never become canonical cancellation decisions even with a valid certificate:
;; submitting a request, expiring at a deadline, deterministic invalidation,
;; rejecting a post-cutpoint cancellation.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def deterministic-ops
  (mapv (fn [op] [op (cfa/cancellation-decision-required? op)])
        [:submit-cancel-request :expire-at-deadline
         :deterministic-invalidation :reject-post-cutpoint-cancellation
         :execute-certified-cancellation :apply-deterministic-fallback
         :decide-cancel-valid-authorisation]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Operation" "Canonical cancellation decision?"]
  :rows (map (fn [[op required?]]
               [(name op) (if required? "✓ (authorised cancellation)" "— (deterministic)")])
             deterministic-ops)})

;; ---
;; ## The commit
;;
;; A complete binding is content-addressed: the decision hash commits to every
;; field, so no part of the target/action/state context can be edited without
;; breaking the binding.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def committed
  (assoc snapshot :cancellation/binding-hash
         (cfa/cancellation-binding-hash snapshot)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def tampered
  (assoc committed :target/hash "sha256:999"))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Decision" "Binding hash valid?" "Why"]
  :rows [["committed binding"
          (if (cfa/cancellation-binding-hash-valid? committed) "✓" "✕")
          "hash recomputes from the committed fields"]
         ["tampered binding"
          (if (cfa/cancellation-binding-hash-valid? tampered) "✓" "✕")
          "hash no longer matches the edited target"]]})

;; ---
;; ## Reproduce
;;
;; ```shell
;; clojure -M:test -e "(require 'resolver-sim.assurance.cancellation-gates-test) (clojure.test/run-tests 'resolver-sim.assurance.cancellation-gates-test)"
;; ```
;;
;; The full gate matrix, the whole-snapshot binding (contract 7), and the
;; fail-closed rejection classes live in the cancellation-gates test suite.
