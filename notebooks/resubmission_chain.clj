;; # The Chain Decides: current-head and the Resubmission Admission Cutpoint
;;
;; **Audience:** Protocol reviewers, security researchers, implementers of the
;; resubmission chain, grant reviewers.
;;
;; **Purpose:** Make the resubmission chain's *chain-admission cutpoint*
;; visible. A researcher may re-submit after a rejection, but the chain admits
;; at most one successor per parent — and only when the parent **is the current
;; head**. A child submitted against a stale head is NOT ADMITTED, no matter how
;; well-formed its receipt is. This notebook recomputes every verdict with the
;; real chain facade (`resolver-sim.resubmission.chain/admit!`), so the tables
;; below cannot drift from executable truth.
;;
;; **Companions in this family:**
;; - `notebooks/not_admitted` — the evidence-chain and invariant admission analysis
;; - `notebooks/demo_not_admitted` — Demo A: change an amount after verification
;; - `notebooks/demo_reorder_chain` — Demo B: reorder the evidence
;; - `notebooks/clean_room_not_admitted` — the clean-room corpus verdicts
;; - `notebooks/research_resolution` — the research commands, evidence, and
;;   collective conclusion that a correction carries forward
;;
;; **Data contract:** the chain state machine is `src/resolver_sim/resubmission/`
;; (`chain.clj`, `transition.clj`, `receipt.clj`); the normative contract is
;; `docs/security/RESUBMISSION.md` §9 (chain admission cutpoint).

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.resubmission-chain
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.allocation.native-evidence :as native-evidence]
            [resolver-sim.benchmark.research-command :as research-command]
            [resolver-sim.contract-model.replay.flags :as rflags]
            [resolver-sim.resubmission.chain :as chain]
            [resolver-sim.resubmission.derive-kind :as derive-kind]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.verify :as verify]))

;; ## The one question
;;
;; The chain holds one *current head*. A new attempt is admitted only when its
;; parent **is** that head. What happens when an attempt points at an older,
;; already-superseded parent?

;; ---
;; ## The chain model

;; Each family is a linear chain. The facade stores the mutable state and
;; applies the pure transition atomically. Three facts define admission:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- admit-request
  "Request shape consumed by `chain/admit!` (see chain.clj)."
  [& {:keys [r seq parent link idem basis version]}]
  (cond-> {:receipt-hash r :sequence seq :parent-receipt-hash parent
           :link-hash link :idempotency-key idem :basis-root basis}
    version (assoc :expected-chain-version version)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- fresh-chain []
  (chain/new-chain "sha256:FAM"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- result-row
  "Normalise an admit! result into a display row."
  [label r]
  {:label label
   :status (name (:admission-status r))
   :reason (name (or (:reason r) :ok))})

;; ### 1. One successor per parent
;;
;; `:chain/successor-by-parent` — once a parent has admitted a child, that
;; parent's successor slot is consumed forever.

;; ### 2. The parent must be the current head
;;
;; `:chain/head` — a linear chain extends only at the tip. `current-head` is
;; the receipt-hash of the tip (`nil` before the first attempt).

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn current-head-is
  "Facade read: the receipt-hash at the tip of the chain (nil before the first
   attempt)."
  [c]
  (chain/current-head c))

;; ### 3. The receipt records the chain decision
;;
;; Every attempt carries `:attempt-receipt/chain {:admission-status
;; :admitted|:not-admitted :sequence N :parent-receipt-hash ...}` (§9). A
;; `:not-admitted` attempt does NOT consume the successor slot.

;; ---
;; ## Demo C — attempt against a stale head

;; Baseline: a healthy chain, one successful admission after another. Then the
;; intervention: a new attempt points at the *superseded* parent, and the same
;; verifier refuses it.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def demo-chain
  (fresh-chain))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def demo-r1
  (chain/admit! demo-chain
                (admit-request :r "sha256:R1" :seq 1 :link "sha256:L1"
                               :idem "sha256:I1" :basis "sha256:B1")))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def demo-r2
  (chain/admit! demo-chain
                (admit-request :r "sha256:R2" :seq 2 :parent "sha256:R1"
                               :link "sha256:L2" :idem "sha256:I2"
                               :basis "sha256:B2")))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def stale-head-attempt
  (chain/admit! demo-chain
                (admit-request :r "sha256:R3" :seq 3 :parent "sha256:R1"
                               :link "sha256:L3" :idem "sha256:I3"
                               :basis "sha256:B3")))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Step" "Attempt" "Parent" "Verdict" "Reason" "Head after"]
  :rows [["1" "R1" "—"
          (name (:admission-status demo-r1)) (name (or (:reason demo-r1) :ok))
          (str (chain/current-head demo-chain))]
         ["2" "R2" "R1 (current head)"
          (name (:admission-status demo-r2)) (name (or (:reason demo-r2) :ok))
          (str (chain/current-head demo-chain))]
         ["3" "R3" "R1 (STALE — head is R2)"
          (name (:admission-status stale-head-attempt))
          (name (:reason stale-head-attempt))
          (str (chain/current-head demo-chain))]]})

