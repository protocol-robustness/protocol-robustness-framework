(ns resolver-sim.scenario.subgame-counterfactual
  "Bounded subgame counterfactual evaluator (Phase 4 v1).

   Uses deterministic, local counterfactuals around strategic decision nodes.
   This v1 evaluator is intentionally bounded:
   - decision nodes are limited to key strategic actions,
   - alternatives are generated from a small fixed action set,
   - utilities are computed from world snapshots in the same replay trace.

   Output is deterministic and suitable as SPE-proxy evidence.

   ## Phase summary

   Phase A — continuation-policy / replay-boundary / utility-spec defaults
   Phase B — minimal information-set model (observable vs hidden state per node)
   Phase C — epsilon semantics + bounded alternatives with timing/exogenous variants
   Phase D — deterministic response-adjustment + compute-bundle-regret multi-step proxy
   Phase E — in-evaluation memoization keyed by stable tuple; per-row memoization-hit?
   Phase F — per-node :spe/checkability classification (proper-subgame vs information-set-node
              vs not-spe-checkable) + top-level coverage counts
   Phase G — declared strategy profile; :governing-policy per row
   Phase H — rich SPE result vocabulary (:spe/pass, :spe/epsilon-pass, etc.)
   Phase I — structured counterexample maps for every profitable deviation
   Phase J — off-path coverage reporting"
  (:require [clojure.string :as str]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.scenario.reputation-profiles :as rep-profiles]))

(def ^:private default-continuation-policy
  {:mode :trace-following
   :version "v1"
   :invalid-trace-action :mark-inconclusive})

(def ^:private default-replay-boundary
  {:frozen [:pre-state-snapshot :block-time :available-actors :environment-params]
   :variable [:node-action :downstream-actions :state-evolution]
   :ordering-mode :preserve
   :exogenous-events :fixed
   ;; Continuation events inherit :params (including optional event-id) from the
   ;; main-line trace. Deviation events (seq 0) do not receive synthetic ids.
   :event-identity :inherit-from-main-trace
   ;; Fork world checkpoints carry :idempotency/applied only for events already
   ;; processed before the fork point — not for downstream continuations.
   ;; :idempotency-state :reset-at-fork is an alternate mode that clears
   ;; :idempotency/applied at fork (starts with empty dedupe set for all
   ;; continuation events). Not yet implemented — only :inherit-checkpoint
   ;; is wired. Enable when you need to re-evaluate idempotent actions on
   ;; the fork path as if they had never been seen.
   :idempotency-state :inherit-checkpoint})

(def ^:private default-utility-spec
  {:type :terminal-realized-v1
   :version "v1"
   :undefined-policy :inconclusive})

(def ^:private strategic-actions
  "Cross-protocol action-name convention for bounded strategic-node analysis.
   Creation is intentionally excluded because it has no prior subgame state."
  #{"raise-dispute" "escalate-dispute" "execute-resolution"
    "sender-cancel" "recipient-cancel" "appeal-slash" "resolve-appeal"})

(def ^:private action-alternatives
  {"raise_dispute" ["settle_now" "wait"]
   "escalate_dispute" ["settle_now" "wait"]
   "execute_resolution" ["defer_verdict" "alternate_verdict"]
   "sender_cancel" ["raise_dispute" "wait"]
   "recipient_cancel" ["raise_dispute" "wait"]
   "appeal_slash" ["accept_slash" "wait"]
   "resolve_appeal" ["uphold_slash" "reverse_slash"]})

(def ^:private node-type-alternatives
  {:challenge-timing ["challenge_now" "challenge_later" "no_challenge"]
   :escalation-timing ["escalate_now" "escalate_later" "no_escalation"]
   :resolver-verdict ["verdict_for_buyer" "verdict_for_seller" "defer_verdict"]
   :cancel-timing ["raise_dispute" "wait"]
   :appeal-decision ["accept_slash" "appeal_slash" "wait"]
   :governance-ruling ["uphold_slash" "reverse_slash"]})

(def ^:private node-type-by-action
  {"create_escrow" :escrow-creation
   "raise_dispute" :challenge-timing
   "escalate_dispute" :escalation-timing
   "execute_resolution" :resolver-verdict
   "sender_cancel" :cancel-timing
   "recipient_cancel" :cancel-timing
   "appeal_slash" :appeal-decision
   "resolve_appeal" :governance-ruling})

;; ---------------------------------------------------------------------------
;; Phase F — Subgame boundary classification
;; ---------------------------------------------------------------------------

