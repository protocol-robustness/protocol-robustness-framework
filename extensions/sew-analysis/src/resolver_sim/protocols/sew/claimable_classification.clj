(ns resolver-sim.protocols.sew.claimable-classification
  "Claimable-classification.v2 taxonomy and terminal-world aggregation.

   Architecture (three layers):

     1. TAXONOMY (lines 15–192)
        Defines the 14 canonical fund classes (v2-classes) and the domain
        vocabulary (canonical-v2-domains).  Each class maps to one or more
        :claimable-v2 domains and carries delivery model, source, risk class,
        category, and boundary invariant references.

     2. AGGREGATION (lines 200–700)
        Pure functions that sum :claimable-v2 balances across terminal worlds,
        grouped by class, by domain, by workflow, and by boundary headroom.
        All operate on immutable world maps — no I/O.

     3. DOCUMENT BUILDING (lines 708–783)
        Assembles the static taxonomy + optional terminal observations into the
        final JSON artifact consumed by notebooks and evidence exporters.

   Flow:
     emit-from-(registry-replay|scenario-file|result-file)!
       → build-document
         → taxonomy-document (static)
         → terminal-observations
           → aggregate-class-observations
           → aggregate-domain-observations
           → boundary-observations
           → scenario-highlights

   Distinct from the legacy invariant `:claimable-classification` (recipient/sender
   split on `:claimable`). This namespace describes :claimable-v2 domains, boundary
   invariants, and optional end-of-run observed balances."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.protocols.sew.claimable-outcome :as claim-outcome]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.projection :as proj]
            [resolver-sim.protocols.sew.types :as t]))

(def schema-version (evcfg/schema :claimable-classification))
(def classifier-version (evcfg/producer :claimable-classification-classifier))

(def shortfall-policy
  {:mode "partial-liquidity-supported"
   :allocation "fulfilled-plus-deferred"
   :rounding_policy (evcfg/rounding-policy)})

(def deferred-amount-semantics
  "Deferred shortfall / yield fulfillment is tracked off current pull-claimable balance until claimed.")

(defn- parse-amount
  "Coerce trace/JSON amounts (number or string) to long."
  [x]
  (t/safe-parse-long x))

(defn- token-key-str
  [token]
  (cond
    (keyword? token) (name token)
    (string? token) token
    :else (str token)))

(defn- class-spec
  [delivery-model source recipient-types risk-class domains & [extra]]
  (let [v1 (:v1_class extra)
        extra* (dissoc extra :v1_class)]
    (merge {:delivery_model delivery-model
            :source source
            :recipient_types recipient-types
            :risk_class risk-class
            :claimable_v2_domains (vec domains)
            :v1_class v1
            :v1_mapping (if v1
                          {:status "mapped" :v1_class v1}
                          {:status "new-in-v2"})}
           extra*)))

