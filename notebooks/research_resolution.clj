;; # Research Resolution
;;
;; **Audience:** Newcomers → technical. The story needs no PRF vocabulary to
;; follow.
;;
;; **One question:**
;;
;; > Is the claim actually established?
;;
;; A research claim is not a single verdict. It is the end of a chain:
;;
;; > **What was run → what evidence that produced → what each researcher
;; > concluded → whether those conclusions collectively establish, qualify, or
;; > contest the claim.**
;;
;; This notebook makes that chain visible and executable. It deliberately does
;; not cover researcher assignment, approval voting, or force-authorisation —
;; those have their own homes. The semantic center here is the *transition*
;; from evidence to position to conclusion to collective status.
;;
;; **Companions in this family:**
;; - `notebooks/clean_room_not_admitted` — can this evidence be accepted?
;; - `notebooks/canonical_cancellation` — can a valid approval cancel the wrong thing?
;; - `notebooks/not_admitted` §15–16 — researcher assignment & approval mechanics
;; - `notebooks/researcher_interaction_topology` — review rounds, keys, topology

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.research-resolution
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.benchmark.research-command :as rc]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.research-conclusion :as concl]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.review-aggregate-check :as rac]))

;; ## Stage 1 — What was run
;;
;; A claim must say which command produced it. A research command is a
;; structured execution-provenance artifact: the normalized argv, the research
;; scope it requested, and the environment/runner/input/output roots, committed
;; as one canonical hash. Two commands that differ only in harmless argument
;; ordering still share the same identity; two commands that request different
;; research scopes do not.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def command
  (rc/build-command
   {:command/id :bench/liquidity-fair
    :command/type :theorem-evaluation
    :command/argv ["prf" "benchmark" "evaluate" "liquidity-fairness"]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def scoped-command
  (rc/build-command
   {:schema-version "research-command.v2"
    :command/id :bench/liquidity-fair
    :command/type :theorem-evaluation
    :command/argv ["prf" "benchmark" "evaluate" "liquidity-fairness"]
    :command/includes [{:kind :research-scope/analysis
                        :ref :research-analysis/incentive}]}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Command" "Schema" "Valid?" "Hash"]
  :rows [[":bench/liquidity-fair (v1)" "research-command.v1"
          (if (rc/command-valid? command) "✓" "✕")
          (subs (:command/hash command) 0 19)]
         [":bench/liquidity-fair (v2, scoped)" "research-command.v2"
          (if (rc/command-valid? scoped-command) "✓" "✕")
          (subs (:command/hash scoped-command) 0 19)]]})

;; The v2 command commits to a *typed research scope* — which analysis it
;; targets. The scope vocabulary is controlled, not free text:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def scopes
  (sort-by (comp str second)
           (map (fn [k] [k (get rc/valid-command-scope-refs k)])
                (keys rc/valid-command-scope-refs))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Scope kind" "Known refs"]
  :rows (map (fn [[kind refs]] [(name kind) (str/join ", " (map name refs))]) scopes)})

;; A trace commits an ordered collection of command identities. It is a
;; `canonical-value-sequence.v1` commitment: every component must be a valid
;; command, and an empty trace is not meaningful provenance.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def second-command
  (rc/build-command
   {:command/id :bench/liquidity-fair-copy
    :command/type :theorem-evaluation
    :command/argv ["prf" "benchmark" "evaluate" "liquidity-fairness"]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def trace
  (rc/build-command-trace-v2 {:commands [command second-command]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def trace-failure
  (try
    (rc/build-command-trace-v2 {:commands []})
    {:rejected? false}
    (catch clojure.lang.ExceptionInfo e
      {:rejected? true :reason (-> e ex-data :error name)})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Trace" "Components" "Root" "Verdict"]
  :rows [["two command identities" "2"
          (subs (:trace/root trace) 0 19) "committed ✓"]
         ["empty trace" "0" "—"
          (if (:rejected? trace-failure)
            (str "rejected — " (:reason trace-failure)) "BUG: accepted")]]})

;; ---
;; ## Stage 2 — Is the research/evidence adequate?
;;
;; Before a claim follows, the research itself must be adequate. A researcher's
;; position assesses the benchmark model component by component — reproduction,
;; model state, transitions, authority, adversary model, parameters, cases,
;; invariants, temporal fidelity, sampling policy — with explicit
;; absent-statuses meaning "not assessed," not "assessed and fine."

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def content-root "sha256:model-root")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn build-researcher
  [id status]
  (rp/build-position
   {:benchmark/content-root content-root
    :researcher/id id
    :outcome-hash "sha256:outcome"
    :dimensions {:reproduction {:status status}
                 :model-state {:status :adequate}
                 :model-transitions {:status :adequate}
                 :model-authority {:status :adequate}
                 :model-parameters {:status :adequate}
                 :model-cases {:status :adequate}
                 :model-invariants {:status :adequate}
                 :temporal-fidelity {:status :adequate}
                 :sampling-policy {:status :adequate}}}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def alpha (build-researcher :researcher-alpha :reproduced))
(def beta (build-researcher :researcher-beta :reproduced))
(def gamma (build-researcher :researcher-gamma :unable-to-reproduce))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Researcher" "Reproduction" "Model state" "Temporal fidelity" "Position valid?"]
  :rows (map (fn [p]
               [(name (:researcher/id p))
                (name (rp/dimension-status p :reproduction))
                (name (rp/dimension-status p :model-state))
                (name (rp/dimension-status p :temporal-fidelity))
                (if (rp/position-valid? p) "✓" "✕")])
             [alpha beta gamma])})