;; Agent roles that operate on private evidence quality or private beliefs —
;; these nodes are information-set nodes, not proper subgames.
(def ^:private private-evidence-roles
  #{"buyer" "claimant"})

(defn- classify-subgame-node
  "Phase F: classify a decision node as :proper-subgame, :information-set-node,
   or :not-spe-checkable.

   :proper-subgame       — all relevant protocol state is public (live-states,
                           dispute-levels, block-time visible); verdict or
                           escalation action from a known public dispute state.
   :information-set-node — the acting agent has private state that materially
                           affects their decision (e.g. buyer's private evidence
                           quality; buyer escalation decisions).
   :not-spe-checkable    — pre-state unavailable or node is not a strategic
                           decision point."
  [node pre-world]
  (let [action (str (:action node))
        agent  (str (:agent node))]
    (cond
      (nil? pre-world)
      {:checkability :not-spe-checkable
       :spe/checkability :not-spe-checkable
       :checkability-reason "pre-state unavailable; cannot evaluate decision context"}

      (not (contains? node-type-by-action action))
      {:checkability :not-spe-checkable
       :spe/checkability :not-spe-checkable
       :checkability-reason "action is not a recognized strategic decision type"}

      ;; Buyer/claimant raise-dispute or escalate-dispute depend on private
      ;; evidence quality — these are information-set nodes.
      (and (contains? private-evidence-roles agent)
           (contains? #{"raise_dispute" "escalate_dispute"} action))
      {:checkability :information-set-node
       :spe/checkability :information-set-node
       :checkability-reason "buyer private evidence quality not modeled; decision is information-set node"}

      :else
      {:checkability :proper-subgame
       :spe/checkability :proper-subgame
       :checkability-reason "protocol state public; strategic action from public state"})))

;; ---------------------------------------------------------------------------
;; Phase G — Strategy profile definition
;; ---------------------------------------------------------------------------

(def ^:private default-strategy-profile
  {:id         "honest-resolution-v1"
   :buyer      :policy/buyer-rational-v1
   :seller     :policy/seller-rational-v1
   :resolver   :policy/resolver-honest-v1
   :governance :policy/governance-no-intervention-v1})

(defn- governing-policy
  "Phase G: return the declared policy for the acting agent in this node,
   derived from the (possibly overridden) strategy profile."
  [agent strategy-profile]
  (let [role (keyword (str agent))]
    (or (get strategy-profile role)
        (get strategy-profile (keyword (str/replace (str agent) #"^:" "")))
        :policy/unknown)))

(defn- get-agent-wealth [world agent-id]
  (let [stakes    (get world :resolver-stakes {})
        claimable (get world :claimable {})
        bonds     (get world :bond-balances {})
        s (get stakes agent-id 0)
        c (reduce + 0 (for [[_ wf] claimable] (get wf agent-id 0)))
        b (reduce + 0 (for [[_ wf] bonds] (get wf agent-id 0)))]
    (+ s c b)))

;; ---------------------------------------------------------------------------
;; Phase K — Backward induction helpers
;; ---------------------------------------------------------------------------

;; Alternative actions that end the current game path for the deviating agent.
;; Taking a terminal action means no downstream subgames apply to that agent.
(def ^:private terminal-deviation-actions
  #{"settle_now" "wait" "no_challenge" "no_escalation"})

(defn- classify-deviation-action
  "Phase K: classify an alternative action as :terminal-deviation or
   :continuation-deviation.
   :terminal-deviation    — agent exits the game path; downstream subgames do not apply.
   :continuation-deviation — agent stays in game with a different action; downstream
                             subgame outcomes still propagate to their utility."
  [alt-action]
  (if (contains? terminal-deviation-actions alt-action)
    :terminal-deviation
    :continuation-deviation))

(defn- build-agent-wealth-table
  "Phase K: walk raw-trace and return {trace-idx → {actor-addr → wealth}}.
   Only actors in the supplied set are tracked. Entries without a :world are skipped."
  [raw-trace actors]
  (into {}
        (keep-indexed (fn [idx entry]
                        (when-let [world (:world entry)]
                          [idx (into {} (map (fn [a] [a (get-agent-wealth world a)]) actors))]))
                      raw-trace)))

(defn- compute-backward-alt-utility
  "Phase K: estimate deviation utility for one alternative action under backward induction.

   :terminal-deviation    → deviation-value = pre-wealth
     (agent exits game path; downstream subgames do not contribute to their utility)
   :continuation-deviation → deviation-value = pre-wealth + downstream-delta, where
     downstream-delta = terminal-wealth − wealth-at-start-of-first-downstream-node.
     Estimates what the actor receives from downstream subgames even if they take a
     different action at this node. Falls back to pre-wealth when no downstream nodes exist.

   All wealth values are longs. Returns 0 when pre-wealth is nil."
  [actor alt-action pre-wealth terminal-wealth wealth-table downstream-seqs]
  (let [pre-w (long (or pre-wealth 0))]
    (case (classify-deviation-action alt-action)
      :terminal-deviation pre-w
      :continuation-deviation
      (if (seq downstream-seqs)
        ;; Use the pre-state of the earliest downstream node (trace-idx = downstream-seq − 1)
        (let [first-ds-seq (apply min downstream-seqs)
              pre-idx      (max 0 (dec (long first-ds-seq)))
              ds-start     (get-in wealth-table [pre-idx actor])]
          (if (some? ds-start)
            (+ pre-w (- (long (or terminal-wealth 0)) (long ds-start)))
            pre-w))
        ;; No downstream nodes: continuation deviation treated like terminal
        pre-w))))

(defn- detect-slash-explicit
  "Returns the incremental slash amount for actor since pre-world using the
   :resolver-slash-total accumulator (preferred — not confused with withdrawals).
   Returns nil when the accumulator key is absent from pre-world (older trace
   format); an empty accumulator map means no prior slashes (= 0)."
  [pre-world terminal-world actor]
  (when (contains? pre-world :resolver-slash-total)
    (let [pre-tot  (get-in pre-world  [:resolver-slash-total actor] 0)
          term-tot (get-in terminal-world [:resolver-slash-total actor] 0)]
      (max 0 (- (long term-tot) (long pre-tot))))))

(defn- detect-slash-stake-delta
  "Fallback slash detection via stake drop >= threshold.
   Risk: false positives from voluntary withdrawals / bond unlocks."
  [pre-world terminal-world actor threshold]
  (let [pre-s  (get-in pre-world  [:resolver-stakes actor] 0)
        term-s (get-in terminal-world [:resolver-stakes actor] 0)
        drop   (max 0 (- (long pre-s) (long term-s)))]
    (when (>= drop (long threshold)) drop)))

(defn- compute-rep-penalty
  "Compute reputation penalty magnitude (token-equivalent future-earnings loss)
   based on :reputation/model in utility-spec. Returns a non-negative long.

   Models:
     :fixed-penalty           (default) — use :reputation-slash-penalty directly.
     :event-penalty           — look up :resolver-slashed in :reputation-event-penalties.
     :expected-future-earnings — compute from routing-probability delta × volume × fee × margin."
  [utility-spec]
  (let [model (keyword (or (:reputation/model utility-spec) :fixed-penalty))]
    (case model
      :fixed-penalty
      (long (or (:reputation-slash-penalty utility-spec) 0))

      :event-penalty
      (long (or (get-in utility-spec [:reputation-event-penalties :resolver-slashed] 0) 0))

      :expected-future-earnings
      ;; penalty = (routing-before − routing-after) × cases × fee × margin × discount
      ;; discount is applied separately in compute-reputation-utility; here we return undiscounted.
      (let [p-before (double (or (:routing-probability-before utility-spec) 0.0))
            p-after  (double (or (:routing-probability-after  utility-spec) 0.0))
            cases    (double (or (:expected-future-cases utility-spec) 0))
            fee      (double (or (:expected-fee-per-case  utility-spec) 0.0))
            margin   (double (or (:resolver-margin        utility-spec) 1.0))]
        (long (Math/round (* (max 0.0 (- p-before p-after)) cases fee margin))))

      ;; Unknown model — treat as zero penalty (safe default, no crash).
      0)))

(defn- compute-reputation-utility
  "Compute :resolver-reputation-v1 utility for actor.

   Accounting invariant: terminal-realized-wealth already includes the stake
   reduction from slashing (resolver-stakes is debited on slash). Therefore,
   the reputation penalty represents ONLY additional future-earnings loss —
   it must NOT subtract the stake loss a second time.

   Slash detection modes (utility-spec :slash-detection-mode):
     :explicit-slash-total (default) — resolver-slash-total accumulator in world state
     :stake-delta                    — stake drop fallback (risk: false positives)

   Penalty models (:reputation/model):
     :fixed-penalty           (default) — :reputation-slash-penalty * discount
     :event-penalty           — reputation-event-penalties[:resolver-slashed] * discount
     :expected-future-earnings — (routing-before - routing-after) * cases * fee * margin * discount

   Returns utility map with :utility-breakdown for transparency. When the
   computed penalty is 0 the result is identical to :terminal-realized-v1."
  [terminal-world actor utility-spec pre-world]
  (let [u-type    (keyword (or (:type utility-spec) :resolver-reputation-v1))
        u-ver     (str (or (:version utility-spec) "v1"))
        penalty   (compute-rep-penalty utility-spec)
        discount  (double (or (:reputation-discount-rate utility-spec) 1.0))
        det-mode  (keyword (or (:slash-detection-mode utility-spec) :explicit-slash-total))
        threshold (long (or (:slash-threshold utility-spec) 1))
        terminal-wealth (get-agent-wealth terminal-world actor)
        ;; Actor-scoped, node-local slash detection.
        slash-amount
        (when pre-world
          (case det-mode
            :explicit-slash-total (detect-slash-explicit pre-world terminal-world actor)
            :stake-delta          (detect-slash-stake-delta pre-world terminal-world actor threshold)
            (detect-slash-explicit pre-world terminal-world actor)))
        slashed?    (boolean (and (some? slash-amount) (pos? (long slash-amount))))
        ;; rep-adj is future-earnings penalty, not stake loss (already in terminal-wealth).
        rep-adj     (if slashed?
                      (- (long (Math/round (* (double penalty) discount))))
                      0)
        total-util  (when (some? terminal-wealth)
                      (+ (long terminal-wealth) (long rep-adj)))
        breakdown   {:terminal-realized-wealth terminal-wealth
                     :slash-detected?          slashed?
                     :slash-amount             (or slash-amount 0)
                     :reputation-adjustment    rep-adj
                     :reputation-model         (keyword (or (:reputation/model utility-spec) :fixed-penalty))
                     :reputation-penalty-used  penalty
                     :reputation-discount-rate discount
                     :total-utility            total-util}]
    {:defined?          (some? total-util)
     :value             total-util
     :utility-type      u-type
     :utility-version   u-ver
     :utility-breakdown breakdown}))

(defn compute-utility
  "Canonical utility interface for Phase A.
   Returns {:defined? bool :value number|nil :utility-type kw :utility-version str}.
   pre-world is required for :resolver-reputation-v1 (slash detection baseline)."
  ([world agent-id utility-spec] (compute-utility world agent-id utility-spec nil))
  ([world agent-id utility-spec pre-world]
   (let [u-type (keyword (or (:type utility-spec) :terminal-realized-v1))
         u-ver  (str (or (:version utility-spec) "v1"))]
     (case u-type
       :terminal-realized-v1
       {:defined? true
        :value (get-agent-wealth world agent-id)
        :utility-type u-type
        :utility-version u-ver}

       :resolver-reputation-v1
       (compute-reputation-utility world agent-id utility-spec pre-world)

       {:defined? false
        :value nil
        :utility-type u-type
        :utility-version u-ver}))))

(defn- build-information-set
  "Phase B minimal information-set model.
   Keeps a deterministic, role-bounded observable view derived from pre-state."
  [pre-world {:keys [agent action seq]}]
  {:agent agent
   :decision-seq seq
   :decision-action action
   :observable-state {:block-time (get pre-world :block-time)
                      :pending-count (get pre-world :pending-count)
                      :live-states (get pre-world :live-states)
                      :dispute-levels (get pre-world :dispute-levels)}
   :hidden-state [:resolver-stakes :bond-balances :claimable]
   :available-actions (vec (get action-alternatives action []))})

(defn- bounded-alternatives
  [node-type action info-set spe-config]
  (let [base (or (seq (get node-type-alternatives node-type))
                 (seq (:available-actions info-set))
                 (seq (get action-alternatives action []))
                 [])
        timing-variants (when (get spe-config :enable-timing-variants? true)
                          ["wait_same_block" "wait_next_block"])
        exogenous-variants (when (get spe-config :enable-exogenous-variants? true)
                             ["hold_exogenous_fixed" "allow_exogenous_shift"])
        enriched (concat base timing-variants exogenous-variants)
        cap  (long (get spe-config :max-alternatives-per-node 3))]
    (->> enriched
         (remove #(= % action))
         distinct
         (take (max 0 cap))
         vec)))

(defn- regret-exceeds-epsilon?
  [regret chosen-utility epsilon-abs epsilon-rel]
  (let [abs-th (double (or epsilon-abs 0.0))
        rel-th (double (or epsilon-rel 0.0))
        rel-v  (if (and (some? chosen-utility) (not (zero? (double chosen-utility))))
                 (/ (double regret) (Math/abs (double chosen-utility)))
                 0.0)]
    (or (> (double regret) abs-th)
        (> rel-v rel-th))))

(defn- response-adjustment
  "Deterministic response-policy adjustment for counterfactual alternatives.
   Phase D foundation: enables explicit mode differences without introducing
   nondeterminism."
  [continuation-policy chosen-utility]
  (case (:mode continuation-policy)
    :policy-response (if (some? chosen-utility) 1 0)
    :trace-following 0
    0))

(defn- compute-bundle-regret
  "Bounded multi-step deviation bundle proxy.
   Uses depth-2 style additive penalty on positive one-step regret as a
   deterministic approximation for compounded strategic deviation effects."
  [local-regret max-depth]
  (let [depth (long (max 1 max-depth))]
    (if (or (nil? local-regret) (<= local-regret 0) (= depth 1))
      local-regret
      (+ local-regret (long (Math/floor (/ local-regret 2.0)))))))

(def stale-continuation-errors
  "Error keywords from guard checks that indicate a continuation event from the
   original trace does not apply in the forked world state.

   This is a public var rather than a private set so that test code can
   validate it against the actual lifecycle guard-check error keywords.
   To add a new error, add it here AND in the corresponding lifecycle or
   resolution guard check.

   If you are adding a new guard check, export its error keyword from the
   lifecycle/resolution namespace and add it to this set.  Run the
   spe-validation fixture suite to confirm stale continuation tagging
   still works."
  #{:transfer-not-pending :transfer-not-in-dispute :invalid-workflow-id
    :transfer-not-finalized :invalid-state-for-release
    :invalid-state-for-refund
    :no-pending-settlement :no-resolution-to-appeal
    :no-resolution-to-challenge :has-pending-settlement
    :appeal-window-expired :escalation-not-allowed
    :liquidity-insufficient})

(defn- fork-world-at-seq
  "Return the replay-complete world checkpoint immediately before `node` executes.
   Falls back to the lean trace snapshot when checkpoints are unavailable."
  [world-checkpoints node pre-entry]
  (or (get world-checkpoints (:seq node))
      (:world pre-entry)))

(defn- continuation-replay-events
  "Normalize main-line trace tail entries into bare replay events for fork replay.
   Filters out non-event trace entries (e.g. batch markers, synthetic snapshots).
   Preserves the original :seq as :fork/original-seq before the events are
   renumbered to 1, 2, 3... by expand-strategic-tree."
  [remaining-trace-entries]
  (->> remaining-trace-entries
       (filter :action)
       (mapv (fn [entry]
               (let [replay-event (replay/trace-entry->replay-event entry)]
                 (if-let [orig-seq (:seq replay-event)]
                   (assoc replay-event :fork/original-seq orig-seq)
                   replay-event))))))

(defn- tag-stale-continuations
  "Tag trace entries from continuation events (seq > 0) that were rejected due
   to stale state.  Adds :fork/continuation true to every continuation entry and
   :fork/stale-continuation true to those that were rejected with a state-mismatch
   error.  Evidence consumers can use these flags to distinguish genuine protocol
   failures from expected counterfactual fallout."
  [trace]
  (mapv (fn [entry]
          (if (pos? (:seq entry 0))
            (let [tagged (assoc entry :fork/continuation true)]
              (if (and (= :rejected (:result entry))
                       (contains? stale-continuation-errors (:error entry)))
                (assoc tagged :fork/stale-continuation true)
                tagged))
            entry))
        trace))

(defn expand-strategic-tree
  "Phase 2: Expand strategic branches from a decision node.
   Forks the simulation for each alternative action and follows the original
   trace continuation where possible."
  [protocol agents p-params scenario-id fork-world actor remaining-trace-entries options]
  (let [available (proto/available-actions protocol fork-world actor)
        cont-events (continuation-replay-events remaining-trace-entries)]
    (for [{:keys [action params] :as action-map} available]
      (let [deviation-event {:seq 0 :time (:block-time fork-world) :agent actor :action action :params params}
            cont-events'    (map-indexed (fn [i e] (assoc e :seq (inc i))) cont-events)
            result (replay/resume-from-snapshot
                    protocol agents p-params scenario-id fork-world
                    (into [deviation-event] cont-events')
                    [] {} options)
            tagged-trace (tag-stale-continuations (:trace result))]
        {:action           action-map
         :outcome          (:outcome result)
         :halt-reason      (:halt-reason result)
         :terminal-world   (:world result)
         :trace            tagged-trace
         :terminal-utility (compute-utility (:world result) actor (:utility-spec options))}))))

(defn- classify-row
  [{:keys [node-type alternatives chosen-utility best-alt-utility]}]
  (cond
    (nil? node-type) :inapplicable-node-type
    (empty? alternatives) :inconclusive-insufficient-alternatives
    (or (nil? chosen-utility) (nil? best-alt-utility)) :inconclusive-undefined-utility
    :else :evaluated))

(defn- node->table-row
  [{:keys [raw-trace terminal-state continuation-policy replay-boundary utility-spec
           strategy-profile backward-induction-ctx world-checkpoints
           protocol agents protocol-params scenario-id] :as row-ctx}
   {:keys [agent address action] :as node}
   spe-config]
  (let [node-seq        (:seq node)
        trace-idx       (first (keep-indexed (fn [i e] (when (= (:seq e) node-seq) i)) raw-trace))
        idx             (or trace-idx (long node-seq))
        pre-entry      (when (and trace-idx (pos? trace-idx)) (nth raw-trace (dec trace-idx) nil))
        chosen-entry   (nth raw-trace idx nil)
        actor          (or address agent)
        node-type      (get node-type-by-action action)
        pre-world      (:world pre-entry)
        fork-world     (fork-world-at-seq world-checkpoints node pre-entry)
        chosen-world   (:world chosen-entry)
        ;; Phase F: subgame boundary classification
        subgame-class  (classify-subgame-node node pre-world)
        info-set       (build-information-set pre-world node)

        ;; Phase 2: Dynamic Tree Expansion
        tree-expansion? (boolean (get spe-config :enable-tree-expansion? false))
        dynamic-branches (when (and tree-expansion? fork-world protocol)
                           (expand-strategic-tree
                            protocol agents protocol-params scenario-id fork-world actor
                            (drop (inc idx) raw-trace)
                            spe-config))

        alternatives   (if (seq dynamic-branches)
                         (mapv #(get-in % [:action :action]) dynamic-branches)
                         (bounded-alternatives node-type action info-set spe-config))

        pre-utility-r  (when pre-world (compute-utility pre-world actor utility-spec))
        chosen-utility-r (when terminal-state (compute-utility terminal-state actor utility-spec pre-world))
        pre-utility    (:value pre-utility-r)
        chosen-utility (:value chosen-utility-r)

        ;; Phase K: backward-induction-ctx overrides the forward-pass heuristic when present.
        ;; bi-result = {:best-alt-utility n :deviation-classes {alt → class}}, or nil.
        bi-result
        (when (and backward-induction-ctx (seq alternatives))
          (let [{:keys [wealth-table downstream-seqs]} backward-induction-ctx
                per-alt-vals (mapv (fn [alt]
                                     (compute-backward-alt-utility
                                      actor alt pre-utility (or chosen-utility 0)
                                      wealth-table downstream-seqs))
                                   alternatives)]
            {:best-alt-utility  (apply max per-alt-vals)
             :deviation-classes (zipmap alternatives
                                        (map classify-deviation-action alternatives))}))

        ;; Phase 2: Best alt from dynamic expansion (forward mode)
        best-alt-from-tree (when (seq dynamic-branches)
                             (apply max (keep (fn [b] (get-in b [:terminal-utility :value])) dynamic-branches)))

        ;; Forward-pass heuristic (used when bi-result is nil).
        local-alt-utility
        (when (and (nil? bi-result) chosen-world)
          (let [chosen-local (get-agent-wealth chosen-world actor)]
            (if (and (some? pre-utility) (some? chosen-local))
              ;; bounded local replay proxy: if chosen action immediately reduces
              ;; wealth (e.g. bond lock/slash), alternatives "wait/settle" avoid
              ;; that immediate drop in this local subgame snapshot.
              (max pre-utility chosen-local)
              chosen-local)))
        response-delta  (when (nil? bi-result) (response-adjustment continuation-policy chosen-utility))
        best-alt-utility (or (:best-alt-utility bi-result)
                             best-alt-from-tree
                             (if (seq alternatives)
                               (max (or local-alt-utility Long/MIN_VALUE)
                                    (+ (long (or chosen-utility Long/MIN_VALUE)) (or response-delta 0)))
                               chosen-utility))
        deviation-classes (:deviation-classes bi-result)
        classification (classify-row {:node-type node-type
                                      :alternatives alternatives
                                      :chosen-utility chosen-utility
                                      :best-alt-utility best-alt-utility})
        regret (if (and (= :evaluated classification)
                        (some? best-alt-utility)
                        (some? chosen-utility))
                 (max 0 (- best-alt-utility chosen-utility))
                 nil)
        ;; Min reputation penalty required for this node to pass SPE.
        ;; Only meaningful for :resolver-reputation-v1 when chosen-utility already
        ;; includes rep-adj. Compare against the terminal-realized baseline so the
        ;; required threshold is expressed in penalty units (before discount).
        utility-breakdown (:utility-breakdown chosen-utility-r)
        min-rep-penalty-required
        (when (and utility-breakdown (some? best-alt-utility))
          (let [terminal-w (long (or (:terminal-realized-wealth utility-breakdown) 0))
                gap        (max 0 (- (long best-alt-utility) terminal-w))
                disc       (double (or (:reputation-discount-rate utility-spec) 1.0))]
            (when (pos? gap)
              (if (pos? disc)
                (long (Math/ceil (/ (double gap) disc)))
                gap))))]
    {:node-index idx
     :agent agent
     :address actor
     :node-type node-type
     ;; Phase F
     :checkability (:checkability subgame-class)
     :spe/checkability (:spe/checkability subgame-class)
     :checkability-reason (:checkability-reason subgame-class)
     :information-set info-set
     :classification classification
     :chosen-action action
     :alternatives alternatives
     :continuation-policy continuation-policy
     :replay-boundary replay-boundary
     :utility-spec utility-spec
     ;; Phase G
     :governing-policy (governing-policy actor strategy-profile)
     :chosen-utility chosen-utility
     :best-alt-utility best-alt-utility
     :local-regret regret
     :bundle-regret nil
     ;; Phase K
     :deviation-classes deviation-classes
     :deterministic-key (str idx "|" agent "|" action)
     :node/id           (str idx "|" agent "|" action)
     ;; Reputation utility breakdown (only present for :resolver-reputation-v1)
     :utility-breakdown utility-breakdown
     :min-reputation-penalty-required min-rep-penalty-required}))

;; ---------------------------------------------------------------------------
;; Phase H — Rich SPE result vocabulary
;; ---------------------------------------------------------------------------

(defn- derive-spe-result
  "Phase H: derive a rich SPE vocabulary keyword from the raw evaluator state.
   Returned alongside the backward-compat :status key.

   :spe/pass                          — all evaluated nodes have regret = 0
   :spe/epsilon-pass                  — all evaluated nodes pass threshold, but some have regret > 0
   :spe/fail-profitable-deviation     — max-regret exceeds threshold or epsilon exceeded
   :spe/not-a-proper-subgame          — all nodes inapplicable (no node-type match)
   :spe/inconclusive-missing-actions  — some nodes lack alternatives
   :spe/inconclusive-missing-utility  — some nodes lack defined utility
   :spe/inconclusive-continuation-undefined — other inconclusive cases"
  [status evaluated-count max-regret threshold exceed-count rows]
  (cond
    (zero? evaluated-count)
    (let [class-issues (mapv :classification rows)]
      (cond
        (every? #(= :inapplicable-node-type %) class-issues)
        :spe/not-a-proper-subgame

        (some #(= :inconclusive-insufficient-alternatives %) class-issues)
        :spe/inconclusive-missing-actions

        (some #(= :inconclusive-undefined-utility %) class-issues)
        :spe/inconclusive-missing-utility

        :else
        :spe/inconclusive-continuation-undefined))

    (= status :pass)
    ;; :spe/epsilon-pass when passes threshold but max-regret > 0 (not perfect)
    (if (and (some? max-regret) (pos? (long max-regret)))
      :spe/epsilon-pass
      :spe/pass)

    (= status :fail)
    :spe/fail-profitable-deviation

    :else
    :spe/inconclusive-continuation-undefined))

;; ---------------------------------------------------------------------------
;; Phase I — Structured counterexample maps
;; ---------------------------------------------------------------------------

(defn- build-counterexample
  "Phase I: build a structured counterexample map for a profitable deviation row."
  [row epsilon-abs epsilon-rel]
  (let [regret   (long (or (:bundle-regret row) 0))
        chosen-u (:chosen-utility row)
        alt-u    (:best-alt-utility row)
        pre-obs  (get-in row [:information-set :observable-state])]
    {:failure/type        :profitable-deviation
     :node/id             (:deterministic-key row)
     :agent               (:agent row)
     :chosen-action       (:chosen-action row)
     :best-alternative    (first (:alternatives row))
     :chosen-utility      chosen-u
     :alternative-utility alt-u
     :regret              regret
     :epsilon-exceeded?   (regret-exceeds-epsilon? regret chosen-u epsilon-abs epsilon-rel)
     :checkability        (:checkability row)
     :pre-state-summary   {:block-time    (:block-time pre-obs)
                           :dispute-level (get (:dispute-levels pre-obs) "unknown")}
     :counterfactual-ref  nil}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-subgame-counterfactual
  "Compute bounded local regret evidence for strategic decision nodes.

   Returns:
   {:status                :pass|:fail|:inconclusive  (backward-compat)
    :spe-result            :spe/pass|...              (Phase H rich vocab)
    :result-strength       One of:
                           :pass                   — all evaluated nodes within epsilon bounds; no caveats
                           :epsilon-pass           — all nodes pass but some exceeded epsilon thresholds
                           :profitable-deviation   — at least one node has positive regret exceeding threshold;
                                                     a profitable deviation exists
                           :inconclusive           — zero evaluated-count; insufficient evidence to assess
    :basis                 kw
    :regret-table          [...]
    :max-regret            n
    :threshold             n
    :candidate-deviations  long                       (distinct alternatives across rows)
    :coverage              {:evaluated N :total N :fraction 0.xx}
    :continuation/mode     :forward|:backward-induction
    :checked-nodes         n
    :strategy-profile      map                        (Phase G)
    :proper-subgames-checked n                        (Phase F)
    :information-set-nodes-checked n                  (Phase F)
    :not-checkable-nodes n                            (Phase F)
    :off-path-coverage     map                        (Phase J)
    :counterexamples       [...]                      (Phase I)
    :requires              [...]}"
  [{:keys [raw-trace decisions terminal-world spe-config world-checkpoints
           protocol agents protocol-params scenario-id]}]
  (let [decision-nodes (->> decisions
                            (sort-by (juxt :seq :agent :action))
                            vec)
        threshold      (long (get spe-config :regret-threshold 0))
        max-depth      (long (get spe-config :max-deviation-depth 1))
        epsilon-abs    (double (get spe-config :epsilon-abs 0.0))
        epsilon-rel    (double (get spe-config :epsilon-rel 0.0))
        continuation-policy (merge default-continuation-policy
                                   (or (:continuation-policy spe-config) {}))
        replay-boundary (merge default-replay-boundary
                               (or (:replay-boundary spe-config) {}))
        utility-spec (merge default-utility-spec
                            (or (:utility-spec spe-config) {}))
        ;; Phase G: strategy profile
        strategy-profile (merge default-strategy-profile
                                (or (:strategy-profile spe-config) {}))
        terminal-state (:world (last raw-trace))
        ;; Phase K: evaluation mode (default :forward for backward compatibility)
        eval-mode  (keyword (get spe-config :evaluation-mode :forward))
        backward?  (= eval-mode :backward-induction)
        ;; Early-exit helper for inconclusive stubs
        stub (fn [basis requires]
               {:status :inconclusive
                :spe-result :spe/inconclusive-continuation-undefined
                :basis basis
                :regret-table []
                :max-regret nil
                :threshold threshold
                :continuation-policy continuation-policy
                :replay-boundary replay-boundary
                :utility-spec utility-spec
                :strategy-profile strategy-profile
                :checked-nodes 0
                :proper-subgames-checked 0
                :information-set-nodes-checked 0
                :not-checkable-nodes 0
                :class-counts {:inconclusive-insufficient-alternatives 0
                               :inconclusive-undefined-utility 0
                               :inapplicable-node-type 0
                               :evaluated 0}
                :result-strength :inconclusive
                :counterexamples []
                :off-path-coverage {:nodes-generated 0
                                    :nodes-evaluated 0
                                    :nodes-inconclusive 0
                                    :nodes-not-checkable 0
                                    :proper-subgames-checked 0
                                    :information-set-nodes-checked 0
                                    :max-depth max-depth}
                :candidate-deviations 0
                :coverage {:evaluated 0 :total 0 :fraction 0.0}
                :continuation/mode eval-mode
                :evaluation-mode eval-mode
                :backward-induction-depth nil
                :deviation-terminal-count nil
                :deviation-continuation-count nil
                :requires requires})]
    (cond
      (empty? decision-nodes)
      (stub :absent-evidence ["no decision nodes available in trace"])

      (not (:terminal? terminal-world))
      (assoc (stub :multi-trace-required
                   ["trace ends before terminal settlement; counterfactual SPE proxy unavailable"])
             :checked-nodes (count decision-nodes))

      :else
      (let [;; Phase K: build wealth table and determine evaluation order
            bi-actors    (when backward?
                           (into #{} (map (fn [n] (or (:address n) (:agent n))) decision-nodes)))
            wealth-table (when backward? (build-agent-wealth-table raw-trace bi-actors))
            ;; In backward-induction mode: evaluate highest-seq first so downstream
            ;; continuation values are available when processing earlier nodes.
            eval-nodes   (if backward?
                           (sort-by :seq #(compare %2 %1) decision-nodes)
                           decision-nodes)
            ;; Memoisation cache (forward mode only; backward mode uses per-node state)
            memo*      (atom {})
            cache-key  (fn [node]
                         [(:seq node)
                          (:agent node)
                          (:action node)
                          (:mode continuation-policy)
                          (:version continuation-policy)
                          (:type utility-spec)
                          (:version utility-spec)
                          (:id strategy-profile)
                          (long (get spe-config :max-alternatives-per-node 3))
                          (boolean (get spe-config :enable-timing-variants? true))
                          (boolean (get spe-config :enable-exogenous-variants? true))])
            row-ctx    {:raw-trace raw-trace
                        :terminal-state terminal-state
                        :continuation-policy continuation-policy
                        :replay-boundary replay-boundary
                        :utility-spec utility-spec
                        :strategy-profile strategy-profile
                        :world-checkpoints (or world-checkpoints {})
                        :protocol protocol
                        :agents agents
                        :protocol-params protocol-params
                        :scenario-id scenario-id}
            cached-row (fn [node]
                         (let [k (cache-key node)]
                           (if-let [hit (get @memo* k)]
                             (assoc hit :memoization-hit? true)
                             (let [computed (node->table-row row-ctx node spe-config)]
                               (swap! memo* assoc k computed)
                               (assoc computed :memoization-hit? false)))))
            ;; Phase K: backward-induction evaluation via reduce (tracks processed-seqs).
            ;; Forward mode uses memoised mapv (no change to existing behaviour).
            rows0      (if backward?
                         ;; Evaluate descending; accumulate processed-seqs (= downstream nodes
                         ;; already evaluated). Re-sort ascending by node-index for output.
                         (let [bi-rows (:rows
                                        (reduce (fn [{:keys [rows processed-seqs]} node]
                                                  (let [bi-ctx {:wealth-table    wealth-table
                                                                :downstream-seqs processed-seqs}
                                                        row    (node->table-row
                                                                (assoc row-ctx :backward-induction-ctx bi-ctx)
                                                                node spe-config)]
                                                    (if (and (some? (:local-regret row)) (> (:local-regret row) threshold))
                                                      (reduced {:rows (conj rows (assoc row :memoization-hit? false :short-circuited? true))})
                                                      {:rows         (conj rows (assoc row :memoization-hit? false))
                                                       :processed-seqs (conj processed-seqs (long (:seq node)))})))
                                                {:rows [] :processed-seqs #{}}
                                                eval-nodes))]
                           (vec (sort-by :node-index (:rows bi-rows))))
                         (:rows
                          (reduce (fn [{:keys [rows]} node]
                                    (let [row (cached-row node)]
                                      (if (and (some? (:local-regret row)) (> (:local-regret row) threshold))
                                        (reduced {:rows (conj rows (assoc row :short-circuited? true))})
                                        {:rows (conj rows row)})))
                                  {:rows []}
                                  decision-nodes)))
            rows       (mapv (fn [r]
                               (assoc r :bundle-regret
                                      (compute-bundle-regret (:local-regret r) max-depth)))
                             rows0)
            regrets    (keep :bundle-regret rows)
            max-regret (when (seq regrets) (apply max regrets))
            mean-regret (when (seq regrets) (/ (reduce + 0 regrets) (count regrets)))
            class-counts (reduce (fn [m r]
                                   (update m (:classification r) (fnil inc 0)))
                                 {:inconclusive-insufficient-alternatives 0
                                  :inconclusive-undefined-utility 0
                                  :inapplicable-node-type 0
                                  :evaluated 0}
                                 rows)
            evaluated-count (:evaluated class-counts 0)
            short-circuited? (some :short-circuited? rows)
            exceed-count (count (filter identity
                                        (for [{:keys [bundle-regret chosen-utility short-circuited?]} rows
                                              :when (some? bundle-regret)]
                                          (or short-circuited? (regret-exceeds-epsilon? bundle-regret chosen-utility epsilon-abs epsilon-rel)))))
            pass?      (and (pos? evaluated-count)
                            (some? max-regret)
                            (not short-circuited?)
                            (<= max-regret threshold)
                            (zero? exceed-count))
            status     (cond
                         short-circuited? :fail
                         (zero? evaluated-count) :inconclusive
                         pass? :pass
                         :else :fail)
            requires   (if (zero? evaluated-count)
                         ["all decision nodes were inapplicable or lacked defined alternatives/utility"]
                         [])
            ;; Phase H: rich SPE result vocabulary
            spe-result (derive-spe-result status evaluated-count max-regret threshold exceed-count rows)
            ;; Phase F: subgame boundary coverage counts
            proper-count     (count (filter #(= :proper-subgame (:checkability %)) rows))
            info-set-count   (count (filter #(= :information-set-node (:checkability %)) rows))
            not-check-count  (count (filter #(= :not-spe-checkable (:checkability %)) rows))
            inconclusive-count (+ (long (:inconclusive-insufficient-alternatives class-counts 0))
                                  (long (:inconclusive-undefined-utility class-counts 0)))
            ;; Phase I: structured counterexamples for profitable deviations
            counterexamples (vec
                             (for [r rows
                                   :let [regret (long (or (:bundle-regret r) 0))]
                                   :when (pos? regret)]
                               (build-counterexample r epsilon-abs epsilon-rel)))
            ;; Phase J: off-path coverage report
            off-path-coverage {:nodes-generated         (count decision-nodes)
                               :nodes-evaluated         evaluated-count
                               :nodes-inconclusive      inconclusive-count
                               :nodes-not-checkable     not-check-count
                               :proper-subgames-checked proper-count
                               :information-set-nodes-checked info-set-count
                               :max-depth               max-depth}
            ;; Phase K: deviation classification counts (backward-induction mode only)
            all-dev-classes  (when backward?
                               (mapcat (fn [r] (vals (or (:deviation-classes r) {}))) rows))
            deviation-terminal-count    (when backward?
                                          (count (filter #(= :terminal-deviation %) all-dev-classes)))
            deviation-continuation-count (when backward?
                                           (count (filter #(= :continuation-deviation %) all-dev-classes)))
            ;; Reputation utility: global min-required-penalty aggregate.
            ;; The minimum penalty that would close every profitable-deviation gap.
            ;; Only meaningful when utility-spec type is :resolver-reputation-v1.
            rep-penalties    (keep :min-reputation-penalty-required rows)
            min-rep-penalty-for-spe-pass (when (seq rep-penalties) (apply max rep-penalties))
            ;; Derived diagnostic keys
            result-strength  (cond
                               (= :pass status) (if (pos? exceed-count) :epsilon-pass :pass)
                               (= :fail status) (if (pos? evaluated-count) :profitable-deviation :inconclusive)
                               :else :inconclusive)
            total-nodes      (long (count rows))
            cov-fraction     (if (pos? total-nodes) (/ (double evaluated-count) (double total-nodes)) 0.0)]
        {:status status
         :spe-result spe-result
         :result-strength result-strength
         :basis :single-trace-node-counterfactual-proxy
         :regret-table rows
         :max-regret max-regret
         :mean-regret mean-regret
         :threshold threshold
         :epsilon-abs epsilon-abs
         :epsilon-rel epsilon-rel
         :max-deviation-depth max-depth
         :continuation-policy continuation-policy
         :replay-boundary replay-boundary
         :utility-spec utility-spec
         :strategy-profile strategy-profile
         :proper-subgames-checked proper-count
         :information-set-nodes-checked info-set-count
         :not-checkable-nodes not-check-count
         :class-counts class-counts
         :exceed-epsilon-count exceed-count
         :memoization {:enabled  (not backward?)
                       :entries  (count @memo*)
                       :hits     (count (filter :memoization-hit? rows0))}
         :regret-distribution {:zero (count (filter zero? regrets))
                               :positive (count (filter pos? regrets))}
         :counterexamples counterexamples
         :off-path-coverage off-path-coverage
         :candidate-deviations (long (count (distinct (mapcat :alternatives rows))))
         :coverage {:evaluated evaluated-count
                    :total total-nodes
                    :fraction cov-fraction}
         :continuation/mode eval-mode
         :checked-nodes total-nodes
         :requires requires
         ;; Phase K
         :evaluation-mode eval-mode
         :backward-induction-depth (when backward? (count eval-nodes))
         :deviation-terminal-count deviation-terminal-count
         :deviation-continuation-count deviation-continuation-count
         ;; Reputation utility: minimum penalty required across all nodes for SPE pass.
         ;; nil when utility-spec is not :resolver-reputation-v1 or no gap exists.
         :min-reputation-penalty-for-spe-pass min-rep-penalty-for-spe-pass}))))

;; ---------------------------------------------------------------------------
;; Profile matrix runner
;; ---------------------------------------------------------------------------

(defn run-profile-matrix
  "Run evaluate-subgame-counterfactual against each profile in the profile list.

   `projection` — map with keys :raw-trace, :decisions, :terminal-world, :spe-config.
   `profiles`   — sequence of profile ids (keywords) or inline maps.

   For each profile, the profile is resolved via reputation-profiles/resolve-utility-profile
   and merged into projection's :spe-config :utility-spec before evaluation.

   Returns a vector of per-profile result maps:
   [{:profile-id            kw-or-map
     :profile/category      kw (when named profile)
     :profile/evidence-basis kw (when named profile)
     :status                :pass|:fail|:inconclusive
     :spe-result            kw
     :max-regret            n
     :threshold             n
     :required-reputation-penalty  n|nil
     :configured-reputation-penalty n
     :safety-margin         n|nil
     :slash-detected-count  n
     :profile-spec          map}]

   Also returns :min-profile-required — the profile-id of the weakest (earliest in list)
   profile that yields :pass status, or nil if none pass."
  [projection profiles]
  (let [base-spe-config (:spe-config projection {})
        base-utility    (get base-spe-config :utility-spec {})
        results
        (mapv
         (fn [profile-key]
           (let [resolved    (rep-profiles/resolve-utility-profile profile-key)
                 merged-spec (merge base-utility resolved)
                 new-spe-cfg (assoc base-spe-config :utility-spec merged-spec)
                 new-proj    (assoc projection :spe-config new-spe-cfg)
                 eval-result (evaluate-subgame-counterfactual new-proj)
                 min-req     (:min-reputation-penalty-for-spe-pass eval-result)
                 configured  (long (or (:reputation-slash-penalty merged-spec)
                                       (get-in merged-spec [:reputation-event-penalties :resolver-slashed] 0)
                                       0))
                 safety      (when (and min-req (= :pass (:status eval-result)))
                               (- configured (long min-req)))
                 slash-count (count (filter (fn [row]
                                              (get-in row [:utility-breakdown :slash-detected?]))
                                            (:regret-table eval-result)))]
             (cond-> {:profile-id                    profile-key
                      :status                        (:status eval-result)
                      :spe-result                    (:spe-result eval-result)
                      :max-regret                    (:max-regret eval-result)
                      :threshold                     (:threshold eval-result)
                      :required-reputation-penalty   min-req
                      :configured-reputation-penalty configured
                      :safety-margin                 safety
                      :slash-detected-count          slash-count
                      :proper-subgames-checked       (:proper-subgames-checked eval-result 0)
                      :profile-spec                  merged-spec}
               (keyword? profile-key)
               (merge {:profile/category       (:profile/category resolved)
                       :profile/evidence-basis (:profile/evidence-basis resolved)
                       :profile/description    (:profile/description resolved)}))))
         profiles)
        first-pass (first (filter #(= :pass (:status %)) results))]
    {:profile-results   results
     :min-profile-required (when first-pass (:profile-id first-pass))
     :any-pass?         (boolean first-pass)
     :all-pass?         (every? #(= :pass (:status %)) results)
     :fail-profiles     (mapv :profile-id (filter #(= :fail (:status %)) results))}))