;; The stale-head attempt is **NOT ADMITTED** with `:parent-not-current-head`.
;; R1 is a *valid* parent in the abstract sense — it exists, it is eligible, it
;; is rejected and final — but it is no longer the tip. The chain refuses to
;; fork. And because admission failed, the head stays `sha256:R2`; the slot was
;; not consumed.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn admission-cutpoint-holds?
  "Demo C invariant: after the stale-head attempt, the head is unchanged and the
   attempt is :not-admitted with :parent-not-current-head."
  [{:keys [head-before attempt head-after]}]
  (and (= :not-admitted (:admission-status attempt))
       (= :parent-not-current-head (:reason attempt))
       (= head-before head-after)))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def demo-c-invariant
  (admission-cutpoint-holds?
   {:head-before "sha256:R2"
    :attempt stale-head-attempt
    :head-after (chain/current-head demo-chain)}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if demo-c-invariant "#f0fdf4" "#fef2f2")
                :border (str "1px solid " (if demo-c-invariant "#86efac" "#fca5a5"))
                :borderRadius "8px" :padding "12px 16px"
                :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
  "invariant: a stale-head attempt is not admitted and does not move the head — "
  [:strong {:style {:color (if demo-c-invariant "#16a34a" "#dc2626")}}
   (if demo-c-invariant "HOLDS ✓" "VIOLATED ✕")]])

;; ---
;; ## The rejection precedence