(def v2-classes
  "Canonical fund classes aligned with :claimable-v2 domains and *-boundary invariants.

   `:category` — settlement | incentive | bond | liability | reserve (for rollups)."
  {:settlement_principal
   (class-spec "pull" "settlement" ["party"] "user-withdrawable"
               [:settlement/principal]
               {:category "settlement"
                :boundary_invariant "settlement-principal-boundary"
                :v1_class "escrow_principal"})

   :settlement_yield
   (class-spec "pull" "yield" ["party" "protocol"] "yield-derived"
               [:settlement/yield]
               {:category "settlement"
                :boundary_invariant "settlement-yield-boundary"
                :v1_class "escrow_yield"
                :shortfall_outcome "may-be-partially-deferred"})

   :fees_resolver
   (class-spec "pull" "dispute-resolution" ["resolver"] "service-compensation"
               [:fees/resolver]
               {:category "incentive"
                :boundary_invariant "fee-boundary"
                :v1_class "resolver_payment"})

   :fees_protocol
   (class-spec "pull" "fee" ["protocol"] "protocol-revenue"
               [:fees/protocol]
               {:category "incentive"
                :boundary_invariant "fee-boundary"
                :authorized_withdrawer "protocol-governance"
                :v1_class "protocol_fee"})

   :yield_protocol_fee
   (class-spec "pull" "yield" ["protocol"] "protocol-revenue"
               [:yield/protocol-fee]
               {:category "incentive"
                :authorized_withdrawer "protocol-governance"
                :boundary_invariant nil
                :boundary_reason "Yield protocol fee slice; fee-boundary applies to :fees/* domains."})

   :yield_resolver_incentive
   (class-spec "pull" "yield" ["resolver"] "yield-derived-incentive"
               [:yield/resolver-incentive]
               {:category "incentive"
                :boundary_invariant nil
                :boundary_reason "Yield resolver incentive; no dedicated fee-boundary row."
                :v1_class "resolver_payment"})

   :bond_refund
   (class-spec "pull" "appeal-bond" ["disputant"] "bond-return"
               [:bond/refund]
               {:category "bond"
                :boundary_invariant "bond-boundary"
                :v1_class "bond_refund"})

   :bond_forfeit
   (class-spec "pull" "appeal-bond" ["protocol" "pool"] "bond-forfeit"
               [:bond/forfeit]
               {:category "incentive"
                :boundary_invariant nil
                :boundary_reason "Bond forfeit inflow; bond-boundary covers refund path only."})

   :liability_slash_bounty
   (class-spec "pull" "slash-distribution" ["challenger" "governance"] "slash-bounty"
               [:liability/slash-bounty]
               {:category "incentive"
                :boundary_invariant "liability-slash-boundary"})

   :liability_challenge_bounty
   (class-spec "pull" "appeal-challenge" ["challenger"] "challenge-bounty"
               [:liability/challenge-bounty]
               {:category "incentive"
                :boundary_invariant nil
                :boundary_reason "Challenge bounty; bounded by appeal lifecycle, no dedicated boundary row."})

   :liability_slash_reserve
   (class-spec "pull" "slash-pool" ["protocol" "pool"] "slash-reserve"
               [:liability/slash]
               {:category "incentive"
                :boundary_invariant nil
                :boundary_reason "Slash reserve inflow; pairs with liability-slash-boundary for bounty outflows."
                :note "Insurance-pool slash reserves (shortfall repair inflow)"})

   :reserve_shortfall
   (class-spec "pull" "shortfall-repair" ["protocol" "pool"] "shortfall-reserve"
               [:reserve/shortfall]
               {:category "reserve"
                :boundary_invariant nil
                :boundary_reason "Deferred shortfall repair; not current pull-claimable until fulfilled."})})

(def canonical-v2-domains
  "Full :claimable-v2 domain vocabulary — keywords throughout internally,
   converted to strings only at the JSON output boundary."
  [:bond/forfeit
   :bond/refund
   :fees/protocol
   :fees/resolver
   :liability/challenge-bounty
   :liability/slash
   :liability/slash-bounty
   :reserve/shortfall
   :settlement/principal
   :settlement/yield
   :yield/protocol-fee
   :yield/resolver-incentive])

(defn class-ids-by-category
  [category]
  (vec (sort (for [[class-id spec] v2-classes
                   :when (= category (:category spec))]
               (name class-id)))))

(defn- domains-present-in-worlds
  [worlds]
  (into #{}
        (mapcat (fn [w]
                  (keys (into {} (mapcat seq (vals (or (:claimable-v2 w) {}))))))
                worlds)))

(defn domain-universe
  "Canonical domains plus any :claimable-v2 kinds observed in `worlds`.
   Results are keywords — convert via (name ...) at the JSON output boundary."
  [worlds]
  (vec (sort-by name (into (set canonical-v2-domains) (domains-present-in-worlds worlds)))))

(defn- class-ids-set
  [category]
  (set (class-ids-by-category category)))

(defn- subset-by-class
  [by-class category]
  (into {}
        (for [[class-id row] by-class
              :when (contains? (class-ids-set category) class-id)]
          [class-id row])))

