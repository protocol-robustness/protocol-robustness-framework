(ns resolver-sim.yield.modules.liquid-lending
  "Liquid-lending yield archetype using the new decision-based accrual engine.

   Replaces the legacy liquid_lending/accrue which used double-based arithmetic
   inline. This version calls accrual/accrual-decision + apply-accrual-decision
   for each position, and partial-fill/calculate-fulfillment for withdrawals.

   Module identity:
     :module/id arbitrary (e.g. :aave-v3)
     :module/type :yield.profile/aave-v3-like (or :yield.provider/liquid-lending)
     :accounting/type :shares"
  (:require [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.token :as tok]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.util.evidence :as util-evidence]
            [resolver-sim.yield.evidence :as ye]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.evidence.capture :as evidence]))

(defn- normalize-token [token]
  (tok/normalize token))

(defn- resolve-now [world]
  (time-ctx/block-ts world))

(defn- token= [a b]
  (= (normalize-token a) (normalize-token b)))

(defn- get-in-token [world path module-id token & keys]
  (let [tok (normalize-token token)
        v   (or (get-in world (into path [module-id tok]))
                (get-in world (into path [module-id (name tok)])))]
    (if (seq keys)
      (get-in v keys)
      v)))

;; ---------------------------------------------------------------------------
;; deposit
;; ---------------------------------------------------------------------------
(defn deposit
  "Create a yield position using the new position model (ratio-based entry-index).
   Does NOT update :total-held — create-escrow already called add-held for the
   escrow amount.  Updating :total-held here would double-count."
  [world module op]
  (attr/with-attribution {:deposit/module-id (:module/id module)
                          :deposit/position-id (:owner/id op)
                          :deposit/token (:token op)}
    (let [oid    (:owner/id op)
          amount (:amount op)
          token  (normalize-token (:token op))
          mid    (:module/id module)
          index  (m/ratio (or (get-in-token world [:yield/indices] mid token) 1))
          shares (m/shares-from-principal-and-index (long amount) index)
          world' (assoc-in world [:yield/positions oid]
                           (pos/make-position {:owner/id oid
                                               :module/id mid
                                               :token token
                                               :principal (long amount)
                                               :shares shares
                                               :entry-index index}))]
      (evidence/capture-event-evidence!
       :yield-deposit
       {:deposit/before-positions (:yield/positions world)}
       {:deposit/after-positions (:yield/positions world')}
       {:deposit/params {:owner/id oid :amount amount :token token :module/id mid}}
       nil
       {:world-before world
        :world-after world'})
      world')))

;; ---------------------------------------------------------------------------
;; accrue
;; ---------------------------------------------------------------------------
(defn- accrue-from-index-schedule
  "Accrue all positions for this module+token using an index-schedule value.
   Bypasses the APY-based decision engine — index comes directly from the
   schedule at `now`. Updates per-position unrealized-yield and world-level
   total-yield-generated / total-held.

   Parallel pattern:
   1. snapshot world
   2. parallel pure compute (update-position-yield per position)
   3. collect deterministic ordered results
   4. serial apply to world
   5. serial evidence capture"
  [world module token mid now sched-index]
  (attr/with-attribution {:accrue/module-id mid
                          :accrue/token token
                          :accrue/index sched-index
                          :accrue/mode :index-schedule}
    (let [;; 1: snapshot world
          snapshot-positions (:yield/positions world {})
          snapshot-world (assoc-in world [:yield/indices mid token] sched-index)
          ;; 2: parallel pure compute — each position's yield update is independent
          updates (->> snapshot-positions
                       (filter (fn [[oid pos]]
                                 (and (= (:module/id pos) mid)
                                      (token= (:token pos) token)
                                      (= (:status pos) :active))))
                       vec
                       (util-evidence/contextual-pmap
                        (fn [[oid pos]]
                          (let [updated   (acct/update-position-yield snapshot-world pos sched-index)
                                old-yield (:unrealized-yield pos 0)
                                yield-delta (- (:unrealized-yield updated 0) old-yield)]
                            [oid updated yield-delta]))))
          ;; 3-4: collect deterministic ordered results, serial apply to world
          world'' (reduce (fn [w [oid updated yield-delta]]
                            (-> w
                                (assoc-in [:yield/positions oid] updated)
                                (update-in [:total-yield-generated token] (fnil + 0) yield-delta)))
                          snapshot-world
                          updates)]
      ;; 5: serial evidence capture
      (evidence/capture-event-evidence!
       :yield-accrue
       {:accrue/before-indices (:yield/indices world)
        :accrue/before-positions (:yield/positions world)}
       {:accrue/after-indices (:yield/indices world'')
        :accrue/after-positions (:yield/positions world'')}
       {:accrue/params {:module-id mid :token token :sched-index sched-index :mode :index-schedule}}
       nil
       {:world-before world
        :world-after world''})
      world'')))

(defn accrue
  "Accrue yield for all positions in this module using the decision-based
   accrual engine. Each position gets a separate accrual-decision that
   handles short circuits, dust accumulation, and exact ratio arithmetic.

   When the index-schedule provides a value at the current time, it is used
   directly instead of computing the index from APY + dt.

   Parallel pattern:
   1. snapshot world
   2. parallel pure compute (accrual-decision per position)
   3. collect deterministic ordered results
   4. serial apply to world (apply-accrual-decision-with-attribution per decision)
   5. serial evidence capture (inside apply-accrual-decision-with-attribution)"
  [world module op]
  (let [token (normalize-token (:token op))
        dt    (:dt op)
        mid   (:module/id module)
        now   (resolve-now world)
        ms    (market-state/get-market-state world mid token now)
        sched-index (:index ms)]
    (if (and sched-index (not (zero? sched-index)))
      (accrue-from-index-schedule world module token mid now sched-index)
      (let [;; 1: snapshot world
            snapshot-positions (:yield/positions world {})
            ;; 2: parallel pure compute — each position's accrual decision is independent
            decisions (->> snapshot-positions
                           (filter (fn [[oid pos]]
                                     (and (= (:module/id pos) mid)
                                          (token= (:token pos) token)
                                          (= (:status pos) :active))))
                           vec
                           (util-evidence/contextual-pmap
                            (fn [[oid pos]]
                              [oid (accrual/accrual-decision
                                    world {:module-id mid
                                           :token token
                                           :position-id oid
                                           :now now
                                           :dt dt})])))
            ;; 3-4: collect deterministic ordered, serial apply to world
            world' (reduce (fn [w [_ decision]]
                             (accrual/apply-accrual-decision-with-attribution w decision))
                           world
                           decisions)]
        world'))))

;; ---------------------------------------------------------------------------
;; withdraw
;; ---------------------------------------------------------------------------
(defn withdraw
  "Withdraw from a yield position. Crystallizes yield first via the decision
   engine, then uses partial-fill/calculate-fulfillment to handle shortfalls."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (attr/with-attribution {:withdraw/module-id mid
                            :withdraw/position-id oid}
      (cond
        (nil? pos)                      world
        (not= (:status pos) :active)    world
        (not= (:module/id pos) mid)     world
        :else
        (let [token   (normalize-token (:token pos))
              now     (resolve-now world)]
        ;; Step 1: Accrue to crystallize final yield
          (let [accrual-decision (accrual/accrual-decision
                                  world {:module-id mid
                                         :token token
                                         :position-id oid
                                         :now now
                                         :dt 0})
                world-after-accrue (accrual/apply-accrual-decision-with-attribution world accrual-decision)
                pos-after-accrue (get-in world-after-accrue pos-key)

            ;; Step 2: Determine available liquidity from market state,
            ;; which resolves the liquidity-schedule, shortfall-model,
            ;; and risk config into a composite available-ratio.
                base-recoverable (or (get-in world-after-accrue [:total-held token])
                                     (get-in world-after-accrue [:yield/held-balances (name token)])
                                     0)
                market-state (market-state/get-market-state world-after-accrue mid token now)
                available-ratio (:available-ratio market-state 1.0)
                shortfall-model (:shortfall-model market-state)
                recoverable (long (* base-recoverable available-ratio))
                gross-amount (+ (:principal pos-after-accrue 0)
                                (:unrealized-yield pos-after-accrue 0))

            ;; Step 3: Calculate fulfillment via the partial-fill engine
                settlement (partial-fill/calculate-fulfillment
                            (max 0 (long recoverable)) pos-after-accrue)
                decision-artifact (when (partial-fill/partial-fill? settlement)
                                    (partial-fill/decision-artifact
                                     pos-after-accrue
                                     settlement
                                     {:decision-source :yield-withdraw}))
                filled (get settlement :filled {})
                deferred-map (get settlement :deferred {})
                haircut-map (get settlement :haircut {})

                fulfilled-total (reduce + 0 (vals filled))
                deferred-total (reduce + 0 (vals deferred-map))
                haircut-total (reduce + 0 (vals haircut-map))
                basis-total (reduce + 0 (vals (:requested settlement {})))

            ;; Step 4: Build :shortfall (based on requested vs filled, not gross value).
            ;; When shortfall-model specifies recoverable=false, all unfilled
            ;; amounts become permanent haircuts (recognized losses) rather
            ;; than deferred (future recoverable).
                unrealized (:unrealized-yield pos-after-accrue 0)
                ;; When unrealized yield is negative (mark-to-market loss), the
                ;; position's actual gross value is less than principal.  The
                ;; partial-fill engine only requests principal (since unrealized
                ;; is :not-claimable), creating a phantom shortfall.  Adjust
                ;; the shortfall basis to the actual gross value so solvency
                ;; and shortfall-fidelity invariants balance.
                neg-unrealized (min 0 unrealized)
                adjusted-basis (+ basis-total neg-unrealized)
                shortfall (when (pos? (- adjusted-basis fulfilled-total))
                            (let [sf-reason (or (:type shortfall-model) :liquidity-shortfall)
                                  recoverable? (:recoverable shortfall-model true)
                                  ;; Positive crystallized yield not yet in deferred
                                  extra-deferred (if (and recoverable? (pos? unrealized)) unrealized 0)]
                              {:reason sf-reason
                               :basis-amount (+ adjusted-basis (if (pos? unrealized) unrealized 0))
                               :available-ratio (if (pos? gross-amount)
                                                  (/ (rationalize fulfilled-total)
                                                     (rationalize gross-amount))
                                                  1)
                               :fulfilled-amount fulfilled-total
                               :deferred-amount (+ (if recoverable? deferred-total 0) extra-deferred)
                               :haircut-amount (if recoverable? haircut-total
                                                   (+ deferred-total haircut-total extra-deferred))
                               :as-of-index (:current-index pos-after-accrue)
                               :started-at now}))

            ;; Step 5: Update position status.
            ;; When the withdrawal fully covers the obligation (no shortfall), realize
            ;; the full unrealized yield.  When there is a shortfall, cap realized yield
            ;; to the fulfilled amount above principal (the waterfall may not fill
            ;; unrealized-yield under :not-claimable treatment).
                realized-yield (if shortfall
                                 (max 0
                                      (min (:unrealized-yield pos-after-accrue 0)
                                           (- fulfilled-total (:principal pos-after-accrue 0))))
                                 (:unrealized-yield pos-after-accrue 0))
                updated-pos (-> pos-after-accrue
                                (assoc :partial-fill-affected? (boolean shortfall))
                                (assoc :status (if shortfall :unwinding :withdrawn))
                                (assoc :realized-yield realized-yield)
                                (assoc :unrealized-yield 0)
                                (assoc :shortfall shortfall))

                world-final (cond-> (assoc-in world-after-accrue pos-key updated-pos)
                              decision-artifact
                              (partial-fill/attach-decision-artifact decision-artifact))]

            (let [final-world (cond-> world-final
                                shortfall
                                (ye/emit-shortfall-event :yield.shortfall/deferred-created oid
                                                         {:deferred-amount deferred-total
                                                          :haircut-amount haircut-total
                                                          :fulfilled-amount fulfilled-total
                                                          :basis-amount basis-total
                                                          :available-ratio (:available-ratio shortfall 1.0)
                                                          :shortfall-kind (name (or (:reason shortfall) :unknown))}))]
              (evidence/capture-event-evidence!
               :yield-withdraw
               {:withdraw/before-positions (:yield/positions world)}
               {:withdraw/after-positions (:yield/positions final-world)}
               {:withdraw/params {:owner/id oid
                                  :module/id mid
                                  :token token
                                  :shortfall shortfall}
                :withdraw/partial-fill-decision decision-artifact}
               nil
               {:world-before world
                :world-after final-world})
              final-world)))))))

;; ---------------------------------------------------------------------------
;; shared pro-rata withdrawal
;; ---------------------------------------------------------------------------
(defn withdraw-shared
  "Atomically settle declared active positions against one shared liquidity pool.

   This is intentionally distinct from `withdraw-many`: all owners are allocated
   from one module/token/time liquidity amount using a single pro-rata decision,
   then applied together. It never falls back to sequential withdrawals."
  [world module {:keys [owner-ids token allocation-mode effective-caps effective-cap-source] :as op}]
  (let [mid (:module/id module)
        token (normalize-token token)
        owners (vec (sort (or owner-ids [])))]
    (when-not (seq owners)
      (throw (ex-info "Shared withdrawal requires at least one owner" {:op op})))
    (when-not (= (count owners) (count (distinct owners)))
      (throw (ex-info "Shared withdrawal owner IDs must be unique" {:owner-ids owner-ids})))
    (when-not (= :pro-rata (keyword (or allocation-mode :pro-rata)))
      (throw (ex-info "Shared withdrawal only supports pro-rata allocation" {:allocation-mode allocation-mode})))
    (when (and effective-caps (not (map? effective-caps)))
      (throw (ex-info "Shared withdrawal effective caps must be a map" {:effective-caps effective-caps})))
    (when (some (fn [[owner cap]]
                  (or (not (some #{owner} owners))
                      (not (integer? cap))
                      (neg? cap)))
                effective-caps)
      (throw (ex-info "Shared withdrawal effective caps must be non-negative integers for declared owners"
                      {:owner-ids owners :effective-caps effective-caps})))
    (let [positions (mapv #(get-in world [:yield/positions %]) owners)
          invalid (->> (map vector owners positions)
                       (remove (fn [[_ p]] (and p
                                                (= :active (:status p))
                                                (= mid (:module/id p))
                                                (token= token (:token p)))))
                       (map first)
                       vec)]
      (when (seq invalid)
        (throw (ex-info "Shared withdrawal requires active positions in one module and token"
                        {:module-id mid :token token :invalid-owner-ids invalid})))
      (attr/with-attribution {:withdraw/module-id mid
                              :withdraw/token token
                              :withdraw/position-ids owners
                              :withdraw/mode :shared-pro-rata}
        (let [now (resolve-now world)
              ;; Crystallize all selected positions before calculating the one pool.
              accrual-decisions (mapv (fn [oid]
                                        [oid (accrual/accrual-decision
                                              world {:module-id mid :token token :position-id oid
                                                     :now now :dt 0})])
                                      owners)
              ;; The shared operation owns one event-evidence record. Apply the
              ;; per-position crystallization decisions directly so each child
              ;; accrual cannot create a same-sequence forensic record.
              accrued-world (reduce (fn [w [_ decision]]
                                      (accrual/apply-accrual-decision w decision))
                                    world accrual-decisions)
              accrued-positions (mapv #(get-in accrued-world [:yield/positions %]) owners)
              requested-by-owner (into {}
                                       (map (fn [oid p]
                                              [oid (reduce + 0 (vals (:requested
                                                                      (partial-fill/calculate-fulfillment
                                                                       Long/MAX_VALUE p))))])
                                            owners accrued-positions))
              rows (mapv (fn [oid]
                           (let [owed (long (get requested-by-owner oid 0))
                                 supplied-cap (get effective-caps oid owed)]
                             {:key oid :owed owed :weight owed
                              ;; The allocator applies min(owed, cap); recording the
                              ;; supplied effective cap makes policy constraints
                              ;; explicit without changing uncapped legacy behavior.
                              :cap (long supplied-cap)})) owners)
              base-held (or (get-in accrued-world [:total-held token])
                            (get-in accrued-world [:yield/held-balances (name token)]) 0)
              market (market-state/get-market-state accrued-world mid token now)
              available (max 0 (long (* (long base-held) (:available-ratio market 1.0))))
              policy (merge partial-fill/default-partial-fill-policy
                            {:mode :pro-rata
                             :rounding-policy :largest-remainder
                             :allocation-ordering :canonical-owner-id-ascending
                             :rounding-tie-break :canonical-owner-id-ascending})
              settlement (-> (partial-fill/calculate-fulfillment-pro-rata available {} policy {:rows rows})
                             ;; Row allocation always follows the capped path. Normalize
                             ;; its semantic settlement mode when every claim is met.
                             ((fn [result]
                                (if (and (every? zero? (vals (:deferred result)))
                                         (every? zero? (vals (:haircut result))))
                                  (assoc result :settlement-mode :full-fill)
                                  result)))
                             ;; Generic allocator evidence retains doubles for interactive callers.
                             ;; Persisted decision artifacts use a canonical exact representation.
                             (update-in [:evidence :allocation-rows]
                                        (fn [allocation-rows]
                                          (mapv (fn [row]
                                                  (let [owed (long (:owed row 0))
                                                        filled (long (:filled row 0))]
                                                    (assoc row :fill-ratio
                                                           {:numerator filled
                                                            :denominator owed})))
                                                allocation-rows))))
              row-by-owner (into {} (map (juxt :key identity) (get-in settlement [:evidence :allocation-rows])))
              decision (partial-fill/decision-artifact
                        {:owner/id "shared-pool" :module/id mid :token token}
                        settlement
                        {:decision-source :yield-withdraw-shared
                         :position-id "shared-pool"
                         :extra {:participants owners
                                 :allocation/effective-caps (into {} (map (juxt :key :cap) rows))
                                                                  :allocation/effective-cap-source (or effective-cap-source :scenario-fixture)
                                                                  :allocation/scope :shared-liquidity-pool
                                 :allocation/domain {:module/id mid :token token :block-time now}
                                                                  :allocation/ordering :canonical-owner-id-ascending
                                                                  :allocation/rounding-tie-break :canonical-owner-id-ascending}})
              updated-world
              (reduce (fn [w oid]
                        (let [position (get-in w [:yield/positions oid])
                              row (get row-by-owner oid)
                              requested (long (:owed row 0))
                              filled (long (:filled row 0))
                              deferred (max 0 (- requested filled))
                              shortfall (when (pos? deferred)
                                          {:reason (or (get-in market [:shortfall-model :type]) :liquidity-shortfall)
                                           :basis-amount requested
                                           :available-ratio (if (pos? requested) (/ (rationalize filled) requested) 1)
                                           :fulfilled-amount filled
                                           :deferred-amount deferred
                                           :haircut-amount 0
                                           :as-of-index (:current-index position)
                                           :started-at now})
                              updated (-> position
                                          (assoc :partial-fill-affected? (boolean shortfall))
                                          (assoc :status (if shortfall :unwinding :withdrawn))
                                          (assoc :realized-yield 0)
                                          (assoc :unrealized-yield 0)
                                          (assoc :shortfall shortfall))]
                          (assoc-in w [:yield/positions oid] updated)))
                      accrued-world owners)
              final-world (partial-fill/attach-decision-artifact updated-world decision)]
          (evidence/capture-event-evidence!
           :yield-withdraw-shared
           {:withdraw/before-positions (:yield/positions world)}
           {:withdraw/after-positions (:yield/positions final-world)}
           {:withdraw/params {:owner-ids owners :module/id mid :token token
                              :available-liquidity available :allocation-mode :pro-rata
                              :effective-caps (into {} (map (juxt :key :cap) rows))
                                                            :effective-cap-source (or effective-cap-source :scenario-fixture)}
            :withdraw/partial-fill-decision decision}
           nil
           {:world-before world :world-after final-world})
          final-world)))))

;; ---------------------------------------------------------------------------
;; withdraw-many (batch parallel)
;; ---------------------------------------------------------------------------
(defn- compute-withdrawal-result
  "Pure computation for a single withdrawal against a world snapshot.
   Returns nil if the position is ineligible. Returns a result map with all
   computed data for serial application."
  [snapshot-positions world mid now op]
  (let [oid (:owner/id op)
        pos (get snapshot-positions oid)]
    (when (and pos
               (= (:status pos) :active)
               (= (:module/id pos) mid))
      (let [token (normalize-token (:token pos))

            a-decision (accrual/accrual-decision
                        world {:module-id mid
                               :token token
                               :position-id oid
                               :now now
                               :dt 0})

            base-recoverable (or (get-in world [:total-held token])
                                 (get-in world [:yield/held-balances (name token)])
                                 0)
            ms (market-state/get-market-state world mid token now)
            available-ratio (:available-ratio ms 1.0)
            shortfall-model (:shortfall-model ms)
            recoverable (long (* base-recoverable available-ratio))
            gross-amount (+ (:principal pos 0)
                            (:unrealized-yield pos 0))
            settlement (partial-fill/calculate-fulfillment
                        (max 0 (long recoverable)) pos)
            decision-artifact (when (partial-fill/partial-fill? settlement)
                                (partial-fill/decision-artifact
                                 pos settlement
                                 {:decision-source :yield-withdraw}))
            filled (get settlement :filled {})
            deferred-map (get settlement :deferred {})
            haircut-map (get settlement :haircut {})
            fulfilled-total (reduce + 0 (vals filled))
            deferred-total (reduce + 0 (vals deferred-map))
            haircut-total (reduce + 0 (vals haircut-map))
            basis-total (reduce + 0 (vals (:requested settlement {})))
            unrealized (:unrealized-yield pos 0)
            neg-unrealized (min 0 unrealized)
            adjusted-basis (+ basis-total neg-unrealized)
            shortfall (when (pos? (- adjusted-basis fulfilled-total))
                        (let [sf-reason (or (:type shortfall-model) :liquidity-shortfall)
                              recoverable? (:recoverable shortfall-model true)
                              extra-deferred (if (and recoverable? (pos? unrealized)) unrealized 0)]
                          {:reason sf-reason
                           :basis-amount (+ adjusted-basis (if (pos? unrealized) unrealized 0))
                           :available-ratio (if (pos? gross-amount)
                                              (/ (rationalize fulfilled-total)
                                                 (rationalize gross-amount))
                                              1)
                           :fulfilled-amount fulfilled-total
                           :deferred-amount (+ (if recoverable? deferred-total 0) extra-deferred)
                           :haircut-amount (if recoverable? haircut-total
                                               (+ deferred-total haircut-total extra-deferred))
                           :as-of-index (:current-index pos)
                           :started-at now}))
            realized-yield (if shortfall
                             (max 0
                                  (min (:unrealized-yield pos 0)
                                       (- fulfilled-total (:principal pos 0))))
                             (:unrealized-yield pos 0))
            updated-pos (-> pos
                            (assoc :partial-fill-affected? (boolean shortfall))
                            (assoc :status (if shortfall :unwinding :withdrawn))
                            (assoc :realized-yield realized-yield)
                            (assoc :unrealized-yield 0)
                            (assoc :shortfall shortfall))]
        {:oid oid
         :token token
         :accrual-decision a-decision
         :updated-pos updated-pos
         :decision-artifact decision-artifact
         :shortfall shortfall
         :fulfilled-total fulfilled-total
         :deferred-total deferred-total
         :haircut-total haircut-total
         :basis-total basis-total}))))

(defn withdraw-many
  "Batch withdraw from multiple yield positions in parallel.
   Each position's accrual decision, fulfillment calculation, and shortfall
   computation run in parallel against a single world snapshot.
   Results are applied serially to produce the final world state.

   Parallel pattern:
   1. snapshot world
   2. parallel pure compute (accrual-decision + fulfillment per position)
   3. collect deterministic ordered results
   4. serial apply to world
   5. serial evidence capture"
  [world module ops]
  (let [mid (:module/id module)
        snapshot-positions (:yield/positions world {})
        now (resolve-now world)
        ;; 1-2: snapshot, parallel pure compute per position
        results (util-evidence/contextual-pmap
                 (partial compute-withdrawal-result snapshot-positions world mid now)
                 ops)
        ;; 3-4: collect deterministic ordered, serial apply to world
        world' (reduce (fn [w result]
                         (if (nil? result)
                           w
                           (let [oid (:oid result)
                                 a-dec (:accrual-decision result)
                                 u-pos (:updated-pos result)
                                 d-art (:decision-artifact result)
                                 sf (:shortfall result)
                                 d-tot (:deferred-total result)
                                 h-tot (:haircut-total result)
                                 f-tot (:fulfilled-total result)
                                 b-tot (:basis-total result)
                                 w-after (accrual/apply-accrual-decision-with-attribution
                                          w a-dec)
                                 w-pos (assoc-in w-after [:yield/positions oid] u-pos)
                                 w-art (if d-art
                                         (partial-fill/attach-decision-artifact w-pos d-art)
                                         w-pos)]
                             (if sf
                               (ye/emit-shortfall-event
                                w-art :yield.shortfall/deferred-created oid
                                {:deferred-amount d-tot
                                 :haircut-amount h-tot
                                 :fulfilled-amount f-tot
                                 :basis-amount b-tot
                                 :available-ratio (:available-ratio sf 1.0)
                                 :shortfall-kind (name (or (:reason sf) :unknown))})
                               w-art))))
                       world
                       results)]
    world'))

;; ---------------------------------------------------------------------------
;; emergency-unwind
;; ---------------------------------------------------------------------------
(defn emergency-unwind
  "Mark all active positions in the module as :unwinding."
  [world module op]
  (attr/with-attribution {:emergency/module-id (:module/id module)
                          :emergency/token (:token op)}
    (let [mid    (:module/id module)
          token  (normalize-token (:token op))
          world' (reduce (fn [w [oid pos]]
                           (if (and (= (:module/id pos) mid)
                                    (token= (:token pos) token)
                                    (= (:status pos) :active))
                             (assoc-in w [:yield/positions oid :status] :unwinding)
                             w))
                         world
                         (:yield/positions world {}))]
      (evidence/capture-event-evidence!
       :yield-emergency-unwind
       {:emergency/before-positions (:yield/positions world)}
       {:emergency/after-positions (:yield/positions world')}
       {:emergency/params {:module-id mid :token token}}
       nil
       {:world-before world
        :world-after world'})
      world')))

;; ---------------------------------------------------------------------------
;; claim-deferred
;; ---------------------------------------------------------------------------
(defn claim-deferred
  "Attempt to reclaim deferred yield from an unwinding position."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (attr/with-attribution {:claim/module-id mid
                            :claim/position-id oid
                            :claim/token (:token pos)}
      (cond
        (nil? pos)                       world
        (not= (:module/id pos) mid)      world
        (= (:status pos) :unwinding)
        (let [old-pos pos
              new-pos (acct/claim-deferred world mid pos)
              reclaimed (:reclaimed-amount new-pos 0)]
          (if (pos? reclaimed)
            (let [world-final (assoc-in world pos-key new-pos)]
              (ye/emit-shortfall-event world-final :yield.shortfall/deferred-reclaimed oid
                                       {:reclaimed-amount reclaimed
                                        :deferred-before (get-in old-pos [:shortfall :deferred-amount] 0)})
              (evidence/capture-event-evidence!
               :yield-claim-deferred
               {:claim/before old-pos}
               {:claim/after new-pos}
               {:claim/reclaimed reclaimed}
               nil
               {:world-before world
                :world-after world-final})
              world-final)
            world))

        (= (:status pos) :queued)
        (let [claimed-pos (-> pos
                              (assoc :status :withdrawn)
                              (assoc :shortfall nil)
                              (assoc :realized-yield (:unrealized-yield pos 0))
                              (assoc :unrealized-yield 0))]
          (assoc-in world pos-key claimed-pos))

        :else world))))

;; ---------------------------------------------------------------------------
;; Module constructor
;; ---------------------------------------------------------------------------
(defn make-liquid-lending-module
  "Build a declarative module record using the v2 (decision-based) ops.

   `module-id` — dispatch key (e.g. :aave-v3).
   `module-type` — profile label (e.g. :yield.profile/aave-v3-like)."
  ([module-id]
   (make-liquid-lending-module module-id :yield.provider/liquid-lending))
  ([module-id module-type]
   {:module/id module-id
    :module/type module-type
    :module/capabilities #{:deposit :withdraw :withdraw-shared :accrue :emergency-unwind :claim-deferred}
    :accounting/type :shares
    :ops {:yield/deposit deposit
          :yield/withdraw withdraw
          :yield/withdraw-shared withdraw-shared
          :yield/accrue accrue
          :yield/emergency-unwind emergency-unwind
          :yield/claim-deferred claim-deferred}}))

(def liquid-lending-module
  (make-liquid-lending-module :yield.provider/liquid-lending))
