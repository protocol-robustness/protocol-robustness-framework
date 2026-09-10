(ns resolver-sim.protocols.sew.financial.lifecycle
  "Insolvency lifecycle state machine (P4) + response decision primitive (P4b).

   LIFECYCLE CONSUMES ASSESSMENTS; IT IS NOT ANOTHER CLASSIFIER OVER RAW
   BALANCES.

       committed world/evidence
                 │
                 ▼
       immutable assessment at tₙ ──────────┐
                 │                          │
       immutable assessment at tₙ₊₁ ────────┼─► lifecycle transition
                 │                          │
                 ▼                          ▼
       response policy ─────────────────► permitted actions (P4b decision)

   Assessment is FACTUAL  : \"at this cutpoint, economic status is :insolvent\".
   Lifecycle is TEMPORAL : \"third consecutive insolvent assessment → episode
                            terminal\".
   Response policy is NORMATIVE : \"terminal insolvency blocks new obligations
                            and permits recovery operations\".

   ARCHITECTURAL RULES
     - No lifecycle transition may override, reinterpret, or mutate the
       underlying assessment. A :terminal episode can have a presently :solvent
       assessment after assets are replenished; whether it exits terminal status
       is a lifecycle-policy question, never a rewrite of the current economic
       fact.
     - The event history is a content-addressed CHAIN: every event commits its
       predecessor (:previous-event-root), its episode identity (:episode/id),
       the states before/after (:episode/before / :episode/after), the event
       time (:event/at), and its own :event-root. verify-chain / reduce-events
       fail CLOSED on any violation (missing, duplicated, reordered, or
       transplanted events; mismatched predecessor/assessment-root/state/time/
       policy; modified :episode/after).
     - Events cannot be spliced across episodes: :episode/id is a deterministic
       genesis identity derived from subject/protocol identity + lifecycle
       policy + first assessment root + contract version.
     - Time is consensus-relevant: :event/at is non-decreasing and is derived
       from / constrained against the assessment's committed cutpoint
       (:assessment/cutpoint-at). Backdated or out-of-order events are rejected.
     - Policy is immutable per episode (v1 rule): :policy-root is fixed by the
       first event; a mid-episode policy change is rejected, never silently
       accepted.
     - :recovering is HISTORICAL: current assessment improved beyond
       insolvency, but the episode's clearance conditions (cure threshold) are
       not yet satisfied. Terminal exit is exceptional and requires an explicit
       :terminal-exit-authorized reason.
     - P4b enforcement is fail-closed on provenance: response-decision verifies
       the chain, derives the lifecycle head, classifies the action against the
        policy, and commits a :response-decision artifact — never a bare
        (case (:lifecycle/state world) ...)."
  (:require [clojure.set :as set]
            [clojure.string :as cstr]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.sequence :as sequence]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]))

(def episode-event-version "insolvency-lifecycle-event.v1")
(def response-decision-version "insolvency-response-decision.v1")

;; Forward references (defined later in this namespace, called from
;; normalize-action).
(declare derived-realized-effects)
(declare normalize-action)
;; ── Content addressing ───────────────────────────────────────────────────────

(defn- sha-256-of
  "Stable sha-256 over an ordered seq of strings."
  [lines]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [line lines]
      (.update md (.getBytes (str line "\n") "UTF-8")))
    (let [sb (StringBuilder.)]
      (doseq [b (.digest md)]
        (.append sb (format "%02x" (bit-and b 0xff))))
      (.toString sb))))

(defn- clean-state
  "A lifecycle state minus its ephemeral bookkeeping (:episode/events chain and
   last-event-root cache). Used for :episode/before / :episode/after snapshots
   and for equality comparisons during verification."
  [state]
  (dissoc state :episode/events :episode/last-event-root))

(defn- canonical-assessment-lines
  "Deterministic serialization of an assessment's stable, economic-relevant fields."
  [assessment]
  (let [dims (:assessment/dimensions assessment {})
        econ (:economic-solvency dims)
        per-token (:per-token econ {})]
    [(str "status:" (:assessment/status assessment))
     (str "reasons:" (pr-str (sort (vec (:assessment/reasons assessment)))))
     (str "ratio:" (:assessment/ratio assessment))
     (str "evidence:" (:evidence/status assessment))
     (str "verification:" (:verification/status assessment))
     (str "accounting:" (get-in dims [:accounting :status]))
     (str "economic-holds?:" (:holds? econ))
     (str "assets:" (:assets econ))
     (str "liabilities:" (:liabilities econ))
     (str "reserved:" (get-in dims [:reserved-coverage :status]))
     (str "liquidity:" (get-in dims [:liquidity :status]))
     (str "cutpoint-at:" (:assessment/cutpoint-at assessment))
     (str "per-token:" (pr-str (sort-by (comp str first) per-token)))
     (str "liability-root:" (get-in assessment [:liability-set :liability-set/root]))]))

(defn assessment-root
  "Content-addressed root of a solvency assessment. Binds the status, reasons,
   ratio, evidence/verification, the four dimensions, the committed cutpoint,
   per-token economics, and the liability artifact root. Two assessments with
   identical roots are economically identical for lifecycle purposes."
  [assessment]
  (sha-256-of (canonical-assessment-lines assessment)))

(defn policy-root
  "Content-addressed root of a lifecycle policy (thresholds + permitted-action
   mapping). A policy change therefore produces different transition events and
   a different episode identity."
  [policy]
  (sha-256-of (map pr-str (sort-by pr-str (flatten (seq policy))))))

(defn lifecycle-state-root
  "Content-addressed root of a lifecycle state (state + episode fields,
   excluding the event chain itself)."
  [state]
  (sha-256-of (map pr-str (sort-by pr-str (seq (clean-state state))))))

;; ── Lifecycle policy ─────────────────────────────────────────────────────────