(defn- sum-accrued-fees
  "`:total-fees` accumulated in world (governance-withdrawable; not always in claimable-v2)."
  [worlds]
  (let [by-token (apply merge-with (fn [a b] (+ (parse-amount a) (parse-amount b)))
                        {}
                        (map #(or (:total-fees %) {}) worlds))]
    {:total (reduce + 0 (map parse-amount (vals by-token)))
     :by_token (update-vals by-token parse-amount)
     :note "Accrued protocol fees via record-fee; parallel to :fees/protocol claimable domain."}))

(defn- workflow-token
  [world workflow-id]
  (get-in world [:escrow-transfers workflow-id :token]))

(defn- sum-domain-amounts
  [world domain]
  (reduce
   (fn [acc [wf domain-map]]
     (let [addr-map (get domain-map domain {})
           token    (workflow-token world wf)]
       (reduce
        (fn [inner [addr amt]]
          (if (nil? addr)
            inner
            (let [n (parse-amount amt)]
              (if (pos? n)
                (-> inner
                    (update :total (fnil + 0) n)
                    (update-in [:by_token (or token :unknown)] (fnil + 0) n)
                    (update :workflows (fnil conj #{}) wf)
                    (update-in [:by_workflow wf] (fnil + 0) n))
                inner))))
        acc
        addr-map)))
   {:total 0 :by_token {} :workflows #{} :by_workflow {}}
   (or (:claimable-v2 world) {})))

(defn- merge-observation
  [a b]
  {:total (+ (:total a 0) (:total b 0))
   :by_token (merge-with + (:by_token a {}) (:by_token b {}))
   :scenarios_nonzero (+ (:scenarios_nonzero a 0) (:scenarios_nonzero b 0))})

(defn- observation-row
  [{:keys [total by_token workflows]}]
  (let [nz (if (or (pos? total) (seq workflows)) 1 0)]
    (cond-> {:total total
             :total_claimable total
             :scenarios_nonzero nz}
      (seq by_token) (assoc :by_token by_token))))

(defn- public-observation
  [m]
  (cond-> {:total_claimable (long (or (:total_claimable m) (:total m) 0))
           :scenarios_nonzero (long (:scenarios_nonzero m 0))}
    (seq (:by_token m)) (assoc :by_token (:by_token m))))

(defn aggregate-domain-observations
  "Sum terminal-world claimable-v2 balances per domain across `worlds`."
  [worlds]
  (into {}
        (for [domain-kw (domain-universe worlds)
              :let [rows (map #(sum-domain-amounts % domain-kw) worlds)
                    merged (reduce (fn [acc row]
                                     (merge-observation acc (observation-row row)))
                                   {:total 0 :by_token {} :scenarios_nonzero 0}
                                   rows)]]
          [(name domain-kw) (public-observation merged)])))

(defn aggregate-class-observations
  [worlds]
  (into {}
        (for [[class-id {:keys [claimable_v2_domains]}] v2-classes
              :let [domain-keys claimable_v2_domains
                    merged
                    (reduce merge-observation
                            {:total 0 :by_token {} :scenarios_nonzero 0}
                            (for [w worlds]
                              (let [per-domain (map #(sum-domain-amounts w %) domain-keys)
                                    total    (reduce + 0 (map :total per-domain))
                                    by-token (apply merge-with +
                                                    (map :by_token per-domain))
                                    workflows (into #{} (mapcat :workflows per-domain))]
                                (observation-row {:total total
                                                  :by_token by-token
                                                  :workflows workflows}))))]]
          [(name class-id) (public-observation merged)])))

(defn- sum-domain-for-workflow
  [world workflow-id domain]
  (let [addr-map (get-in world [:claimable-v2 workflow-id domain] {})
        token    (workflow-token world workflow-id)]
    (reduce
     (fn [inner [addr amt]]
       (if (nil? addr)
         inner
         (let [n (parse-amount amt)]
           (if (pos? n)
             (-> inner
                 (update :total (fnil + 0) n)
                 (update-in [:by_token (token-key-str (or token :unknown))] (fnil + 0) n)
                 (update-in [:by_address (str addr)] (fnil + 0) n))
             inner))))
     {:total 0 :by_token {} :by_address {}}
     addr-map)))

(defn- workflow-domain-breakdown
  [world workflow-id]
  (into {}
        (for [domain-kw (domain-universe [world])
              :let [row   (sum-domain-for-workflow world workflow-id domain-kw)
                    total (:total row 0)]
              :when (pos? total)]
          [(name domain-kw) (cond-> (public-observation row)
                              (seq (:by_address row)) (assoc :by_address (:by_address row)))])))

(defn- workflow-class-breakdown
  [world workflow-id]
  (into {}
        (for [[class-id {:keys [claimable_v2_domains]}] v2-classes
              :let [domain-keys claimable_v2_domains
                    total     (reduce + 0
                                      (map #(parse-amount (:total (sum-domain-for-workflow
                                                                   world workflow-id %)))
                                           domain-keys))]
              :when (pos? total)]
          [(name class-id) {:total_claimable total}])))

(defn- workflow-total-claimable
  [world workflow-id]
  (reduce + 0 (map :total_claimable (vals (workflow-class-breakdown world workflow-id)))))

(defn- workflow-ids-for-world
  [world]
  ;; :live-states is the terminal-escrow-state snapshot set by sew.clj:610
  ;; during replay finalization — included here to capture workflows that
  ;; are finalized but may not yet have :claimable-v2 entries.
  (into #{}
        (concat (keys (:claimable-v2 world {}))
                (keys (:live-states world {}))
                (keys (:escrow-transfers world {})))))

(defn- principal-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [claims (reduce + 0 (vals (get domain-map :settlement/principal {})))
               et     (get-in world [:escrow-transfers wf])
               max    (t/safe-parse-long (:amount-after-fee et))]
         :when (or (pos? claims) et)]
     {:workflow_id wf :claims claims :max max :headroom (- max claims)})))

(defn- yield-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [claims   (reduce + 0 (vals (get domain-map :settlement/yield {})))
               max-yield (inv/workflow-max-yield world wf)]
         :when (pos? claims)]
     {:workflow_id wf :claims claims :max max-yield :headroom (- max-yield claims)})))

(defn- bond-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [claims (reduce + 0 (vals (get domain-map :bond/refund {})))
               posted (+ (reduce + 0 (map t/safe-parse-long (vals (get-in world [:bond-balances wf] {}))))
                         (t/safe-parse-long (get-in world [:bond-posted-by-workflow wf] 0)))]
         :when (or (pos? claims) (pos? posted))]
     {:workflow_id wf :claims claims :max posted :headroom (- posted claims)})))