;; `transition/apply-action` (admit-child) applies the **pinned rejection
;; precedence** in order — externally observable and locked in tests. Each row
;; below is recomputed in a *fresh* chain so an earlier check cannot mask a
;; later one:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def rejection-scenarios
  [{:label "idempotent replay (same key + same content)"
    :r "sha256:R1x" :seq 1 :parent nil :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")]}
   {:label "idempotency-key, different content"
    :r "sha256:R2" :seq 1 :parent nil :link "sha256:L2" :idem "sha256:I1" :basis "sha256:B2"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")]}
   {:label "duplicate content, same parent"
    :r "sha256:R2" :seq 1 :parent nil :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B1"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")]}
   {:label "transplant — same content, different parent"
    :r "sha256:R3" :seq 2 :parent "sha256:R1" :link "sha256:L3" :idem "sha256:I3" :basis "sha256:B1"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")
            (admit-request :r "sha256:R2" :seq 2 :parent "sha256:R1" :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B2")]}
   {:label "parent not found"
    :r "sha256:R1" :seq 2 :parent "sha256:R999" :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1"
    :steps []}
   {:label "stale head (parent not current-head)"
    :r "sha256:R3" :seq 3 :parent "sha256:R1" :link "sha256:L3" :idem "sha256:I3" :basis "sha256:B3"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")
            (admit-request :r "sha256:R2" :seq 2 :parent "sha256:R1" :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B2")]}
   {:label "sequence gap (5, expected 3)"
    :r "sha256:R3" :seq 5 :parent "sha256:R2" :link "sha256:L3" :idem "sha256:I3" :basis "sha256:B3"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")
            (admit-request :r "sha256:R2" :seq 2 :parent "sha256:R1" :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B2")]}
   {:label "sequence regression (1, expected 3)"
    :r "sha256:R3" :seq 1 :parent "sha256:R2" :link "sha256:L3" :idem "sha256:I3" :basis "sha256:B3"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")
            (admit-request :r "sha256:R2" :seq 2 :parent "sha256:R1" :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B2")]}
   {:label "receipt already committed under another parent"
    :r "sha256:R2" :seq 3 :parent "sha256:R2" :link "sha256:L3" :idem "sha256:I3" :basis "sha256:B3"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")
            (admit-request :r "sha256:R2" :seq 2 :parent "sha256:R1" :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B2")]}
   {:label "commit contention (wrong expected chain version)"
    :r "sha256:R2" :seq 2 :parent "sha256:R1" :link "sha256:L2" :idem "sha256:I2" :basis "sha256:B2"
    :steps [(admit-request :r "sha256:R1" :seq 1 :link "sha256:L1" :idem "sha256:I1" :basis "sha256:B1")]
    :version 999}])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- run-scenario
  "Replay a scenario in a fresh chain and return the final admit! verdict."
  [{:keys [r seq parent link idem basis version steps]}]
  (let [c (fresh-chain)]
    (doseq [req steps] (chain/admit! c req))
    (chain/admit! c (admit-request :r r :seq seq :parent parent :link link
                                   :idem idem :basis basis :version version))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def rejection-verdicts
  (->> rejection-scenarios
       (mapv (fn [s] (assoc (result-row (:label s) (run-scenario s)) :scenario s)))
       (sort-by (juxt :status :label))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Candidate" "Verdict" "Reason"]
  :rows (mapv (fn [v] [(:label v) (:status v) (:reason v)]) rejection-verdicts)})

;; **Reading the reasons** (pinned precedence, §14 / transition.clj):
;;
;; - `submission-already-observed` — idempotent replay of the same attempt.
;; - `idempotency-content-mismatch` — same key now claims different content.
;; - `duplicate-content-submission` — identical content already under this parent.
;; - `idempotency-key-rebound` — a *transplant*: same content under a different parent.
;; - `previous-not-found` — the named parent does not exist in the chain.
;; - `parent-not-current-head` — the parent is real but no longer the tip (Demo C).
;; - `sequence-gap` / `sequence-regression` — the sequence is not parent+1.
;; - `receipt-already-committed` — the receipt hash already has a parent (fork prevention).
;; - `commit-contention` — expected chain version disagrees with the observed store.

;; Two further pinned reasons are **defensive** — reachable only in states a
;; single linear walk cannot produce, so the earlier precedence masks them here:
;; `parent-already-has-successor` (a parent that is simultaneously the head and
;; already consumed) and `cycle-detected` (child == parent, which implies the
;; child is already committed). They are asserted directly in
;; `test/resolver_sim/resubmission/resubmission_test.clj`.

;; ### Invariant: every committed rejection recomputes as not-admitted

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def expected-reasons
  {"idempotent replay (same key + same content)"        :submission-already-observed
   "idempotency-key, different content"                 :idempotency-content-mismatch
   "duplicate content, same parent"                     :duplicate-content-submission
   "transplant — same content, different parent"        :idempotency-key-rebound
   "parent not found"                                   :previous-not-found
   "stale head (parent not current-head)"               :parent-not-current-head
   "sequence gap (5, expected 3)"                       :sequence-gap
   "sequence regression (1, expected 3)"                :sequence-regression
   "receipt already committed under another parent"     :receipt-already-committed
   "commit contention (wrong expected chain version)"   :commit-contention})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def rejections-hold
  (every? (fn [{:keys [label reason]}]
            (= (name (get expected-reasons label)) reason))
          rejection-verdicts))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if rejections-hold "#f0fdf4" "#fef2f2")
                :border (str "1px solid " (if rejections-hold "#86efac" "#fca5a5"))
                :borderRadius "8px" :padding "12px 16px"
                :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
  "invariant: all 10 recomputed rejections carry their pinned reason — "
  [:strong {:style {:color (if rejections-hold "#16a34a" "#dc2626")}}
   (if rejections-hold "HOLDS ✓" "VIOLATED ✕")]])

;; ---
;; ## The gates before the chain: three more first-class NOT-ADMITTED classes
;;
;; The chain cutpoint (§9) is one gate. Three further classes sit before it —
;; recomputed below with the real code so they are first-class, not footnotes:

;; ### A. chain-ingestion — replay actions must carry a committed event-id

;; A chain-ingestion replay is an external-log path: each replay-sensitive
;; action MUST commit an `event-id`, otherwise the run is NOT ADMITTED. The
;; strict flag is `external-log-replay-flags`, not the default.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn chain-ingestion-event-id-required?
  "True when the replay flags are the external-log / chain-ingestion profile,
   which requires event-id on replay-sensitive actions."
  []
  (:require-event-id? rflags/external-log-replay-flags))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Flag source" ":require-event-id?" "Consequence"]
  :rows [["default replay flags" (str (:require-event-id? rflags/default-replay-flags))
          "event-id optional"]
         ["external-log / chain-ingestion flags"
          (str (:require-event-id? rflags/external-log-replay-flags))
          "event-id REQUIRED — an action without one is NOT ADMITTED"]]})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def chain-ingestion-strict
  (chain-ingestion-event-id-required?))