;; The `temporal-fidelity` dimension is where time is a first-class concern:
;; a model that only behaves in one time-slice does not support a
;; time-independent claim. An absent status (`:not-reviewed`,
;; `:insufficient-information`, `:not-applicable`) is visibly not an assessment.

;; ---
;; ## Stage 3 — What claim follows
;;
;; A conclusion is an "X, **therefore** Y" artifact: what was established, the
;; inference, what follows, and — critically — what was *not* concluded.
;; Conclusions must not overreach: a finding within one parameter domain must
;; not be presented as universal.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def conclusion
  (concl/build-conclusion
   {:conclusion/id :claim/liquidity-fair
    :conclusion/premise {:x "under the committed parameter domain, pro-rata allocation is fair"}
    :conclusion/result {:y "pro-rata fairness holds within that domain"}
    :conclusion/status :established
    :conclusion/scope {:cases 3 :parameter-domain-root "sha256:domain"}
    :conclusion/qualifications ["not shown to hold outside the committed domain"]
    :conclusion/falsifiers [{:falsifier/id :partial-fill-skip
                             :status :untested}]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def overreaching-conclusion
  (concl/build-conclusion
   {:conclusion/id :claim/liquidity-fair-universal
    :conclusion/premise {:x "under the committed parameter domain, pro-rata allocation is fair"}
    :conclusion/result {:y "pro-rata fairness is universal"}
    :conclusion/status :established}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Conclusion" "Status" "Overreaches?" "Falsifiers"]
  :rows [["liquidity-fair (bounded)" "established"
          (if (concl/conclusion-overreaches? conclusion) "✕ YES" "✓ no")
          (str/join ", " (map (comp name :status) (:conclusion/falsifiers conclusion)))]
         ["liquidity-fair (universal)" "established"
          (if (concl/conclusion-overreaches? overreaching-conclusion) "✕ YES" "✓ no")
          "—"]]})

;; ---
;; ## Stage 4 — What the collective view is
;;
;; Now the three researchers state their position on the claim itself, not just
;; on the model. Each position carries a target status for the conclusion:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn position-on-claim
  [id reproduction target-status]
  (rp/build-position
   {:benchmark/content-root content-root
    :researcher/id id
    :outcome-hash "sha256:outcome"
    :dimensions {:reproduction {:status reproduction}}
    :position/targets [{:kind :conclusion
                        :id :claim/liquidity-fair
                        :hash (:conclusion/hash conclusion)
                        :status target-status
                        :rationale (case target-status
                                     :supported "reproduced and consistent"
                                     :challenged "could not reproduce" 
                                     :not-supported "contradicted by counterexample")}]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def claim-positions
  [(position-on-claim :researcher-alpha :reproduced :supported)
   (position-on-claim :researcher-beta :reproduced :supported)
   (position-on-claim :researcher-gamma :unable-to-reproduce :challenged)])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Researcher" "Reproduction" "On the claim" "Rationale"]
  :rows (map (fn [p]
               [(name (:researcher/id p))
                (name (rp/dimension-status p :reproduction))
                (name (rp/target-status p :conclusion :claim/liquidity-fair))
                (:rationale (rp/find-target p :conclusion :claim/liquidity-fair))])
             claim-positions)})