(defn- fee-utilization-rows
  [world]
  (vec
   (for [[wf domain-map] (get-in world [:claimable-v2] {})
         :let [resolver-fees (get domain-map :fees/resolver {})
               protocol-fees (get domain-map :fees/protocol {})
               claimed       (+ (reduce + 0 (vals resolver-fees))
                                (reduce + 0 (vals protocol-fees)))
               max-fees      (inv/workflow-max-fees world wf)]
         :when (or (pos? claimed) (get-in world [:escrow-transfers wf]))]
     {:workflow_id wf :claims claimed :max max-fees :headroom (- max-fees claimed)})))

(defn- liability-slash-utilization-rows
  [world]
  (let [reserves (t/safe-parse-long (:retained-slash-reserves world 0))
        rows     (vec
                  (for [[wf domain-map] (get-in world [:claimable-v2] {})
                        :let [claims (reduce + 0 (vals (get domain-map :liability/slash-bounty {})))]
                        :when (pos? claims)]
                    ;; Note: Max capacity is global retained slash reserves.
                    ;; Headroom represents capacity remaining in the global pool.
                    {:workflow_id wf :claims claims :max reserves :headroom (- reserves claims)}))]
    rows))

(def boundary-checks
  "Boundary invariants and their corresponding utilization-row generators.
   Each entry embeds both the invariant check function (`:fn`) and the row-computation
   function (`:rows`, pointer to the *-utilization-rows fn), so the two never drift.
   Defined here, after all *-utilization-rows functions, so the fn pointers resolve."
  [{:id "settlement-principal-boundary"
    :fn  inv/settlement-principal-boundary?
    :rows principal-utilization-rows}
   {:id "settlement-yield-boundary"
    :fn  inv/settlement-yield-boundary?
    :rows yield-utilization-rows}
   {:id "liability-slash-boundary"
    :fn  inv/liability-slash-boundary?
    :rows liability-slash-utilization-rows}
   {:id "bond-boundary"
    :fn  inv/bond-boundary?
    :rows bond-utilization-rows}
   {:id "fee-boundary"
    :fn  inv/fee-boundary?
    :rows fee-utilization-rows}])