;; ### B. now-gated — researcher key valid now, revoked at the cutpoint

;; Researcher authority is evaluated against the **cutpoint** (the historical
;; state the parent rejection lived in), not against today. A key that is valid
;; *now* but was not valid at the required cutpoint is NOT ADMITTED.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def researcher-link
  {:resubmission/researcher
   {:policy/hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    :key/id "rk-1"}})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn researcher-gate
  "Stage 3a authority check against a pinned policy snapshot.
   `store` is a (policy-hash key-id) -> {:public-hex :status :valid-at-cutpoint}."
  [store]
  (verify/validate-researcher-authority researcher-link store))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Snapshot" "Verdict" "Reason"]
  :rows [["active, valid at cutpoint"
          (name (:reason (researcher-gate (fn [_ _] {:public-hex "x" :status :active :valid-at-cutpoint true}))))
          "admitted on researcher authority"]
         ["active NOW, not valid at cutpoint"
          (name (:reason (researcher-gate (fn [_ _] {:public-hex "x" :status :active :valid-at-cutpoint false}))))
          "NOT ADMITTED — key valid now but not at the cutpoint"]]})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def now-gated-holds
  (= :key-not-valid-at-cutpoint
     (:reason (researcher-gate (fn [_ _] {:public-hex "x" :status :active :valid-at-cutpoint false})))))

;; ### C. resubmission-eligibility — the parent must be marked :eligible

;; A rejected, final parent is a valid resubmission parent only when the
;; validator marked it `:attempt-receipt/resubmission-eligibility :eligible`.
;; `:ineligible` and `:retry-same-attempt` parents are NOT ADMITTED as direct
;; parents.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- parent-receipt
  "A rejected/final/active parent receipt with a given eligibility mark."
  [elig]
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/id "sha256:PARENTPARENTPARENTPARENTPARENTPARENTPARENTPARENTPARENTPARENT"
   :attempt-receipt/submitted-bundle-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :attempt-receipt/roots
   {:research-subject {:root/schema "r" :status :verified :hash "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
    :execution-context {:root/schema "e" :status :verified :hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
    :results {:root/schema "r" :status :verified :hash "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}
    :submission-basis {:root/schema "s" :status :verified :hash "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}}
   :attempt-receipt/results {:status :valid}
   :attempt-receipt/submitter {:status :verified}
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/lifecycle-status :active
   :attempt-receipt/resubmission-eligibility elig
   :attempt-receipt/validator {:policy/hash "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                               :key/id "vk-1"}})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn parent-eligibility-gate
  "The direct-parent requirement the transition enforces before admission:
   nil when the parent is eligible, else the disqualifying reason."
  [receipt]
  (receipt/resubmission-parent-requirement-mismatch receipt))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Parent receipt" "Direct-parent gate" "Admission"]
  :rows (mapv (fn [[label elig]]
                [label
                 (let [m (parent-eligibility-gate (parent-receipt elig))]
                   (if (nil? m) "ok" (name m)))
                 (if (= :eligible elig) "ADMITTED as parent" "NOT ADMITTED as parent")])
              [["rejected + final + :eligible" :eligible]
               ["rejected + final + :ineligible" :ineligible]
               ["rejected + final + :retry-same-attempt" :retry-same-attempt]])})

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def eligibility-holds
  (and (nil? (parent-eligibility-gate (parent-receipt :eligible)))
       (= :parent-not-resubmittable (parent-eligibility-gate (parent-receipt :ineligible)))
       (= :parent-not-resubmittable (parent-eligibility-gate (parent-receipt :retry-same-attempt)))))

;; ### Invariant: all three gates hold

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def gates-hold
  (and chain-ingestion-strict now-gated-holds eligibility-holds))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if gates-hold "#f0fdf4" "#fef2f2")
                :border (str "1px solid " (if gates-hold "#86efac" "#fca5a5"))
                :borderRadius "8px" :padding "12px 16px"
                :fontFamily "monospace" :fontSize "13px" :maxWidth "760px"}}
  "invariant: chain-ingestion requires event-id; now-gated rejects cutpoint-missing keys; "
  "eligibility gates non-:eligible parents — "
  [:strong {:style {:color (if gates-hold "#16a34a" "#dc2626")}}
   (if gates-hold "HOLDS ✓" "VIOLATED ✕")]])