(defn default-policy
  "Default lifecycle policy (immutable per episode).

     :terminal-after-consecutive-insolvent  N insolvent assessments → :terminal
     :cure-after-consecutive-solvent        M solvent assessments → :healthy
     :allow-exit-from-terminal?             whether :terminal may exit via a
                                            solvent assessment (policy choice —
                                            never a rewrite of the assessment)
     :permitted            per-state action TYPES explicitly allowed
     :effects-permitted    per-state economic EFFECTS allowed. An action is
                            permitted iff its type is in :permitted AND all its
                            effects are in :effects-permitted. This keeps the
                            policy stable as new domain operations are added:
                            a new operation that creates a liability is governed
                            by :liability-creating, not by remembering to add a
                            new action keyword."
  []
  {:terminal-after-consecutive-insolvent 3
   :cure-after-consecutive-solvent 2
   :allow-exit-from-terminal? false
   :permitted
   {:healthy    #{:create-escrow :open-escrow :post-bonds :withdraw :settle :allow-recapitalization}
    :impaired   #{:settle :withdraw :allow-repayments :register-claims}
    :insolvent  #{:settle-only :allow-recapitalization :register-claims :enter-resolution}
    :recovering #{:create-escrow :open-escrow :post-bonds :withdraw :settle :allow-recapitalization}
    :terminal   #{:settle-only :enter-resolution :declare-terminal}}
   :effects-permitted
   {:healthy    #{:asset-inflow :asset-outflow :liability-creating :liability-extinguishing
                  :risk-increasing :risk-reducing :recovery-operation :settlement-operation
                  :no-economic-effect}
    :impaired   #{:asset-inflow :asset-outflow :liability-extinguishing
                  :recovery-operation :settlement-operation :no-economic-effect}
    :insolvent  #{:asset-inflow :settlement-operation :recovery-operation :no-economic-effect}
    :recovering #{:asset-inflow :asset-outflow :liability-extinguishing
                  :recovery-operation :settlement-operation :no-economic-effect}
    :terminal   #{:settlement-operation :recovery-operation :no-economic-effect}}})

(defn permitted-actions
  "Action TYPES the response policy explicitly permits in a given lifecycle state."
  [policy state]
  (get-in policy [:permitted state] #{}))

(defn permitted-effects
  "Economic EFFECTS the response policy permits in a given lifecycle state."
  [policy state]
  (get-in policy [:effects-permitted state] #{}))

;; ── Lifecycle state ──────────────────────────────────────────────────────────

(defn initial-state
  "Initial lifecycle state (healthy, no episode)."
  []
  {:lifecycle/state :healthy
   :episode/id nil
   :episode/root nil
   :episode/onset-at nil
   :episode/last-healthy-at nil
   :episode/consecutive-assessments 0
   :episode/consecutive-insolvent 0
   :episode/consecutive-solvent 0
   :episode/max-shortfall-by-token {}
   :episode/recovery-at nil
   :episode/terminal-reason nil
   :episode/terminal-exit-reason nil
   :episode/events []})

(defn- economic-signal
  "Map an assessment status to the lifecycle's economic signal.
   :unassessable / :assessment-invalid are :indeterminate — they carry no
   economic fact and therefore cannot change the lifecycle state's economic
   meaning (recorded as a reason instead)."
  [assessment]
  (case (:assessment/status assessment)
    :solvent :covered
    :impaired :impaired
    :insolvent :insolvent
    :indeterminate))

(defn- shortfall-by-token
  "Per-token current shortfall (max 0, liabilities − assets) from the
   assessment's economic dimension. Deliberately per-token: no synthetic
   cross-token aggregation, matching economic-solvency?."
  [assessment]
  (let [rows (get-in assessment [:assessment/dimensions :economic-solvency :per-token] {})]
    (into {}
          (keep (fn [[tok r]]
                  (let [d (- (long (:liabilities r 0)) (long (:assets r 0)))]
                    (when (pos? d) [tok d]))))
          rows)))

;; ── Transition (pure) ────────────────────────────────────────────────────────

(defn transition
  "Compute the next lifecycle state given the previous state, the current
   immutable assessment, a lifecycle policy, and an `:at` timestamp.

   The assessment is READ ONLY — it is never mutated or reinterpreted.
   :recovering is historical: it means the current assessment has improved
   beyond insolvency but the episode's clearance conditions (cure threshold)
   are not yet satisfied. Terminal exit is exceptional: it requires
   :allow-exit-from-terminal? and is reported as :terminal-exit-authorized.

   Returns:
     {:next-lifecycle-state keyword
      :episode {...updated episode fields, :lifecycle/state set to next}
      :transition-reasons   #{...}}"
  [prev-state assessment policy at]
  (let [signal (economic-signal assessment)
        shortfalls (shortfall-by-token assessment)
        term-threshold (get-in policy [:terminal-after-consecutive-insolvent] 3)
        cure-threshold (get-in policy [:cure-after-consecutive-solvent] 2)
        allow-exit? (get-in policy [:allow-exit-from-terminal?] false)
        prev-state-kw (:lifecycle/state prev-state)
        prev-insolvent (:episode/consecutive-insolvent prev-state 0)
        prev-solvent (:episode/consecutive-solvent prev-state 0)
        prev-onset (:episode/onset-at prev-state)
        prev-recovery (:episode/recovery-at prev-state)
        prev-max-shortfall (:episode/max-shortfall-by-token prev-state {})
        assessments (inc (:episode/consecutive-assessments prev-state 0))
        max-shortfalls (merge-with max prev-max-shortfall shortfalls)]
    (case signal
      :indeterminate
      {:next-lifecycle-state prev-state-kw
       :episode (assoc prev-state
                       :episode/consecutive-assessments assessments)
       :transition-reasons #{(if (= :assessment-invalid (:assessment/status assessment))
                               :assessment-invalid
                               :unassessable)}}

      :insolvent
      (let [consec-insolvent (inc prev-insolvent)
            terminal? (>= consec-insolvent term-threshold)
            next-kw (if terminal? :terminal :insolvent)]
        {:next-lifecycle-state next-kw
         :episode (-> prev-state
                      (assoc :lifecycle/state next-kw)
                      (assoc :episode/consecutive-insolvent consec-insolvent)
                      (assoc :episode/consecutive-solvent 0)
                      (assoc :episode/onset-at (or prev-onset at))
                      (assoc :episode/max-shortfall-by-token max-shortfalls)
                      (assoc :episode/consecutive-assessments assessments)
                      (cond-> terminal?
                        (assoc :episode/terminal-reason :economic-insolvency-persisted)))
         :transition-reasons (cond-> #{:economic-insolvency}
                               terminal? (conj :terminal-declared))})

      :impaired
      {:next-lifecycle-state :impaired
       :episode (-> prev-state
                    (assoc :lifecycle/state :impaired)
                    (assoc :episode/consecutive-insolvent 0)
                    (assoc :episode/consecutive-solvent 0)
                    (assoc :episode/onset-at (or prev-onset at))
                    (assoc :episode/max-shortfall-by-token max-shortfalls)
                    (assoc :episode/consecutive-assessments assessments))
       :transition-reasons #{:impairment}}

      :covered
      (let [consec-solvent (inc prev-solvent)
            in-episode? (#{:insolvent :impaired :recovering} prev-state-kw)
            terminal-exit? (and (= :terminal prev-state-kw) allow-exit?)
            recovering? (or in-episode? terminal-exit?)
            cured? (and recovering? (>= consec-solvent cure-threshold))
            next-kw (cond
                      (= :terminal prev-state-kw) (if terminal-exit? :recovering :terminal)
                      cured? :healthy
                      recovering? :recovering
                      :else :healthy)]
        {:next-lifecycle-state next-kw
         :episode (-> prev-state
                      (assoc :lifecycle/state next-kw)
                      (assoc :episode/consecutive-insolvent 0)
                      (assoc :episode/consecutive-solvent consec-solvent)
                      (assoc :episode/last-healthy-at at)
                      (assoc :episode/max-shortfall-by-token max-shortfalls)
                      (assoc :episode/consecutive-assessments assessments)
                      (cond-> (and recovering? (nil? prev-recovery))
                        (assoc :episode/recovery-at at))
                      (cond-> (and (= prev-state-kw :terminal) terminal-exit?)
                        (assoc :episode/terminal-reason nil))
                      (cond-> terminal-exit?
                        (assoc :episode/terminal-exit-reason :policy-authorized)))
         :transition-reasons (cond-> #{:covered}
                               terminal-exit? (conj :terminal-exit-authorized)
                               recovering? (conj :recovery-started)
                               cured? (conj :cured))}))))

;; ── Episode identity ─────────────────────────────────────────────────────────

(defn genesis-episode-id
  "Deterministic genesis identity of an episode:

     sha-256(version | subject | policy-root | first-assessment-root)

   Events cannot be spliced between episodes even if assessment contents
   coincide: a different subject, policy, or first assessment produces a
   different :episode/id."
  [subject policy-root first-assessment-root]
  (sha-256-of [(str "episode-genesis:" episode-event-version)
               (str "subject:" subject)
               (str "policy-root:" policy-root)
               (str "first-assessment-root:" first-assessment-root)]))

;; ── Content-addressed chain events ──────────────────────────────────────────

(defn build-event
  "Build a content-addressed chain event. Every field below (including
   :previous-event-root, :episode/id, :episode/before, :episode/after,
   :event/at and :policy-root) is committed in :event-root, so any modification
   or any change of predecessor / episode / time / policy is detectable."
  [{:keys [subject policy-root previous-event-root episode-id
           previous-assessment-root current-assessment-root
           previous-lifecycle-state next-lifecycle-state
           transition-reasons event-at episode-before episode-after]}]
  (let [preimage [(str "episode-event/version:" episode-event-version)
                  (str "episode/id:" episode-id)
                  (str "subject:" subject)
                  (str "previous-event-root:" (if (= :genesis previous-event-root) "genesis" previous-event-root))
                  (str "previous-assessment-root:" previous-assessment-root)
                  (str "current-assessment-root:" current-assessment-root)
                  (str "previous-lifecycle-state:" (name previous-lifecycle-state))
                  (str "next-lifecycle-state:" (name next-lifecycle-state))
                  (str "transition-reasons:" (pr-str (sort (vec transition-reasons))))
                  (str "policy-root:" policy-root)
                  (str "event/at:" event-at)
                  (str "episode/before:" (pr-str (sort-by pr-str (seq episode-before))))
                  (str "episode/after:" (pr-str (sort-by pr-str (seq episode-after))))]]
    {:episode-event/version episode-event-version
     :episode/id episode-id
     :episode/subject subject
     :previous-event-root previous-event-root
     :previous-assessment-root previous-assessment-root
     :current-assessment-root current-assessment-root
     :previous-lifecycle-state previous-lifecycle-state
     :next-lifecycle-state next-lifecycle-state
     :transition-reasons transition-reasons
     :policy-root policy-root
     :event/at event-at
     :episode/before episode-before
     :episode/after episode-after
     :event-root (sha-256-of preimage)}))

(defn- recompute-event-root
  "Recompute an event's :event-root from its own committed fields (ignoring the
   stored :event-root). Two events are identical iff recompute matches."
  [e]
  (let [preimage [(str "episode-event/version:" (:episode-event/version e))
                  (str "episode/id:" (:episode/id e))
                  (str "subject:" (:episode/subject e))
                  (str "previous-event-root:" (if (= :genesis (:previous-event-root e)) "genesis" (:previous-event-root e)))
                  (str "previous-assessment-root:" (:previous-assessment-root e))
                  (str "current-assessment-root:" (:current-assessment-root e))
                  (str "previous-lifecycle-state:" (name (:previous-lifecycle-state e)))
                  (str "next-lifecycle-state:" (name (:next-lifecycle-state e)))
                  (str "transition-reasons:" (pr-str (sort (vec (:transition-reasons e)))))
                  (str "policy-root:" (:policy-root e))
                  (str "event/at:" (:event/at e))
                  (str "episode/before:" (pr-str (sort-by pr-str (seq (:episode/before e)))))
                  (str "episode/after:" (pr-str (sort-by pr-str (seq (:episode/after e)))))]]
    (sha-256-of preimage)))

;; ── Low-level step (tests / non-authoritative) ───────────────────────────────

(defn apply-transition
  "Low-level lifecycle step: compute the transition from a previous immutable
   assessment to a current immutable assessment, build a content-addressed
   chain event, and append it. Returns the next lifecycle state.

   NOTE: this is NOT the authority boundary — it trusts the caller-supplied
   prev-state. The authoritative path is apply-verified, which derives
   prev-state from a verified event chain."
  ([prev-state prev-assessment current-assessment]
   (apply-transition prev-state prev-assessment current-assessment {}))
  ([prev-state prev-assessment current-assessment {:keys [at policy subject]}]
   (let [policy (or policy (default-policy))
         prev-events (:episode/events prev-state)
         first? (empty? prev-events)
         cur-root (assessment-root current-assessment)
         prev-root (assessment-root prev-assessment)
         p-root (policy-root policy)
         eid (if first?
               (genesis-episode-id (or subject :unspecified) p-root cur-root)
               (:episode/id prev-state))
         prev-event-root (if first? :genesis (-> prev-events peek :event-root))
         at* (or at (:assessment/cutpoint-at current-assessment))
         t (transition prev-state current-assessment policy at*)
         episode-after (-> (:episode t)
                           (assoc :episode/id eid)
                           (assoc :episode/root eid))
         event (build-event
                {:subject (or subject :unspecified)
                 :policy-root p-root
                 :previous-event-root prev-event-root
                 :episode-id eid
                 :previous-assessment-root prev-root
                 :current-assessment-root cur-root
                 :previous-lifecycle-state (:lifecycle/state prev-state)
                 :next-lifecycle-state (:next-lifecycle-state t)
                 :transition-reasons (:transition-reasons t)
                 :event-at at*
                 :episode-before (clean-state prev-state)
                 :episode-after (clean-state episode-after)})]
     (update episode-after :episode/events conj event))))

;; ── Chain verification (fail-closed) ─────────────────────────────────────────

(defn verify-chain
  "Fail-closed chain integrity check. Never returns a state on an invalid chain;
   returns {:valid? bool :error kw :index n}. Checks, in order:
     - first event carries the :genesis predecessor marker
     - event[n].previous-event-root == event[n-1].event-root  (predecessor binding)
     - event[n].previous-assessment-root == event[n-1].current-assessment-root
     - event[n].previous-lifecycle-state == event[n-1].next-lifecycle-state
     - :episode/id identical across the chain (no transplanted events)
     - :policy-root identical across the chain (immutable per episode)
     - :event/at non-decreasing (temporal monotonicity)
     - event[n].event-root recomputes from its committed fields"
  [events]
  (if (empty? events)
    {:valid? true}
    (let [episode-id (-> events first :episode/id)
          policy-root (-> events first :policy-root)]
      (loop [i 0, prev nil, prev-at nil]
        (let [e (nth events i)]
          (cond
            (and (zero? i) (not= :genesis (:previous-event-root e)))
            {:valid? false :error :missing-genesis :index i}

            (and (pos? i) (not= (:event-root prev) (:previous-event-root e)))
            {:valid? false :error :predecessor-root-mismatch :index i}

            (and (pos? i) (not= (:current-assessment-root prev) (:previous-assessment-root e)))
            {:valid? false :error :assessment-root-mismatch :index i}

            (and (pos? i) (not= (:next-lifecycle-state prev) (:previous-lifecycle-state e)))
            {:valid? false :error :lifecycle-state-mismatch :index i}

            (not= episode-id (:episode/id e))
            {:valid? false :error :episode-id-mismatch :index i}

            (not= policy-root (:policy-root e))
            {:valid? false :error :policy-root-mismatch :index i}

            (and prev-at (> prev-at (long (or (:event/at e) 0))))
            {:valid? false :error :time-not-monotonic :index i}

            (not= (:event-root e) (recompute-event-root e))
            {:valid? false :error :event-root-mismatch :index i}

            (= (inc i) (count events))
            {:valid? true :tail-root (:event-root e)}

            :else
            (recur (inc i) e (long (or (:event/at e) prev-at)))))))))

(defn reduce-events
  "FAIL-CLOSED reduction over the event chain.

   Verifies the chain (verify-chain) AND that the supplied initial state matches
   event[0].episode/before and each subsequent event's :episode/before matches
   the state reconstructed from its predecessor. Never returns a state on an
   invalid chain or an inconsistent initial state. The reconstructed state
   carries :episode/events (the verified chain) and :episode/last-event-root.

   Returns {:valid? true :state <reconstructed state incl. events>}
        or {:valid? false :error kw :index n}."
  [initial events]
  (let [chain (verify-chain events)]
    (if-not (:valid? chain)
      chain
      (loop [state initial, evs events, i 0, done []]
        (if (empty? evs)
          {:valid? true :state state}
          (let [e (first evs)]
            (if (not= (clean-state state) (:episode/before e))
              {:valid? false :error :state-before-mismatch :index i}
              (let [after (merge (clean-state state) (:episode/after e))
                    with-chain (-> after
                                   (assoc :episode/events (conj done e))
                                   (assoc :episode/last-event-root (:event-root e)))]
                (recur with-chain (rest evs) (inc i) (conj done e))))))))))

;; ── Authoritative apply (chain-derived, never caller-supplied prev-state) ────

(defn apply-verified
  "AUTHORITATIVE lifecycle step.

   Verifies the event chain, DERIVES the previous lifecycle state from it (the
   caller-supplied prev-state is not an authority boundary), enforces temporal
   monotonicity and policy continuity, then appends the transition event for
   current-assessment.

   Returns {:ok? true :state <next state> :event <event>}
        or {:ok? false :error kw :index n}."
  [subject chain current-assessment {:keys [at policy]}]
  (let [policy (or policy (default-policy))
        reduced (reduce-events (initial-state) chain)]
    (if-not (:valid? reduced)
      {:ok? false :error (:error reduced) :index (:index reduced)}
      (let [prev-state (:state reduced)
            cur-root (assessment-root current-assessment)
            prev-root (if (empty? chain) :genesis (-> chain peek :current-assessment-root))
            p-root (policy-root policy)
            p-root-consistent? (or (empty? chain) (= p-root (-> chain peek :policy-root)))
            prev-at (some-> chain peek :event/at)
            at* (or at (:assessment/cutpoint-at current-assessment))
            time-ok? (or (nil? prev-at) (>= (long at*) (long prev-at)))]
        (cond
          (not p-root-consistent?)
          {:ok? false :error :policy-root-mismatch :index (count chain)}

          (not time-ok?)
          {:ok? false :error :time-not-monotonic :index (count chain)}

          :else
          (let [t (transition prev-state current-assessment policy at*)
                eid (if (empty? chain)
                      (genesis-episode-id subject p-root cur-root)
                      (-> chain first :episode/id))
                episode-after (-> (:episode t)
                                  (assoc :episode/id eid)
                                  (assoc :episode/root eid))
                event (build-event
                       {:subject subject
                        :policy-root p-root
                        :previous-event-root (if (empty? chain) :genesis (-> chain peek :event-root))
                        :episode-id eid
                        :previous-assessment-root prev-root
                        :current-assessment-root cur-root
                        :previous-lifecycle-state (:lifecycle/state prev-state)
                        :next-lifecycle-state (:next-lifecycle-state t)
                        :transition-reasons (:transition-reasons t)
                        :event-at at*
                        :episode-before (clean-state prev-state)
                        :episode-after (clean-state episode-after)})
                new-state (update episode-after :episode/events conj event)]
            {:ok? true :state new-state :event event}))))))

;; ── P4b action classification ────────────────────────────────────────────────

(def action-effect-vocabulary
  "Stable economic-effect vocabulary used to drive insolvency policy. New domain
   operations are governed by their effects, not by remembering to add a new
   action keyword to every rule.

   NAME-SEMANTICS NOTE (documented distinction, pending a public-terminology
   split): the realized :asset-inflow / :asset-outflow effects currently mean a
   NET-POSITION change (Δ assets − Δ liabilities, i.e. economic headroom), NOT
   raw asset movement — create_escrow (assets +1000 AND liabilities +1000) is
   not an :asset-outflow and realizes no :asset-inflow, yet its raw
   :asset-delta is +1000. Raw movement is always visible in
   :transition/economic-deltas (:asset-delta, :custody-delta). Once this
   vocabulary is public/stable, these two names should split into raw
   :asset-inflow/:asset-outflow ← asset-delta and
   :headroom-increasing/:headroom-decreasing ← economic-headroom-delta."
  #{:asset-inflow
    :asset-outflow
    :liability-creating
    :liability-extinguishing
    :risk-increasing
    :risk-reducing
    :recovery-operation
    :settlement-operation
    :headroom-increasing
    :headroom-decreasing
    :no-economic-effect})

(defn valid-effect-declaration?
  "A declared effect set is valid iff:
     - every effect is in the vocabulary, AND
     - :no-economic-effect is EXCLUSIVE (it may not co-occur with any other
       effect: #{:no-economic-effect :asset-outflow} is invalid)."
  [declared-effects]
  (let [declared (set declared-effects)]
    (and (clojure.set/subset? declared action-effect-vocabulary)
         (not (and (contains? declared :no-economic-effect)
                   (> (count declared) 1))))))

(defn classify-action-effects
  "Classify a known action type's economic effects. Unknown types default to
   :no-economic-effect + the caller may supply explicit :action/effects instead.

   create-escrow is deliberately NOT :asset-outflow: the funds remain controlled
   by the protocol (they transform from unencumbered assets into assets backing
   a new escrow obligation) — the insolvency concern is new liability / new
   risk, not immediate depletion."
  [action-type]
  (case action-type
    (:create-escrow :open-escrow) #{:liability-creating}
    :post-bonds                   #{:liability-creating}
    :withdraw                     #{:asset-outflow}
    :settle                       #{:settlement-operation :liability-extinguishing}
    :settle-only                  #{:settlement-operation :liability-extinguishing}
    :allow-repayments             #{:recovery-operation :liability-extinguishing}
    :register-claims              #{:recovery-operation}
    :allow-recapitalization       #{:recovery-operation :asset-inflow}
    :enter-resolution             #{:recovery-operation}
    :declare-terminal             #{:recovery-operation}
    :no-economic-effect           #{}))

(defn- resolver-attribute
  "Resolver provenance for an action's params. A CUSTOM resolver introduces a
   materially different risk profile from the protocol's canonical resolver, so
   it is carried as a separate classified attribute rather than folded into the
   (deliberately stable and economic) effects vocabulary."
  [raw-params]
  (if-let [addr (or (:custom-resolver raw-params) (:resolver raw-params))]
    {:resolver/type :custom
     :resolver/addr addr
     :resolver/root (sha-256-of [(str "resolver:" addr)])}
    {:resolver/type :canonical}))

(defn- canonical-action-params
  "Canonical, protocol-shaped parameters for a known action type. Reads BOTH the
   scenario keys (:to, :custom-resolver) and the canonical keys (:beneficiary,
   :resolver) so normalization is a FIXED POINT — re-normalizing an already
   canonical action preserves its parameters instead of dropping them.

   CONFLICTING ALIAS SPELLINGS FAIL CLOSED: when both spellings are present and
   UNEQUAL the action is REJECTED (never resolved by silent precedence), so two
   materially different inputs cannot collapse according to implementation
   ordering. Only-legacy, only-canonical, and both-equal all normalize.

   Unknown types pass raw params through (sorted deterministically at hashing
   time)."
  [action-type raw-params]
  (case action-type
    :create-escrow
    (let [legacy-to (:to raw-params)
          canonical-beneficiary (:beneficiary raw-params)
          legacy-resolver (:custom-resolver raw-params)
          canonical-resolver (:resolver raw-params)]
      (when (and legacy-to canonical-beneficiary (not= legacy-to canonical-beneficiary))
        (throw (ex-info "conflicting :to / :beneficiary spellings"
                        {:to legacy-to :beneficiary canonical-beneficiary})))
      (when (and legacy-resolver canonical-resolver (not= legacy-resolver canonical-resolver))
        (throw (ex-info "conflicting :custom-resolver / :resolver spellings"
                        {:custom-resolver legacy-resolver :resolver canonical-resolver})))
      {:token (:token raw-params)
       :beneficiary (or canonical-beneficiary legacy-to)
       :amount (long (or (:amount raw-params) 0))
       :resolver (or canonical-resolver legacy-resolver)})
    (or raw-params {})))

(defn- canonical-action-type
  "Canonicalize an action type keyword (snake_case scenario names → kebab-case
   internal vocabulary): \"create_escrow\" → :create-escrow."
  [x]
  (keyword (cstr/replace (name (if (keyword? x) x (keyword (str x)))) "_" "-")))

;; ── Compound action (canonical consecutive sequence of canonical members) ────

(def compound-action-schema :prf/compound-action.v1)
(def compound-member-schema :prf/compound-action-member.v1)
(def compound-lineage-schema :prf/compound-action-lineage.v1)
(def compound-sequence-purpose :sew-compound-action/members)

(defn compound-action?
  "True when `action` is a compound action input (type :action/compound).
   A compound is ONE canonical action whose identity commits, through the
   generic canonical consecutive-sequence machinery, to an ordered set of
   canonical request/action member bindings plus all-or-nothing lifecycle
   semantics.

   The type token is compared EXACTLY — canonical-action-type cannot be used
   because (name :action/compound) strips the namespace to :compound."
  [action]
  (and (map? action)
       (contains? #{:action/compound "action/compound" "action_compound"}
                  (or (:action/type action) (:action action)))))

(defn request-id-root
  "Canonical root of a single member request identity. Member requests are
   committed as roots (not inline content), matching the request-hash
   convention that identity, not representation, is bound."
  [request-id]
  (hc/domain-hash :sew-request-id-v1 {:request/id (or request-id :unbound)}))

(defn compound-action-member
  "Canonicalize ONE compound member binding: exactly one position
   (:compound-member/index), one request identity (:compound-member/request-root),
   and one action identity (:compound-member/action-root). The member root
   commits the POSITIONED PAIRING, so two identical request/action sequences
   cannot be re-paired differently without changing the commitment."
  [{:keys [index request-root action-root]}]
  (let [binding {:compound-member/schema compound-member-schema
                 :compound-member/index index
                 :compound-member/request-root request-root
                 :compound-member/action-root action-root}]
    (assoc binding :compound-member/root
           (hc/domain-hash :sew-compound-action-member-v1 binding))))

(defn compound-member-sequence-root
  "Ordered sequence commitment over canonical member roots:
   consecutive(member-root₀, member-root₁, …, member-rootₙ₋₁).

   Reuses the generic canonical consecutive-sequence machinery
   (resolver-sim.hash.sequence); the purpose binds the commitment to lifecycle
   compound membership. The result is \"these exact members in this exact
   order\", independent of whether they executed."
  [members]
  (sequence/sequence-hash {:purpose compound-sequence-purpose
                           :expected-component-count (count members)}
                          (mapv :compound-member/root members)))

(defn- validate-member-indices!
  [members]
  (let [indices (mapv :compound-member/index members)]
    (when-not (= (range (count members)) indices)
      (throw (ex-info "compound members must carry contiguous indices 0..n-1"
                      {:indices indices})))))

(defn build-compound-action
  "Build a lifecycle compound action from ALREADY-canonicalized member bindings.

     {:action/schema :prf/compound-action.v1
      :action/type :action/compound
      :compound/member-sequence-root <hex>
      :compound/member-count n
      :compound/pre-state-root <hex>
      :compound/atomicity :all-or-nothing
      :action/root <hex>}

   Two distinct identities are preserved:
     - :compound/member-sequence-root  \"these exact members, in this exact order\"
     - :action/root                   \"this sequence under lifecycle authorization
                                        semantics (pre-state, atomicity, schema)\"

   The root projection commits ONLY roots and semantics — member content is
   resolved from the artifact, never duplicated into the preimage."
  [{:keys [members pre-state-root atomicity]}]
  (when-not (and (vector? members) (seq members))
    (throw (ex-info "compound action requires a non-empty vector of members"
                    {:members members})))
  (validate-member-indices! members)
  (let [body {:action/schema compound-action-schema
              :action/type :action/compound
              :compound/member-sequence-root (compound-member-sequence-root members)
              :compound/member-count (count members)
              :compound/pre-state-root pre-state-root
              :compound/atomicity (or atomicity :all-or-nothing)}]
    (assoc body :action/root (hc/domain-hash :sew-compound-action-v1 body))))

(defn- hex-root?
  "A lifecycle commitment root is a bare lowercase 64-hex sha256 digest
   (matching the module's action-root / domain-state-root convention)."
  [v]
  (boolean (and (string? v) (re-matches #"[0-9a-f]{64}" v))))

(defn valid-compound-member?
  "A member binding is valid iff its root recomputes from its committed fields
   and both request/action roots are well-formed 64-hex commitments."
  [binding]
  (and (= compound-member-schema (:compound-member/schema binding))
       (int? (:compound-member/index binding))
       (hex-root? (:compound-member/request-root binding))
       (hex-root? (:compound-member/action-root binding))
       (= (:compound-member/root binding)
          (hc/domain-hash :sew-compound-action-member-v1
                          (dissoc binding :compound-member/root)))))

(defn- normalize-single-action
  "Normalize a SINGLE (non-compound) action into the authorization form:

     {:action/type kw
      :action/params canonical-map
      :action/effects set
      :action/attributes {...}
      :action/root sha-256}

   Accepts:
     - a keyword (type only, effects auto-classified)
     - a map {:action/type ... :action/params ... :action/effects ...}
     - a scenario action {:action \"create_escrow\" :params {...}}

   The action ROOT binds the ECONOMIC identity only (type + canonical params +
   effects + attributes). Execution/context identity — :seq, :time, :agent,
   :request/id, :pre-state/root — is deliberately NOT inside it: that answers
   \"what transition was proposed?\", not \"who tried to execute it, when, and
   against which state?\"."
  [action]
  (let [{:keys [type raw-params effects attributes]}
        (cond
          (keyword? action) {:type (canonical-action-type action) :raw-params {}}
          (map? action) {:type (canonical-action-type (or (:action/type action) (:action action)))
                         :raw-params (or (:action/params action) (:params action) {})
                         :effects (:action/effects action)
                         :attributes (:action/attributes action)}
          :else (throw (ex-info "invalid action" {:action action})))
        params (canonical-action-params type raw-params)
        base-effects (or effects (classify-action-effects type))
        attributes (or attributes (resolver-attribute raw-params))]
    (when-not (valid-effect-declaration? base-effects)
      (throw (ex-info "invalid effect declaration: :no-economic-effect is exclusive and effects must be in the vocabulary"
                      {:action/type type :action/effects base-effects})))
    (let [;; Auto-complete the declaration with derived risk consequences so the
          ;; declared set is always ⊇ what realization will derive: any action
          ;; declaring :liability-creating is understood to be :risk-increasing.
          completed-effects (set/union base-effects (derived-realized-effects base-effects))]
      {:action/type type
       :action/params params
       :action/effects completed-effects
       :action/attributes attributes
       :action/root (hc/domain-hash :sew-action-root
                                    {:action/type type
                                     :action/params params
                                     :action/effects (vec (sort completed-effects))
                                     :action/attributes attributes})})))

(defn- aggregate-member-effects
  "Declared effect union across compound members. :no-economic-effect is a
   PER-MEMBER statement (member i changed nothing) and is dropped from the
   aggregate when any other member has a real economic effect — the compound as
   a whole then has economic effect, so the union must remain a valid
   declaration."
  [members]
  (let [u (apply set/union (map (comp :action/effects :member/action) members))]
    (if (and (contains? u :no-economic-effect) (> (count u) 1))
      (disj u :no-economic-effect)
      u)))

(defn normalize-compound-action
  "Normalize a compound action into the authorization form. Every member's
   request identity and action are canonicalized into a POSITION-BOUND member
   binding, the member roots are consecutive-sequenced through the generic
   canonical sequence machinery, and the compound root commits the sequence +
   pre-state + atomicity.

   Accepts the raw form (member content carried inline) and is a TRUE fixed
   point on its own output (see canonical-action?).

     {:action/type :action/compound
      :compound/members [{:member/request-id \"r1\" :member/action {...}} ...]
      :compound/pre-state-root <hex>
      :compound/atomicity :all-or-nothing}"
  [action]
  (let [raw-members (or (:compound/members action) [])
        pre-state-root (:compound/pre-state-root action)
        atomicity (:compound/atomicity action)]
    (when-not (and (vector? raw-members) (seq raw-members))
      (throw (ex-info "compound action requires a non-empty :compound/members vector"
                      {:action action})))
    (let [members (mapv (fn [index m]
                          (let [action* (normalize-action (:member/action m))
                                req-id (:member/request-id m)]
                            {:member/index index
                             :member/request-id req-id
                             :member/action-root (:action/root action*)
                             :member/action action*}))
                        (range) raw-members)
          bindings (mapv (fn [m]
                           (compound-action-member
                            {:index (:member/index m)
                             :request-root (request-id-root (:member/request-id m))
                             :action-root (:member/action-root m)}))
                         members)
          compound (build-compound-action {:members bindings
                                           :pre-state-root pre-state-root
                                           :atomicity atomicity})]
      (assoc compound
             :compound/members
             (mapv (fn [m b]
                     (assoc m
                            :compound-member/request-root (:compound-member/request-root b)
                            :compound-member/root (:compound-member/root b)))
                   members bindings)
             :action/effects (aggregate-member-effects members)
             :action/attributes {:resolver/type :canonical}))))

(defn normalize-action
  "Normalize an action into the authorization form. Dispatches to the compound
   path for :action/compound inputs and to normalize-single-action otherwise.
   See both docstrings for the exact committed shape."
  [action]
  (if (compound-action? action)
    (normalize-compound-action action)
    (normalize-single-action action)))

(defn action-root
  "Content-addressed root of an EXACT action request (economic identity:
   type + canonical params + effects + attributes). Two economically different
   requests have different roots, so a decision cannot be substituted from
   action A to action B."
  [action]
  (:action/root (normalize-action action)))

(defn canonical-action?
  "True when `action` is ALREADY in canonical normalized form — a TRUE fixed
   point of normalize-action: normalize-action(canonical) == canonical, not
   merely 'eventually stabilizes'."
  [action]
  (and (map? action)
       (contains? action :action/type)
       (= action (normalize-action action))))

(defn domain-state-root
  "Content-addressed root of a domain state (the world/ledger a mutation would
   apply to). Deterministic across equal states."
  [state]
  (sha-256-of [(str "domain-state:" (pr-str (sort-by pr-str (seq (or state {})))))]))

(defn- single-action-findings
  "Policy findings for one (non-compound) normalized action under a lifecycle
   state. Structured as vectors so analytics can discriminate WHICH effect was
   denied:

     permit → #{[:action-type-permitted] [:effect-permitted :liability-creating] ...}
     deny   → #{[:action-type-not-permitted :create-escrow]
                [:effect-not-permitted :liability-creating] ...}"
  [policy state {:keys [action/type action/effects]}]
  (let [type-set (permitted-actions policy state)
        eff-set (permitted-effects policy state)]
    (cond-> #{}
      (contains? type-set type) (conj [:action-type-permitted])
      (not (contains? type-set type)) (conj [:action-type-not-permitted type])
      true (into (for [e (or effects #{})]
                   (if (contains? eff-set e)
                     [:effect-permitted e]
                     [:effect-not-permitted e]))))))

(defn policy-findings
  "Machine-oriented policy findings for an action under a lifecycle state.
   For a single action the findings are the per-type/per-effect tuples above.
   For a COMPOUND action every MEMBER is evaluated individually under the SAME
   lifecycle state; findings are prefixed with the member index:

     [:compound/member 1 :action-type-not-permitted :withdraw]
     [:compound/member 2 :effect-not-permitted :liability-creating]

   A compound is therefore denied iff ANY member is denied — atomicity extends
   to authorization."
  [policy state action]
  (let [n (normalize-action action)]
    (if (= :action/compound (:action/type n))
      (into #{}
            (mapcat (fn [m]
                      (into #{}
                            (map (fn [f] (into [:compound/member (:member/index m)] f)))
                            (single-action-findings policy state (:member/action m))))
                    (:compound/members n)))
      (single-action-findings policy state n))))

(defn action-permitted?
  "Policy decision for an action in a lifecycle state: permitted iff its action
   TYPE is explicitly in :permitted AND all its economic EFFECTS are in
   :effects-permitted. For a compound, EVERY member must be individually
   permitted. Effect-driven so future operations are governed by their economic
   effect rather than an ad hoc keyword list."
  [policy state action]
  (let [findings (policy-findings policy state action)]
    (and (not-any? (fn [f] (some #{:action-type-not-permitted} f)) findings)
         (not-any? (fn [f] (some #{:effect-not-permitted} f)) findings))))

;; ── Effect realization (declared vs observed) ────────────────────────────────

(defn economic-deltas
  "Per-token economic deltas between two domain states (worlds), using the
   canonical liability set and custody/assets:

     {:liability-delta          {token Δ}  raw canonical liability change
      :asset-delta              {token Δ}  raw realizable-asset change
                                          (total-held + claimable-v2)
      :custody-delta            {token Δ}  gross custody change (total-held)
      :economic-headroom-delta  {token Δ}  Δ(asset − liability) = net position}

   assets − liabilities is ECONOMIC HEADROOM/net position, deliberately NOT
   called an asset delta, so P5 metrics such as 'assets lost during episode'
   never count liability creation as asset loss.

   For create_escrow this reports :liability-delta +1000, :asset-delta +1000
   (funds enter realizable custody), :custody-delta +1000, and
   :economic-headroom-delta {} (net position unchanged — the funds transform
   from unencumbered assets into assets backing the new obligation). Never a
   synthetic cross-token value."
  [pre-state post-state]
  (let [liab-fn (fn [w] (:per-token (liab/economic-liability-set (or w {}))))
        custody-fn (fn [w] (:total-held (or w {})))
        assets-fn (fn [w] (liab/custody-assets (or w {})))
        delta (fn [f]
                (let [a (f pre-state)
                      b (f post-state)
                      toks (into (set (keys a)) (keys b))]
                  (into {}
                        (for [t toks
                              :let [d (- (long (get b t 0)) (long (get a t 0)))]
                              :when (not (zero? d))]
                          [t d]))))
        liability-delta (delta liab-fn)
        custody-delta (delta custody-fn)
        asset-delta (delta assets-fn)
        toks (into (set (keys asset-delta)) (keys liability-delta))
        headroom-delta (into {}
                              (for [t toks
                                    :let [d (- (long (get asset-delta t 0))
                                               (long (get liability-delta t 0)))]
                                    :when (not (zero? d))]
                                [t d]))]
    {:liability-delta liability-delta
     :asset-delta asset-delta
     :custody-delta custody-delta
     :economic-headroom-delta headroom-delta}))

(defn primitive-realized-effects
  "PRIMITIVE observed effects — directly evidenced by the economic deltas, with
   NO derived risk semantics:
     :liability-creating      liabilities increased
     :liability-extinguishing liabilities decreased
     :asset-inflow            economic HEADROOM (net position, Δ assets − Δ
                              liabilities) increased
     :asset-outflow           economic HEADROOM decreased
   NOTE: :asset-inflow/:asset-outflow here mean NET-POSITION change, not raw
   asset movement — a custody/asset move exactly matched by a liability move
   (create_escrow) realizes neither. Raw asset movement is reported in
   :transition/economic-deltas :asset-delta/:custody-delta. Pending the
   documented terminology split, the intended names for these two are
   :headroom-increasing / :headroom-decreasing."
  [economic-deltas]
  (let [{:keys [liability-delta economic-headroom-delta]} economic-deltas]
    (cond-> #{}
      (some pos? (vals liability-delta)) (conj :liability-creating)
      (some neg? (vals liability-delta)) (conj :liability-extinguishing)
      (some pos? (vals economic-headroom-delta)) (conj :asset-inflow)
      (some neg? (vals economic-headroom-delta)) (conj :asset-outflow))))

(defn derived-realized-effects
  "DERIVED observed effects — deterministic risk classification over the
   primitives.

   :risk-increasing / :risk-reducing are POLICY RISK/EXPOSURE classifications,
   NOT economic theorems and NOT equivalent to economic-headroom-decreasing /
   -increasing per se. In particular an action that creates obligations without
   worsening headroom (create_escrow) IS :risk-increasing by conservative
   containment policy (more exposure), even though measured solvency headroom
   is unchanged."
  [primitive-effects]
  (cond-> #{}
    (or (contains? primitive-effects :liability-creating)
        (contains? primitive-effects :asset-outflow)) (conj :risk-increasing)
    (contains? primitive-effects :liability-extinguishing) (conj :risk-reducing)))

(defn realized-effects
  "Observed economic effects between two states = primitive + derived.
   Complements the DECLARED/classified effects; the effect contract checks
   that OBSERVED ⊆ DECLARED (an action may not realize an undeclared,
   economically relevant effect)."
  [pre-state post-state]
  (let [deltas (economic-deltas pre-state post-state)
        primitive (primitive-realized-effects deltas)]
    (set/union primitive (derived-realized-effects primitive))))

(def evidenced-effects
  "Effects that ARE derivable from economic deltas and therefore subject to the
   effect-realization contract. Semantic/policy effects (:settlement-operation,
   :recovery-operation, :no-economic-effect) are not delta-evidenced and are
   handled separately."
  #{:liability-creating :liability-extinguishing
    :asset-inflow :asset-outflow
    :risk-increasing :risk-reducing})

(defn effect-contract
  "Two-sided effect reconciliation:

     {:unrealized-declared-effects  (declared ∩ evidenced) − realized
      :undeclared-realized-effects  realized − declared   (FULL set)}

   :undeclared-realized-effects uses the FULL realized set (primitive ∪ derived,
   NO evidenced filter) because derived effects such as :risk-increasing
   participate in policy and must not be able to slip through an intersection.
   The safety contract is OBSERVED ⊆ DECLARED on the full set.

   :unrealized-declared-effects stays scoped to delta-evidenced effects — it is
   an evidence-fidelity diagnostic (an action may legitimately declare a
   semantic effect such as :recovery-operation that has no delta). For an exact
   effect contract both sets must be empty."
  [declared-effects realized-set]
  (let [declared (set declared-effects)
        realized (set realized-set)
        evidenced-declared (set (filter evidenced-effects declared))]
    {:unrealized-declared-effects (set/difference evidenced-declared realized)
     :undeclared-realized-effects (set/difference realized declared)}))

(defn undeclared-realized-effects
  "Observed effects (primitive AND derived) that were NOT declared — the
   enforcement-relevant direction, on the FULL realized set. A non-empty result
   means the classifier lied or the mutation performed more than was authorized."
  [declared-effects realized-set]
  (:undeclared-realized-effects (effect-contract declared-effects realized-set)))

(defn unrealized-declared-effects
  "Declared delta-evidenced effects NOT observed after execution — evidence-
   fidelity gap (the action may be under-delivering on its declared contract)."
  [declared-effects realized-set]
  (:unrealized-declared-effects (effect-contract declared-effects realized-set)))

(defn effect-realized?
  "True iff a declared effect was actually observed after execution."
  [declared-effect realized-set]
  (contains? realized-set declared-effect))

(defn no-economic-effect-violated?
  "Declared :no-economic-effect ⇒ economic deltas must all be zero AND no
   economic realized effect may exist."
  [declared-effects economic-deltas realized-set]
  (let [declared (set declared-effects)]
    (and (contains? declared :no-economic-effect)
         (or (not (every? zero? (concat (vals (:liability-delta economic-deltas))
                                        (vals (:asset-delta economic-deltas))
                                        (vals (:custody-delta economic-deltas)))))
             (seq realized-set)))))

;; ── P4b request commitment ───────────────────────────────────────────────────

(defn request-hash
  "Content-addressed commitment over an execution REQUEST: the exact execution
   identity (:request/id) bound to the economic action it requests (:action/root),
   the :subject, and the :pre-state/root it applies to. Excludes :request/hash
   itself (self-referential exclusion). Mirrors the codebase's
   signed-external-decision request-hash pattern.

   A request with an INVALID or MISSING request-hash is rejected at the
   authorization boundary (:invalid-request); a request that does not match the
   decision's committed hash is rejected (:request-hash-mismatch)."
  [request]
  (sha-256-of [(str "request/id:" (or (:request/id request) :unbound))
               (str "action/root:" (or (:action/root request) :unbound))
               (str "subject:" (or (:subject request) :unbound))
               (str "pre-state/root:" (or (:pre-state/root request) :unbound))]))

(defn attach-request-hash
  "Attach a committed :request/hash to a request map."
  [request]
  (assoc request :request/hash (request-hash request)))

;; ── P4b response decision (authorization artifact) ───────────────────────────

(defn response-decision
  "P4b — the single authoritative answer to 'is this EXACT transition permitted
   under the current lifecycle state?'.

   Fail-closed on provenance AND binds the exact proposed transition, closing
   substitution and staleness:

     - exact action:     :action/root (type + params + effects)
     - exact request:    :request/id (execution identity; single-use or idempotent)
     - exact pre-state:  :pre-state/root (the domain state being mutated)
     - lifecycle head:   :lifecycle-head-root (derived from a verified chain)
     - policy:           :policy-root
     - assessment:       :assessment-root

   :decision is :permit | :deny | :invalid — :invalid means the provenance
   itself is broken (bad chain / policy mismatch), which is operationally
   fail-closed but semantically distinct from a policy denial.

   Returns:
     {:response-decision/version :insolvency-response-decision.v1
      :subject ...
      :action/type ...
      :action/root ...
      :action/effects ...
      :request/id ...
      :pre-state/root ...
      :lifecycle-head-root ...
      :assessment-root ...
      :policy-root ...
      :decision :permit | :deny | :invalid
      :reasons #{...}
      :decision-root ...}"
  ([subject chain current-assessment policy action pre-state]
   (response-decision subject chain current-assessment policy action pre-state {}))
  ([subject chain current-assessment policy action pre-state opts]
   (let [id (or (:request/id opts) :unbound)
         idempotent? (boolean (:idempotent? opts))
         supplied-hash (:request/hash opts)
         reduced (reduce-events (initial-state) chain)
         chain-ok? (:valid? reduced)
         head-state (when chain-ok? (:state reduced))
         head-root (when chain-ok? (lifecycle-state-root head-state))
         assessment-root* (assessment-root current-assessment)
         p-root (policy-root policy)
         p-root-ok? (or (empty? chain) (= p-root (-> chain peek :policy-root)))
         provenance-ok? (and chain-ok? p-root-ok?)
         request-hash* (request-hash {:request/id id
                                      :action/root (action-root action)
                                      :subject subject
                                      :pre-state/root (domain-state-root pre-state)})
         _ (when (and supplied-hash (not= supplied-hash request-hash*))
             (throw (ex-info "request hash mismatch"
                             {:reason :request-hash-mismatch
                              :expected request-hash* :got supplied-hash})))
         n-action (normalize-action action)
         {:keys [action/type action/effects action/attributes]} n-action
         permitted? (and provenance-ok?
                          (action-permitted? policy (:lifecycle/state head-state) action))
         invalid? (not provenance-ok?)
         decision (cond invalid? :invalid
                        permitted? :permit
                        :else :deny)
         reasons (cond-> (policy-findings policy (:lifecycle/state head-state) action)
                   (not chain-ok?) (conj [:invalid-lifecycle-chain])
                   (and chain-ok? (not p-root-ok?)) (conj [:policy-root-mismatch])
                   idempotent? (conj [:idempotent]))
         preimage [(str "response-decision/version:" response-decision-version)
                   (str "subject:" subject)
                   (str "action/type:" (name type))
                   (str "action/root:" (action-root action))
                   (str "action/params:" (pr-str (sort-by pr-str (seq (get n-action :action/params {})))))
                   (str "action/effects:" (pr-str (sort (vec (or effects #{})))))
                   (str "action/attributes:" (pr-str (sort-by pr-str (seq (or attributes {})))))
                   (str "request/id:" (or id :unbound))
                   (str "request/hash:" request-hash*)
                   (str "pre-state/root:" (domain-state-root pre-state))
                   (str "lifecycle-head-root:" (or head-root "invalid"))
                   (str "assessment-root:" assessment-root*)
                   (str "policy-root:" p-root)
                   (str "idempotent?:" idempotent?)
                   (str "decision:" (name decision))
                   (str "reasons:" (pr-str (sort (vec reasons))))]]
     (cond-> {:response-decision/version response-decision-version
              :subject subject
              :action/type type
              :action/params (:action/params n-action)
              :action/root (action-root action)
              :action/effects (or effects #{})
              :action/attributes attributes
              :request/id (or id :unbound)
              :request/hash request-hash*
              :pre-state/root (domain-state-root pre-state)
              :lifecycle-head-root head-root
              :assessment-root assessment-root*
              :policy-root p-root
              :idempotent? (boolean idempotent?)
              :decision decision
              :reasons reasons
              :decision-root (sha-256-of preimage)}
       (= :action/compound type)
       (assoc :action/sequence-root (:compound/member-sequence-root n-action)
              :action/member-count (:compound/member-count n-action))))))

(defn permitted-action?
  "True iff the response decision for the exact action+request+pre-state under
   the current lifecycle state is :permit."
  ([subject chain current-assessment policy action pre-state]
   (= :permit (:decision (response-decision subject chain current-assessment policy action pre-state))))
  ([subject chain current-assessment policy action pre-state opts]
   (= :permit (:decision (response-decision subject chain current-assessment policy action pre-state opts)))))

;; ── P4b enforcement invariant ────────────────────────────────────────────────

(defn decision-authorizes?
  "The enforcement invariant: a :permit is valid ONLY for the exact action root,
   exact request id, exact pre-state root, and exact lifecycle head committed by
   the decision. Rejects substitution (different action / subject / episode /
   policy / pre-state), staleness (newer lifecycle head), and any non-:permit
   decision.

   Args mirror what execution supplies: the decision, the action being executed,
   the request id, the pre-state, the lifecycle head root, and the subject."
  [decision action request-id pre-state lifecycle-head-root subject]
  (and (= :permit (:decision decision))
       (= subject (:subject decision))
       (= (action-root action) (:action/root decision))
       (= request-id (:request/id decision))
       (= (domain-state-root pre-state) (:pre-state/root decision))
       (= lifecycle-head-root (:lifecycle-head-root decision))))

(defn transition-evidence
    "Execution evidence binding a completed transition back to the response
     decision that authorized it, AND exposing the realized economic change so a
     verifier can check the effect contract was honored:

       {:transition/request-root        hash(request-id + action-root)
        :transition/pre-state-root      ...
        :transition/post-state-root     ...
        :transition/economic-deltas     {:liability-delta {token Δ}
                                         :asset-delta {token Δ}   ; net realizable
                                         :custody-delta {token Δ}} ; gross
        :transition/primitive-effects   #{:liability-creating ...}  ; directly evidenced
        :transition/derived-effects     #{:risk-increasing ...}     ; derived risk semantics
        :transition/realized-effects    primitive ∪ derived
        :response-decision/root         ...
        :transition/execution-root      sha-256 over the above}"

    [decision request-id action pre-state post-state]
    (let [request-root (sha-256-of [(str "request/id:" (or request-id :unbound))
                                    (str "action/root:" (action-root action))])
          pre-root (domain-state-root pre-state)
          post-root (domain-state-root post-state)
          deltas (economic-deltas pre-state post-state)
          primitive (primitive-realized-effects deltas)
          derived (derived-realized-effects primitive)
          realized (set/union primitive derived)
          decision-root (:decision-root decision)
          execution-root (sha-256-of [(str "transition/request-root:" request-root)
                                      (str "transition/pre-state-root:" pre-root)
                                      (str "transition/post-state-root:" post-root)
                                      (str "transition/economic-deltas:" (pr-str (sort-by pr-str (seq deltas))))
                                      (str "transition/primitive-effects:" (pr-str (sort (vec primitive))))
                                      (str "transition/derived-effects:" (pr-str (sort (vec derived))))
                                      (str "transition/realized-effects:" (pr-str (sort (vec realized))))
                                      (str "response-decision/root:" (or decision-root :none))])]
      {:transition/request-root request-root
       :transition/pre-state-root pre-root
       :transition/post-state-root post-root
       :transition/economic-deltas deltas
       :transition/primitive-effects primitive
       :transition/derived-effects derived
       :transition/realized-effects realized
       :transition/effects deltas
       :response-decision/root decision-root
       :transition/execution-root execution-root}))

(defn valid-transition-evidence?
  "A transition evidence is VALID only if the effect contract passed:

     - the evidence recomputes from (decision, action, request-id, pre, post)
       (roots consistent), AND
     - it binds this decision (:response-decision/root matches), AND
     - the declared effect contract holds: NO undeclared realized effects
       (realized ⊆ declared, evidenced scope) AND no unrealized declared
       evidenced effects, AND
     - declared :no-economic-effect ⇒ zero economic deltas and no realized effect.

   This makes the resulting transition evidence invalid deterministically when
   the classifier lied or the mutation exceeded what was authorized.

   Returns {:valid? bool :issues #{...}}."
  [decision action request-id pre-state post-state evidence]
  (let [recomputed (transition-evidence decision request-id action pre-state post-state)
        deltas (:transition/economic-deltas evidence)
        realized (:transition/realized-effects evidence)
        declared (:action/effects decision)
        contract (effect-contract declared realized)
         issues (cond-> #{}
                 (not= (:transition/execution-root recomputed)
                       (:transition/execution-root evidence))
                 (conj :evidence-root-mismatch)
                 (not= (:decision-root decision)
                       (:response-decision/root evidence))
                 (conj :decision-root-mismatch)
                 (not (valid-effect-declaration? declared))
                 (conj :invalid-effect-declaration)
                 (seq (:undeclared-realized-effects contract))
                 (conj :undeclared-realized-effects)
                 (seq (:unrealized-declared-effects contract))
                 (conj :unrealized-declared-effects)
                 (no-economic-effect-violated? declared deltas realized)
                 (conj :no-economic-effect-violated))]
    {:valid? (empty? issues) :issues issues}))

(defn authorize-and-execute
  "The CENTRAL mutation gate. Every protected mutation must go through this path —
   it is the only place a response decision authorizes an actual state change.

   Steps (fail-closed):
     1. decision non-nil and :permit, else reject (:no-decision / :decision-denied /
        :decision-invalid)
     2. exact binding via decision-authorizes? (action/request/pre-state/lifecycle-head/subject)
        → rejects substitution, staleness, episode/policy mismatch
     3. single-use: a request/id already consumed is rejected unless the decision
        explicitly committed :idempotent (in which case re-execution of the
        IDENTICAL transition is allowed)
     4. only after 1–3 pass is execute-fn invoked, producing a CANDIDATE post-state
     5. the effect contract is verified against the candidate post-state BEFORE
        commit: any undeclared realized effect (or violated :no-economic-effect
        declaration) makes the transition INVALID evidence and the execution is
        rejected (:effect-contract-violated) — the candidate state is never
        accepted as an authorized transition
     6. execution evidence binds the decision root + request + pre/post-state roots

   Returns {:ok? true :post-state ... :transition ... :consumed-ids ...}
        or {:ok? false :error kw :index n :transition ...}."
  [decision action request-id pre-state lifecycle-head-root subject consumed-ids execute-fn]
  (cond
    (nil? decision)
    {:ok? false :error :no-decision}

    (not (= :permit (:decision decision)))
    {:ok? false :error (if (= :invalid (:decision decision)) :decision-invalid :decision-denied)}

    (not (and (:request/hash decision)
              (= (request-hash {:request/id (or request-id :unbound)
                                :action/root (action-root action)
                                :subject subject
                                :pre-state/root (domain-state-root pre-state)})
                 (:request/hash decision))))
    {:ok? false :error (if (:request/hash decision) :request-hash-mismatch :invalid-request)}

    (not (decision-authorizes? decision action request-id pre-state lifecycle-head-root subject))
    {:ok? false :error :decision-does-not-authorize-request}

    (and (contains? (set consumed-ids) request-id)
         (not (:idempotent? decision)))
    {:ok? false :error :decision-reused}

    :else
    (let [post-state (execute-fn pre-state)
          transition (transition-evidence decision request-id action pre-state post-state)
          validity (valid-transition-evidence? decision action request-id pre-state post-state
                                               transition)]
      (if (:valid? validity)
        {:ok? true
         :post-state post-state
         :transition transition
         :consumed-ids (conj (set consumed-ids) request-id)}
        {:ok? false
         :error :effect-contract-violated
         :transition transition
         :issues (:issues validity)}))))

;; ── Compound execution: consecutive state-transition lineage ────────────────

(defn- apply-member-steps
  "Sequentially apply member actions via member-execute-fn, collecting the
   intermediate states. Because each step's output becomes the next step's
   input, the resulting state sequence is consecutive BY CONSTRUCTION:

     [state₀ state₁ state₂ state₃]   member i: stateᵢ ──actionᵢ──► stateᵢ₊₁

   All-or-nothing: any member execution failure (exception) rejects the whole
   compound and returns no post-state."
  [member-execute-fn pre-state member-actions]
  (loop [remaining (seq member-actions)
         index 0
         state pre-state
         states [pre-state]]
    (if-let [action (first remaining)]
      (let [result (try
                     (let [next-state (member-execute-fn state action)]
                       {:ok? true :next-state next-state})
                     (catch Throwable t
                       {:ok? false :member/index index :message (.getMessage t)}))]
        (if (:ok? result)
          (recur (next remaining) (inc index) (:next-state result)
                 (conj states (:next-state result)))
          {:ok? false
           :error :compound-member-execution-failed
           :member/index index
           :message (:message result)}))
      {:ok? true :states states})))

(defn- member-transitions
  "Per-member economic transitions over the consecutive lineage states. Used for
   per-member effect-contract validation and researcher traceback."
  [member-actions states]
  (mapv (fn [index action [before after]]
          (let [deltas (economic-deltas before after)
                primitive (primitive-realized-effects deltas)
                derived (derived-realized-effects primitive)
                realized (set/union primitive derived)]
            {:member/index index
             :member/action-root (action-root action)
             :member/economic-deltas deltas
             :member/realized-effects (vec (sort realized))}))
        (range) member-actions (partition 2 1 states)))

(defn build-execution-lineage
  "Canonical consecutive execution lineage over a compound execution:

     state₀ --A--> state₁ --B--> state₂ --C--> state₃

   Each step binds the state-before/after ROOTS to the member action root. The
   lineage root commits \"these exact states transitioned consecutively through
   these exact actions\" — it exists ONLY after execution, and it distinguishes
   executions that share the same action sequence/order but reach different
   intermediate states.

   Returns {:lineage/schema ... :lineage/step-count n :lineage/steps [...] :lineage/root <hex>}."
  [member-actions states]
  (let [steps (mapv (fn [index action [before after]]
                      {:step/index index
                       :state-before/root (domain-state-root before)
                       :action/root (action-root action)
                       :state-after/root (domain-state-root after)})
                    (range) member-actions (partition 2 1 states))
        lineage {:lineage/schema compound-lineage-schema
                 :lineage/step-count (count steps)
                 :lineage/steps steps}]
    (assoc lineage :lineage/root
           (hc/domain-hash :sew-compound-action-lineage-v1 lineage))))

(defn valid-execution-lineage?
  "The consecutive-lineage invariant:

     - step[0].state-before/root == the compound's committed pre-state root
     - step[i].state-after/root  == step[i+1].state-before/root  (every i)

   This is the frame-addressable continuity claim a researcher can verify
   independently of the authorization path."
  [lineage pre-state-root]
  (let [steps (:lineage/steps lineage)
        chain-mismatch (vec
                        (keep-indexed (fn [i [left right]]
                                        (when (not= (:state-after/root left)
                                                    (:state-before/root right))
                                          i))
                                      (partition 2 1 steps)))
        head-mismatch (when (and (seq steps)
                                 (not= (:state-before/root (first steps)) pre-state-root))
                        :head-state-root-mismatch)]
    {:consecutive? (and (empty? chain-mismatch) (nil? head-mismatch))
     :chain-mismatch chain-mismatch
     :head-mismatch head-mismatch}))

(defn compound-transition-evidence
  "Execution evidence binding a completed compound transition back to the
   response decision, the committed member sequence, and the consecutive
   execution lineage. After execution the transition binds ALL THREE identities
   — compound action root, action sequence root, execution lineage root — which
   gives a researcher unusually good traceback:

     {:transition/request-root ...
      :transition/pre-state-root ...
      :transition/post-state-root ...
      :transition/action-root          <compound-action-root>
      :transition/action-sequence-root <member-sequence-root>
      :transition/execution-lineage-root <lineage-root>
      :transition/member-transitions   [{:member/index i
                                         :member/economic-deltas ...
                                         :member/realized-effects ...}]
      :transition/economic-deltas      ...aggregate
      :transition/realized-effects     ...aggregate
      :response-decision/root ...
      :transition/execution-root       sha-256 over the above}"
  [decision request-id compound pre-state post-state lineage member-transitions]
  (let [request-root (sha-256-of [(str "request/id:" (or request-id :unbound))
                                  (str "action/root:" (:action/root compound))])
        pre-root (domain-state-root pre-state)
        post-root (domain-state-root post-state)
        deltas (economic-deltas pre-state post-state)
        primitive (primitive-realized-effects deltas)
        derived (derived-realized-effects primitive)
        realized (set/union primitive derived)
        decision-root (:decision-root decision)
        execution-root (sha-256-of
                        [(str "transition/request-root:" request-root)
                         (str "transition/pre-state-root:" pre-root)
                         (str "transition/post-state-root:" post-root)
                         (str "transition/action-root:" (:action/root compound))
                         (str "transition/action-sequence-root:"
                              (:compound/member-sequence-root compound))
                         (str "transition/execution-lineage-root:" (:lineage/root lineage))
                         (str "transition/member-transitions:" (pr-str member-transitions))
                         (str "transition/economic-deltas:" (pr-str (sort-by pr-str (seq deltas))))
                         (str "transition/realized-effects:" (pr-str (sort (vec realized))))
                         (str "response-decision/root:" (or decision-root :none))])]
    {:transition/request-root request-root
     :transition/pre-state-root pre-root
     :transition/post-state-root post-root
     :transition/action-root (:action/root compound)
     :transition/action-sequence-root (:compound/member-sequence-root compound)
     :transition/execution-lineage-root (:lineage/root lineage)
     :transition/member-transitions member-transitions
     :transition/economic-deltas deltas
     :transition/primitive-effects primitive
     :transition/derived-effects derived
     :transition/realized-effects realized
     :response-decision/root decision-root
     :transition/execution-root execution-root}))

(defn valid-compound-transition-evidence?
  "A compound transition evidence is VALID only if:

     - the evidence recomputes from (decision, compound, request-id, pre, post,
       lineage, member-transitions) — roots consistent, AND
     - it binds this decision (:response-decision/root matches), AND
     - it binds the committed member sequence and execution lineage
       (:transition/action-sequence-root, :transition/execution-lineage-root), AND
     - the per-member effect contract holds: each member realizes ONLY its own
       declared effects between its lineage states, AND no member violates a
       declared :no-economic-effect.

   Returns {:valid? bool :issues #{...}}."
  [decision request-id compound pre-state post-state lineage member-transitions evidence]
  (let [recomputed (compound-transition-evidence decision request-id compound pre-state post-state
                                                 lineage member-transitions)
        declared-per-member (mapv (fn [m] (:action/effects (:member/action m)))
                                  (:compound/members compound))
        realized-per-member (mapv :member/realized-effects member-transitions)
        deltas-per-member (mapv :member/economic-deltas member-transitions)
        member-contracts (mapv (fn [declared realized deltas]
                                 (let [contract (effect-contract declared realized)]
                                   {:invalid-declaration? (not (valid-effect-declaration? declared))
                                    :undeclared-realized (:undeclared-realized-effects contract)
                                    :unrealized-declared (:unrealized-declared-effects contract)
                                    :no-economic-effect-violated?
                                    (no-economic-effect-violated? declared deltas realized)}))
                               declared-per-member realized-per-member deltas-per-member)
        issues (cond-> #{}
                 (not= (:transition/execution-root recomputed)
                       (:transition/execution-root evidence))
                 (conj :evidence-root-mismatch)
                 (not= (:decision-root decision)
                       (:response-decision/root evidence))
                 (conj :decision-root-mismatch)
                 (not= (:compound/member-sequence-root compound)
                       (:transition/action-sequence-root evidence))
                 (conj :action-sequence-root-mismatch)
                 (not= (:lineage/root lineage)
                       (:transition/execution-lineage-root evidence))
                 (conj :execution-lineage-root-mismatch)
                 (not (:consecutive? (valid-execution-lineage? lineage
                                                               (:compound/pre-state-root compound))))
                 (conj :execution-lineage-not-consecutive)
                 (some :invalid-declaration? member-contracts)
                 (conj :invalid-effect-declaration)
                 (some seq (map :undeclared-realized member-contracts))
                 (conj :undeclared-realized-effects)
                 (some seq (map :unrealized-declared member-contracts))
                 (conj :unrealized-declared-effects)
                 (some :no-economic-effect-violated? member-contracts)
                 (conj :no-economic-effect-violated))]
    {:valid? (empty? issues) :issues issues}))

(defn authorize-and-execute-compound
  "COMPOUND variant of the central mutation gate. One decision still authorizes
   EXACTLY ONE canonical action root — the compound root — so no special
   multi-root authorization logic is needed: the same fail-closed binding
   (action/request/pre-state/lifecycle-head/subject) and single-use semantics
   apply. What differs is EXECUTION:

     - members are applied SEQUENTIALLY via member-execute-fn
       ([state member-action] -> next-state), all-or-nothing,
     - the committed consecutive lineage (each member's post-state is the next
       member's pre-state) is REQUIRED and enforced,
     - the per-member effect contract is verified against the intermediate
       lineage states BEFORE commit.

   Returns {:ok? true :post-state ... :transition ... :lineage ... :consumed-ids ...}
        or {:ok? false :error kw :member/index n :transition ... :issues ...}.

   consumed-ids consumes the compound request id AND every member request id as
   one unit (:all-or-nothing consumption)."
  [decision compound request-id pre-state lifecycle-head-root subject consumed-ids member-execute-fn]
  (cond
    (nil? decision)
    {:ok? false :error :no-decision}

    (not (= :permit (:decision decision)))
    {:ok? false :error (if (= :invalid (:decision decision)) :decision-invalid :decision-denied)}

    (not (= :action/compound (:action/type (normalize-action compound))))
    {:ok? false :error :compound-required}

    (not (= (:compound/pre-state-root (normalize-action compound))
            (domain-state-root pre-state)))
    {:ok? false :error :compound-pre-state-root-mismatch}

    (not (and (:request/hash decision)
              (= (request-hash {:request/id (or request-id :unbound)
                                :action/root (action-root compound)
                                :subject subject
                                :pre-state/root (domain-state-root pre-state)})
                 (:request/hash decision))))
    {:ok? false :error (if (:request/hash decision) :request-hash-mismatch :invalid-request)}

    (not (decision-authorizes? decision compound request-id pre-state lifecycle-head-root subject))
    {:ok? false :error :decision-does-not-authorize-request}

    (and (contains? (set consumed-ids) request-id)
         (not (:idempotent? decision)))
    {:ok? false :error :decision-reused}

    :else
    (let [n (normalize-action compound)
          members (:compound/members n)
          member-actions (mapv :member/action members)
          applied (apply-member-steps member-execute-fn pre-state member-actions)]
      (if-not (:ok? applied)
        {:ok? false :error (:error applied) :member/index (:member/index applied)
         :message (:message applied)}
        (let [states (:states applied)
              post-state (peek states)
              lineage (build-execution-lineage member-actions states)
              member-transitions (member-transitions member-actions states)
              transition (compound-transition-evidence decision request-id n pre-state post-state
                                                       lineage member-transitions)
              validity (valid-compound-transition-evidence? decision request-id n pre-state post-state
                                                            lineage member-transitions transition)]
          (if (:valid? validity)
            {:ok? true
             :post-state post-state
             :transition transition
             :lineage lineage
             :consumed-ids (into (conj (set consumed-ids) request-id)
                                 (mapv :member/request-id members))}
            {:ok? false
             :error :effect-contract-violated
             :transition transition
             :lineage lineage
             :issues (:issues validity)}))))))

;; ── Compound execution claims (pure derived researcher/auditor queries) ──────

(defn compound-claims
  "Pure derived queries over a compound execution result — the explicit named
   vocabulary for \"the authorized action sequence was executed completely, in
   order, with consecutive state continuity and complete request consumption\".
   NOT a rooted artifact: every claim recomputes from committed evidence, so a
   researcher can assert each one without interpreting raw structure.

     {:compound/membership-valid?        these exact canonical members were committed
      :compound/equal-cardinality?       member-count == lineage steps == member transitions
      :compound/order-valid?             lineage action roots match member action roots in order
      :compound/consecutive?             each member's post-state is the next member's pre-state
      :compound/execution-atomic?        lifecycle emitted all-or-nothing commit material
                                         (NOT durable-store atomicity)
      :compound/consumption-complete?    every member request id was consumed as one unit}

   :compound/execution-atomic? refers ONLY to lifecycle-result atomicity: the
   gate emits either one complete commit-capable payload (all-or-nothing) or
   none. It is NOT evidence of durable-store atomicity — persisting all of the
   commit material transactionally (CAS) is the persistence adapter's
   responsibility, and once a real store exists it should expose its own claim
   (e.g. :durably-committed-atomically?) backed by actual persistence evidence.

   Takes {:keys [compound result]} where compound is the executing compound input
   and result is the authorize-and-execute-compound return value."
  [{:keys [compound result]}]
  (let [n (normalize-action compound)
        members (:compound/members n)
        member-action-roots (mapv :member/action-root members)
        member-request-ids (mapv :member/request-id members)
        bindings (mapv (fn [m]
                         (compound-action-member
                          {:index (:member/index m)
                           :request-root (request-id-root (:member/request-id m))
                           :action-root (:member/action-root m)}))
                       members)
        ok? (boolean (:ok? result))
        lineage (:lineage result)
        lineage-steps (:lineage/steps lineage)
        transition (:transition result)
        consumed (set (:consumed-ids result))
        membership-valid? (and ok?
                               (every? valid-compound-member? bindings)
                               (= (:compound/member-sequence-root n)
                                  (compound-member-sequence-root bindings)))
        equal-cardinality? (and ok?
                                (= (:compound/member-count n)
                                   (count lineage-steps)
                                   (count (:transition/member-transitions transition))))
        order-valid? (and ok?
                          (= member-action-roots (mapv :action/root lineage-steps)))
        consecutive? (and ok?
                          (:consecutive? (valid-execution-lineage? lineage
                                                                   (:compound/pre-state-root n))))
        execution-atomic? (and ok?
                               (= (:compound/member-count n) (count lineage-steps)))
        consumption-complete? (and ok?
                                   (every? consumed member-request-ids))]
    {:compound/membership-valid? membership-valid?
     :compound/equal-cardinality? equal-cardinality?
     :compound/order-valid? order-valid?
     :compound/consecutive? consecutive?
     :compound/execution-atomic? execution-atomic?
     :compound/consumption-complete? consumption-complete?}))