;; ### Collective conclusion
;;
;; Two researchers support the claim; one challenges it. The collective view is
;; **QUALIFIED** — supported by a majority but contested — not "established."

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def collective-status
  (let [statuses (map #(rp/target-status % :conclusion :claim/liquidity-fair)
                      claim-positions)
        supported (count (filter #{:supported} statuses))
        challenged (count (filter #{:challenged} statuses))]
    {:supported supported :challenged challenged
     :collective (cond
                   (and (= supported 2) (= challenged 1)) :qualified
                   (= supported 3) :established
                   :else :contested)}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :fontFamily "monospace" :maxWidth "760px"}}
  [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"
                 :letterSpacing "0.05em" :fontWeight 700}}
   "Collective conclusion"]
  [:div {:style {:fontSize "20px" :fontWeight 800 :color "#fbbf24" :marginTop "4px"}}
   (str/upper-case (name (:collective collective-status)))]
  [:div {:style {:color "#e2e8f0" :fontSize "13px" :marginTop "4px"}}
   (str (:supported collective-status) " support · " (:challenged collective-status)
        " challenge — a contested claim is not silently upgraded to established.")]])

;; The collective hash binds the non-withdrawn conclusions. A withdrawn
;; conclusion is a terminal retraction and contributes no evidence:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def collective-hash
  (concl/conclusion-collective-hash
   [{:conclusion/hash (:conclusion/hash conclusion)
     :conclusion/status :established}
    {:conclusion/hash "sha256:withdrawn" :conclusion/status :withdrawn}]))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:fontFamily "monospace" :fontSize "12px" :color "#e2e8f0"}}
  "collective evidence root (withdrawn excluded): "
  [:strong {:style {:color "#22c55e"}} (subs collective-hash 0 19) "…"]])

;; ### Consistency check — is this a real collective view?
;;
;; A collective status is not produced by counting labels. The review structure
;; itself must be consistent: the aggregate checks verify that the review
;; round's member bit-width, member-key density, three-member standard, and
;; classifications all refer to the same committed research object.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def review-round
  (rr/build-review-round
   {:benchmark/content-root content-root
    :review-round/purpose :model-admission
    :review-round/members [{:review-member/key 0 :researcher/id :researcher-alpha :role :model-steward}
                           {:review-member/key 1 :researcher/id :researcher-beta :role :independent-reproducer}
                           {:review-member/key 2 :researcher/id :researcher-gamma :role :adversarial-reviewer}]
    :review-round/membership-frozen-at 0
    :review-round/policy-root "sha256:policy"}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def aggregate
  (rac/run-review-aggregate-checks review-round))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def aggregate-check-result
  (select-keys aggregate [:holds? :checks]))

;; **Cross-structure check:** the review aggregate, member positions, target
;; identities, and conclusion must all refer to the same committed research
;; object.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if (:holds? aggregate) "#052e16" "#450a0a")
                :border (str "1px solid " (if (:holds? aggregate) "#22c55e" "#ef4444"))
                :borderRadius "8px" :padding "12px 16px"
                :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
  "cross-structure check: "
  [:strong {:style {:color (if (:holds? aggregate) "#4ade80" "#f87171")}}
   (if (:holds? aggregate) "PASS" "FAIL")]
  " — review aggregate, member positions, target identities, and conclusion "
  "refer to the same committed research object."])

;; The checks, exposed:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Check" "Holds?"]
  :rows (map (fn [[k v]] [(name k) (if (:holds? v) "✓" "✕")])
             (sort-by key (:checks aggregate)))})

;; ---
;; ## Deeper inspection
;;
;; This notebook stops at the proof boundary: it shows that a consistent
;; cross-structure check exists and matters here. How review rounds, canonical
;; member indices, identities, and interaction topology are *constructed* lives
;; in `researcher_interaction_topology`.

;; ## Reproduce
;;
;; ```shell
;; clojure -M:test -e "(require 'resolver-sim.benchmark.review-aggregate-check 'resolver-sim.benchmark.research-command 'resolver-sim.benchmark.researcher-position 'resolver-sim.benchmark.research-conclusion)"
;; ```