;; ---
;; ## Worked correction — from rejected chain event to one admissible successor
;;
;; The preceding sections isolate individual gates. This example connects them
;; without collapsing their meanings. A dispute-resolution benchmark is rejected
;; because an externally ingested event was bound to the wrong event identifier.
;; The researcher corrects the replay, records the ordered research work, obtains
;; an independent implementation replay, and submits exactly one corrected result.
;;
;; ### 1. Root comparison derives the relationship
;;
;; The parent was rejected for a semantic result mismatch. Its research subject
;; remains the same, while the replay and result roots change. The kind is not a
;; researcher assertion: it is derived from these committed roots.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def correction-parent
  {:roots {:research-subject {:status :verified :hash "sha256:subject-dispute-event-id"}
           :execution-context {:status :verified :hash "sha256:execution-before-event-id-fix"}
           :results {:status :verified :hash "sha256:results-before-event-id-fix"}
           :submission-basis {:status :verified :hash "sha256:basis-before-event-id-fix"}}
   :rejection-classification :result-award-mismatch})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def correction-current
  {:research-subject-hash "sha256:subject-dispute-event-id"
   :execution-context-hash "sha256:execution-after-event-id-fix"
   :results-hash "sha256:results-after-event-id-fix"
   :submission-basis-hash "sha256:basis-after-event-id-fix"})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def correction-kind
  (derive-kind/derive-kind correction-parent correction-current))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Committed dimension" "Parent" "Corrected attempt" "Meaning"]
  :rows [["research subject" "subject-dispute-event-id" "subject-dispute-event-id" "same question"]
         ["execution context" "before-event-id-fix" "after-event-id-fix" "replay corrected"]
         ["results" "before-event-id-fix" "after-event-id-fix" "authoritative result changed"]
         ["derived relationship" "—" (name (:kind correction-kind))
          (name (:reason correction-kind))]]})

;; ### 2. Ordered command provenance records how the correction was produced
;;
;; Here the event normalization command must happen before the allocation check:
;; the latter consumes the former's corrected output. Therefore this is a
;; `research-command-trace.v2` sequence, not a v1 order-arbitrary declaration
;; collection.