(def ^:private default-highlight-limit 15)
(def ^:private default-workflows-per-highlight 10)

(defn- workflow-boundary-snapshot
  [world workflow-id]
  (into {}
        (for [{:keys [id rows]} boundary-checks
              :let [row  (first (filter #(= workflow-id (:workflow_id %)) (rows world)))]
              :when row]
          [id (select-keys row [:claims :max :headroom])])))

(defn workflow-rows-for-world
  "Per-workflow claimable and boundary snapshot (single terminal world)."
  [world & {:keys [limit]}]
  (let [lim (or limit default-workflows-per-highlight)]
    (->> (workflow-ids-for-world world)
         (map (fn [wf]
                (let [domains (workflow-domain-breakdown world wf)
                      total   (workflow-total-claimable world wf)
                      bounds  (workflow-boundary-snapshot world wf)]
                  (when (or (pos? total) (seq domains) (seq bounds))
                    {:workflow_id wf
                     :token (some-> (workflow-token world wf) name)
                     :terminal_state (some-> (get-in world [:live-states wf]) name)
                     :total_claimable total
                     :by_domain domains
                     :by_class (workflow-class-breakdown world wf)
                     :boundary_headroom bounds}))))
         (remove nil?)
         (sort-by :total_claimable >)
         (take lim)
         vec)))

(defn boundary-headroom-for-world
  [world]
  (into {}
        (for [{:keys [id rows] check-fn :fn} boundary-checks
              :let [util      (rows world)
                    result    (check-fn world)
                    headrooms (map :headroom util)
                    min-h     (when (seq headrooms) (apply min headrooms))]]
          [id {:min_headroom      min-h
               :workflows_tracked (count util)
               :workflows_at_cap  (count (filter zero? headrooms))
               :holds             (:holds? result)
               :violations        (:violations result)}])))

(defn boundary-observations
  "Per-world boundary holds? counts across `worlds`."
  [worlds]
  (into {}
        (for [{:keys [id fn]} boundary-checks]
          (let [results (map #(fn %) worlds)
                failures (count (remove :holds? results))]
            [id {:all_hold (zero? failures)
                 :failure_count failures
                 :worlds_checked (count worlds)}]))))

(defn- aggregate-boundary-headroom
  [worlds]
  (into {}
        (for [{:keys [id]} boundary-checks
              :let [per-world      (map boundary-headroom-for-world worlds)
                    rows-by-id     (map #(get % id) per-world)
                    min-headrooms  (keep :min_headroom rows-by-id)
                    all-violations (mapcat :violations rows-by-id)]]
          [id {:min_headroom_across_worlds (when (seq min-headrooms)
                                             (apply min min-headrooms))
               :worlds_at_cap  (reduce + 0 (map :workflows_at_cap rows-by-id))
               :workflows_tracked (reduce + 0 (map :workflows_tracked rows-by-id))
               :all_hold       (every? :holds rows-by-id)
               :sample_violations (vec (take 10 all-violations))}])))

(defn- merge-token-bucket-maps
  "Sum per-token custody buckets; keys normalized to strings (no duplicate USDC/USDC)."
  [maps]
  (reduce
   (fn [acc m]
     (reduce (fn [a [tok buckets]]
               (let [k (token-key-str tok)]
                 (update a k
                         (fn [existing]
                           (merge-with (fn [x y] (+ (parse-amount x) (parse-amount y)))
                                       (or existing {})
                                       buckets)))))
             acc
             m))
   {}
   maps))

(defn- total-claimable-all-classes
  [world]
  (reduce + 0
          (map :total_claimable (vals (aggregate-class-observations [world])))))

(defn- classified-claimable-total
  [worlds]
  (reduce + 0 (map total-claimable-all-classes worlds)))

(defn aggregate-funds-ledger
  "Sum global custody buckets across terminal worlds (suite-level diagnostic)."
  [worlds]
  (when (seq worlds)
    (let [views              (map proj/funds-ledger-view worlds)
          legacy-total       (reduce + 0 (map #(get-in (proj/funds-ledger-view %) [:global :claimable-total] 0) worlds))
          classified-total   (classified-claimable-total worlds)]
      {:aggregation "sum-across-terminal-worlds"
       :terminal_world_count (count worlds)
       :terminal_value_total legacy-total
       :classified_claimable_total classified-total
       :unclassified_claimable_total (max 0 (- legacy-total classified-total))
       :legacy_claimable_note
       "terminal_value_total sums legacy :claimable; classified_* sums :claimable-v2 domains only."
       :bond_locked_total (reduce + 0 (map #(get-in % [:global :bond-locked-total] 0) views))
       :withdrawn_note "Per-token held/released/refunded/withdrawn summed across scenarios — not one physical ledger."
       :by_token (merge-token-bucket-maps (map :by-token views))})))

(defn- workflow-count-for-worlds
  [worlds]
  (reduce + 0 (map #(count (workflow-ids-for-world %)) worlds)))

(defn- coverage-status-for-worlds
  [worlds]
  (let [by-class (aggregate-class-observations worlds)
        exercised (count (filter #(pos? (get-in by-class [% :total_claimable] 0))
                                 (map name (keys v2-classes))))]
    (if (zero? exercised)
      "taxonomy-emitted-no-nonzero-claimables"
      (str exercised "-classes-with-nonzero-claimable"))))

(defn- coverage-matrix
  [worlds]
  (into {}
        (for [[class-id _] v2-classes]
          (let [k (name class-id)
                total (get-in (aggregate-class-observations worlds) [k :total_claimable] 0)]
            [k {:exercised (pos? total)
                :total_claimable (long total)}]))))

(defn- observations-warnings
  [{:keys [contexts legacy-total classified-total]}]
  (vec
   (remove
    nil?
    [(when-let [ctx (first contexts)]
       (when (= "missing-from-result" (:scenario-id-status ctx))
         {:code "scenario-id-missing"
          :severity "warning"
          :message "Scenario result did not expose :scenario-id; use scenario_result_path or fix result JSON."}))
     (when (and (pos? legacy-total) (zero? classified-total))
       {:code "legacy-claimable-without-v2-classification"
        :severity "warning"
        :message (str "terminal_value_total=" legacy-total
                      " but classified_claimable_total=0; legacy :claimable may remain after settlement.")})
     (when (pos? (- legacy-total classified-total))
       {:code "unclassified-claimable-balance"
        :severity "info"
        :message (str "unclassified_claimable_total="
                      (max 0 (- legacy-total classified-total)))})])))

(defn scenario-id-from-result-path
  [result-path]
  (when (seq (str result-path))
    (-> result-path
        io/file
        .getName
        (str/replace #"\.result\.json$" "")
        (str/replace #"\.json$" ""))))

(defn resolve-scenario-identity
  [result & {:keys [result-path]}]
  (let [from-result (or (:scenario-id result)
                        (:id result)
                        (get-in result [:source :scenario-id])
                        (get-in result [:scenario :id])
                        (get-in result [:meta :scenario-id]))
        from-path   (scenario-id-from-result-path result-path)]
    {:scenario-id (or (some-> from-result str)
                      from-path
                      "unknown")
     :scenario-id-status (cond
                           from-result "from-result"
                           from-path "derived-from-result-path"
                           :else "missing-from-result")
     :scenario-result-path (some-> result-path str)}))

(defn- compact-by-class
  [class-obs]
  (into {}
        (for [[k v] class-obs
              :when (pos? (:total_claimable v 0))]
          [k v])))

(defn scenario-highlight
  [{:keys [scenario-id outcome world]} & {:keys [workflow-limit]}]
  (let [class-obs (aggregate-class-observations [world])
        ledger    (proj/funds-ledger-view world)
        bounds    (boundary-headroom-for-world world)]
    {:scenario_id (str scenario-id)
     :outcome (some-> outcome name)
     :total_claimable (total-claimable-all-classes world)
     :by_class (compact-by-class class-obs)
     :workflows (workflow-rows-for-world world :limit workflow-limit)
     :funds_ledger {:terminal_value_total (get-in ledger [:global :claimable-total] 0)
                    :classified_claimable_total (total-claimable-all-classes world)
                    :bond_locked_total (get-in ledger [:global :bond-locked-total] 0)
                    :conservation_holds (get-in ledger [:conservation :holds?])}
     :boundaries_all_hold (every? :holds (vals bounds))}))

(defn scenario-highlights
  "Top scenarios by terminal claimable balance (for researcher drill-down)."
  [contexts & {:keys [limit workflow-limit]}]
  (let [lim (or limit default-highlight-limit)]
    (->> contexts
         (map #(scenario-highlight % :workflow-limit workflow-limit))
         (sort-by :total_claimable >)
         (filter #(or (pos? (:total_claimable % 0))
                      (seq (:workflows %))))
         (take lim)
         vec)))

(defn escrow-yield-outcome-frequencies
  [worlds]
  (frequencies
   (mapcat
    (fn [w]
      (map :outcome (vals (claim-outcome/outcomes-by-workflow w))))
    worlds)))

(defn- base-observations
  "Shared observation keys that appear in every output regardless of context."
  [worlds contexts scenarios-passed aggregation aggregation-note]
  (let [legacy-total     (reduce + 0 (map #(get-in (proj/funds-ledger-view %) [:global :claimable-total] 0) worlds))
        classified-total (classified-claimable-total worlds)
        single-scenario? (= "single-terminal-world" aggregation)
        all-classes      (aggregate-class-observations worlds)]
    {:scope (or aggregation "terminal-worlds")
     :scenario_count (if contexts (count contexts) (count worlds))
     :terminal_world_count (count worlds)
     :workflow_count (workflow-count-for-worlds worlds)
     :scenarios_passed scenarios-passed
     :coverage_status (coverage-status-for-worlds worlds)
     :coverage_matrix (coverage-matrix worlds)
     :classified_claimable_total classified-total
     :terminal_value_total legacy-total
     :unclassified_claimable_total (max 0 (- legacy-total classified-total))
     :warnings (observations-warnings {:contexts contexts
                                       :legacy-total legacy-total
                                       :classified-total classified-total})
     :aggregation (or aggregation "sum-across-terminal-worlds")
     :aggregation_note
     (or aggregation-note
         (str "classified_* sums :claimable-v2 only; terminal_value_total sums legacy :claimable."))
     ;; For single-scenario output, only emit classes with nonzero claimable
     :by_class (if single-scenario? (compact-by-class all-classes) all-classes)
     :by_incentive_class (subset-by-class all-classes "incentive")
     :by_domain (aggregate-domain-observations worlds)
     :domains_discovered
     (vec (sort-by name (remove (set canonical-v2-domains)
                                (domains-present-in-worlds worlds))))
     :accrued_fees (sum-accrued-fees worlds)
     :boundaries (boundary-observations worlds)
     :boundary_headroom (aggregate-boundary-headroom worlds)
     :funds_ledger (aggregate-funds-ledger worlds)
     :escrow_yield_outcome_counts
     (into {} (map (fn [[k v]] [(name k) v]) (escrow-yield-outcome-frequencies worlds)))}))

(defn- single-scenario-section
  "Add scenario identity when a single context was the source."
  [first-ctx]
  {:scenario_id (:scenario-id first-ctx)
   :scenario_id_status (:scenario-id-status first-ctx)
   :scenario_result_path (:scenario-result-path first-ctx)})

(defn- scenario-highlights-section
  "Add top-scenarios drill-down when multiple contexts are available."
  [contexts highlight-limit workflow-limit]
  {:scenario_highlights
   (scenario-highlights contexts
                        :limit highlight-limit
                        :workflow-limit workflow-limit)})

(defn terminal-observations
  "Aggregate claimable balances and boundary status from terminal replay worlds."
  [worlds & {:keys [scope scenarios_passed contexts highlight-limit workflow-limit
                    aggregation aggregation_note]}]
  (when (seq worlds)
    (let [first-ctx (first contexts)
          base      (base-observations worlds contexts scenarios_passed
                                       aggregation aggregation_note)]
      (cond-> base
        first-ctx (merge (single-scenario-section first-ctx))
        (seq contexts) (merge (scenario-highlights-section contexts
                                                           highlight-limit
                                                           workflow-limit))))))

(defn taxonomy-document
  "Static v2 classification (no replay)."
  []
  {:schema_version schema-version
   :classifier_version classifier-version
   :shortfall_policy shortfall-policy
   :deferred_amount_semantics deferred-amount-semantics
   :canonical_domains canonical-v2-domains
   :incentives_summary
   {:category "incentive"
    :class_ids (class-ids-by-category "incentive")
    :domains (vec (sort (mapcat :claimable_v2_domains
                                (filter #(= "incentive" (:category %))
                                        (vals v2-classes)))))}
   :classes (update-vals v2-classes
                         (fn [m]
                           (update m :claimable_v2_domains vec)))})

(defn build-document
  "Full v2 artifact: taxonomy plus optional terminal observations."
  [& {:keys [worlds contexts scope scenarios-passed observations-status provenance
             highlight-limit workflow-limit aggregation aggregation-note]}]
  (cond-> (taxonomy-document)
    provenance (assoc :provenance provenance)

    (seq worlds)
    (assoc :terminal_observations
           (terminal-observations worlds
                                  :scope scope
                                  :scenarios_passed scenarios-passed
                                  :contexts contexts
                                  :highlight-limit highlight-limit
                                  :workflow-limit workflow-limit
                                  :aggregation aggregation
                                  :aggregation_note aggregation-note))

    observations-status
    (assoc :observations_status observations-status)))

(defn terminal-context-from-replay-result
  [result & {:keys [result-path]}]
  (when-let [world (proj/terminal-world-from-result result)]
    (merge (resolve-scenario-identity result :result-path result-path)
           {:outcome (:outcome result)
            :world world})))

(defn- summary-entries
  [summary]
  (or (:results summary) (:entries summary) []))

(defn terminal-contexts-from-summary
  "Replay summary entries with terminal world snapshots."
  [summary]
  (vec
   (keep
    (fn [entry]
      (if-let [res (:replay-result entry)]
        (if-let [world (proj/terminal-world-from-result res)]
          (let [sid (or (:scenario-id res)
                        (:scenario-id entry)
                        (some-> (:name entry) str))
                status (cond
                         (:scenario-id res) "from-result"
                         (:scenario-id entry) "from-summary-entry"
                         (:name entry) "from-summary-entry-name"
                         :else "missing-from-result")]
            {:scenario-id (or sid "unknown")
             :scenario-id-status status
             :outcome (:outcome res)
             :world world}))))
    (summary-entries summary))))

(defn terminal-worlds-from-summary
  "Extract terminal worlds from a scenario runner summary (`:entries`)."
  [summary]
  (mapv :world (terminal-contexts-from-summary summary)))