;; Scope refs are controlled values. The provenance commands use the applicable
;; analysis scope while their argv retains the concrete operation.
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def correction-analysis-command
  (research-command/build-command
   {:schema-version "research-command.v2"
    :command/id :dispute/check-corrected-allocation
    :command/type :claim-evaluation
    :command/argv ["prf" "benchmark" "evaluate" "--input" "corrected-chain-event"]
    :command/includes [{:kind :research-scope/analysis
                        :ref :research-analysis/incentive}]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def correction-normalization-command
  (research-command/build-command
   {:schema-version "research-command.v2"
    :command/id :dispute/normalize-chain-ingestion-event
    :command/type :evidence-projection
    :command/argv ["prf" "replay" "normalize-external-event" "--require-event-id"]
    :command/includes [{:kind :research-scope/analysis
                        :ref :research-analysis/incentive}]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def correction-trace
  (research-command/build-command-trace-v2
   {:commands [correction-normalization-command correction-analysis-command]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def reversed-correction-trace
  (research-command/build-command-trace-v2
   {:commands [correction-analysis-command correction-normalization-command]}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Trace" "Ordered components" "Root" "Interpretation"]
  :rows [["correction workflow"
          "normalize event → check allocation"
          (subs (:trace/root correction-trace) 0 19)
          "committed dependency order"]
         ["reversed workflow"
          "check allocation → normalize event"
          (subs (:trace/root reversed-correction-trace) 0 19)
          (if (= (:trace/root correction-trace) (:trace/root reversed-correction-trace))
            "BUG: order was lost" "different root — different workflow")]]})

;; ### 3. Independent replay is evidence, not identity substitution
;;
;; The corrected output may be reproduced by another implementation. That is
;; useful evidence, but a changed implementation pin cannot be upgraded into an
;; exact-replication claim.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def corrected-reference
  {:results-artifact-hash "sha256:corrected-results-artifact"
   :input-root "sha256:corrected-input"
   :result-root "sha256:corrected-result-root"
   :pinned-prf {:implementation "prf-allocation-kernel" :version "prf.v2"}
   :pinned-rust {:implementation "official-rust-kernel" :version "rust.v2" :commit "c2"}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- replay-evidence [rust-identity]
  {:native-evidence/schema "native-evidence.v1"
   :native-evidence/kind :exact-replication
   :native-evidence/verifier-version "native-evidence.v1"
   :native-evidence/source :executed
   :native-evidence/results-artifact-hash "sha256:corrected-results-artifact"
   :native-evidence/input-root "sha256:corrected-input"
   :native-evidence/result-root "sha256:corrected-result-root"
   :native-evidence/prf-identity (:pinned-prf corrected-reference)
   :native-evidence/rust-identity rust-identity
   :native-evidence/comparison :match})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Replay implementation" "Classification" "Reason" "Claimable meaning"]
  :rows (mapv (fn [[label rust]]
                (let [result (native-evidence/exact-replication-classification
                              (replay-evidence rust) corrected-reference)]
                  [label (name (:classification result)) (name (:reason result))
                   (if (:proof-backed? result) "exact pinned reproduction"
                       "independent replay only")]))
              [["pinned Rust v2 / c2" (:pinned-rust corrected-reference)]
               ["older Rust v1 / c1" {:implementation "official-rust-kernel"
                                       :version "rust.v1" :commit "c1"}]])})

;; ### 4. The allocation mode determines the proposition being checked
;;
;; A corrected result is never validated by a generic "filled is small" rule.
;; Its committed mode chooses the applicable over-allocation model. The full
;; runtime check lives in `yield/invariants`; this table states the semantic
;; boundary that the resubmission's result evidence must preserve.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Committed mode" "Safety proposition" "A result that still needs a separate proof"]
  :rows [[":single-position" "filled ≤ recoverable; row ≤ requested"
          "cross-invocation source-slice disjointness"]
         [":fcfs-sequential" "every allocation prefix ≤ pool; row ≤ requested"
          "that the greedy FCFS allocator produced this exact order"]
         [":pro-rata" "Σ filled ≤ available; each row ≤ declared cap"
          "fairness / exact proportional reproduction"]]})

;; ### 5. Receipt, disposition, and chain state remain separate layers
;;
;; The corrected attempt can become a successor only after all previous evidence
;; gates succeed. Its immutable receipt records the validator's decision;
;; dispositions are later lifecycle events; the chain is the mutable current-head
;; index. A disposition must be authenticated and linked before it influences
;; eligibility — a status label alone is not authority.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Layer" "Responsible artifact/state" "Question it answers"]
  :rows [["execution provenance" "research-command-trace.v2" "what was run, and in what order?"]
         ["reproduction" "native-evidence.v1" "which pinned implementation reproduced it?"]
         ["attempt authority" "submission-attempt-receipt.v1" "was this rejected/final/eligible/active?"]
         ["lifecycle" "attempt-disposition.v1" "what later status transition is authenticated?"]
         ["admission" "resubmission chain + transaction ordering" "is this the one next successor?"]]})

;; **Outcome of this example:** the correction is a `:corrected-result`, its
;; research workflow has an order-sensitive provenance root, independent replay
;; is not mislabelled as exact replication, and only an eligible active parent at
;; the current head can admit it.

;; ---
;; ## Reproduce
;;
;; ```shell
;; # the full resubmission suite (chain, transaction, issuance)
;; clojure -M:test -e "(require 'resolver-sim.resubmission.resubmission-test
;;                                'resolver-sim.resubmission.transaction-test)
;;                     (clojure.test/run-tests
;;                      'resolver-sim.resubmission.resubmission-test
;;                      'resolver-sim.resubmission.transaction-test)"
;; ```
;;
;; - Normative contract: `docs/security/RESUBMISSION.md` §9 (chain admission cutpoint)
;; - Facade: `src/resolver_sim/resubmission/chain.clj`
;; - Transition (semantic authority): `src/resolver_sim/resubmission/transition.clj`
;; - Tests: `test/resolver_sim/resubmission/resubmission_test.clj`,
;;   `test/resolver_sim/resubmission/transaction_test.clj`
;; - Expected: `admit!` returns `:admission-status :admitted|:not-admitted`
;;   with the pinned reason; a `:not-admitted` attempt never consumes the
;;   successor slot and never moves the head.
