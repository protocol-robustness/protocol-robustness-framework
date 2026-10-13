(ns notebooks.util.yield-demo
  "Illustrative yield module shortfall analysis adapter.

   This is NOT the real framework runner. It is a simple sequential
   event processor built for notebook demonstration purposes.

   It uses resolver-sim.yield.partial-fill/calculate-fulfillment for
   per-user settlement decisions, but the pool allocation logic
   (how available liquidity is divided among concurrent withdrawal
   requests) is a custom proportional model.

   Label: illustrative model using real partial-fill engine."
  (:require [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.position :as pos]
            [clojure.string :as str]))

(def default-module-state
  {:total-principal 0
   :available-liquidity 0
   :reserved 0
   :shortfall? false
   :available-ratio 1.0
   :fulfilled-total 0
   :deferred-total 0
   :positions {}
   :outcomes []
   :events []
   :snapshots []
   :phase-label "initial"})

(defn make-position
  "Create a yield position for a user with a given principal.
   Uses the real position schema so calculate-fulfillment works correctly."
  [user-id amount]
  (pos/make-position {:owner/id (name user-id)
                      :module/id :gen-yield
                      :token :USDC
                      :principal (long amount)}))

(defn snapshot
  "Capture a module-state snapshot at an event boundary."
  [state]
  {:phase (:phase-label state)
   :total-principal (:total-principal state)
   :available-liquidity (:available-liquidity state)
   :reserved (:reserved state)
   :shortfall? (:shortfall? state)
   :available-ratio (:available-ratio state)
   :fulfilled-total (:fulfilled-total state)
   :deferred-total (:deferred-total state)})

(defn total-remaining-principal
  "Sum of principal across all positions still active (not yet processed)."
  [state]
  (reduce + 0
    (for [[_user pos] (:positions state)
          :when (= :active (:status pos))]
      (:principal pos))))

(defn total-outstanding
  "Sum of principal remaining in ALL positions (active + unwinding)."
  [state]
  (reduce + 0 (map :principal (vals (:positions state)))))

(defn apply-event
  "Process one event against the module state.
   Returns updated state map.
   Events: :deposit, :accrue, :set-liquidity-shortfall, :withdraw-request"
  [state event]
  (let [event-type (:event event)]
    (case event-type
      :deposit
      (let [user (:user event)
            amount (long (:amount event))]
        (-> state
            (assoc :phase-label (str "after " (name user) " deposit"))
            (assoc-in [:positions user] (make-position user amount))
            (update :total-principal + amount)
            (update :available-liquidity + amount)
            (update :events conj event)))

      :accrue
      (let [positions (:positions state)]
        (-> state
            (assoc :phase-label "after yield accrual")
            (update :events conj event)))

      :set-liquidity-shortfall
      (let [ratio (double (:available-ratio event))
            total (:total-principal state)
            gross-available (long (* total ratio))
            already-fulfilled (:fulfilled-total state 0)
            available (max 0 (- gross-available already-fulfilled))
            reserved (- total gross-available)
            ;; Re-activate unwinding positions so their remaining principal
            ;; is counted in the new withdrawal round
            positions (reduce-kv (fn [acc user pos]
                                   (if (= :unwinding (:status pos))
                                     (assoc acc user (assoc pos :status :active))
                                     (assoc acc user pos)))
                                 {}
                                 (:positions state))]
        (let [updated (-> state
                          (assoc :phase-label (str "after shortfall (" (int (* ratio 100)) "% liquid)"))
                          (assoc :shortfall? true
                                 :available-ratio ratio
                                 :available-liquidity available
                                 :reserved reserved
                                 :positions positions)
                          (update :events conj event))]
          (assoc updated :deferred-total (total-outstanding updated))))

      :withdraw-request
      (let [user (:user event)
            requested (long (:requested event))
            pool (:available-liquidity state)
            pos (get-in state [:positions user])
            remaining-total (total-remaining-principal state)
            user-principal (long (:principal pos 0))

            ;; Proportional share: this user's fraction of remaining
            ;; principal determines their share of the available pool.
            share-ratio (if (pos? remaining-total)
                          (/ (double user-principal) (double remaining-total))
                          1.0)
            user-pool-share (long (* pool share-ratio))
            effective-liquidity (min pool user-pool-share requested)

            ;; Cap position principal at the user's requested amount so
            ;; calculate-fulfillment doesn't try to fulfill beyond what
            ;; the user asked for.
            capped-pos (assoc pos :principal (min (long (:principal pos 0)) requested))
            settlement (pf/calculate-fulfillment effective-liquidity capped-pos)
            filled (reduce + 0 (vals (:filled settlement {})))
            deferred (reduce + 0 (vals (:deferred settlement {})))]

        (if (nil? pos)
          state
          (let [wave (inc (count (filter #(= (:user %) user) (:outcomes state))))
                filled-principal (long (get-in settlement [:filled :principal] 0))
                outcome {:user user
                         :wave wave
                         :requested requested
                         :filled filled
                         :deferred deferred
                         :fill-pct (if (pos? requested)
                                     (double (/ filled requested))
                                     0.0)
                         :status (cond
                                   (zero? filled) :deferred
                                   (< filled requested) :partial-fill
                                   :else :full-fill)
                         :shortfall-affected? (pos? deferred)}
                settlement-map {:requested (:requested settlement)
                                :filled (:filled settlement)
                                :deferred (:deferred settlement)
                                :mode (:settlement-mode settlement)}]
            (let [updated (-> state
                              (assoc :phase-label (str "after " (name user) " withdrawal"))
                              (update :available-liquidity - filled)
                              (update :fulfilled-total + filled)
                              (update :outcomes conj outcome)
                              (assoc-in [:settlements user] settlement-map)
                              (update-in [:positions user :principal] - filled-principal)
                              (assoc-in [:positions user :status] (if (pos? deferred)
                                                                    :unwinding
                                                                    :withdrawn))
                              (update :events conj event))]
              (assoc updated :deferred-total (total-outstanding updated))))))

      state)))

(defn run-scenario
  "Run a sequence of events through the module state machine.
   Returns a map with :snapshots, :outcomes, :final-state, and :events."
  [events]
  (let [initial (assoc default-module-state :phase-label "initial")]
    (loop [state initial
           remaining events
           snaps [(snapshot initial)]]
      (if (empty? remaining)
        {:snapshots snaps
         :outcomes (:outcomes state)
         :settlements (:settlements state)
         :final-state state
         :events (:events state)}
        (let [event (first remaining)
              state' (apply-event state event)
              snaps' (conj snaps (snapshot state'))]
          (recur state' (rest remaining) snaps'))))))

(defn module-snapshots
  "Extract module-level table rows from snapshots vector.
   Returns a Clerk-compatible {:head [...] :rows [[...] ...]} map."
  [snapshots]
  {:head [:t :phase :total-principal :available-liquidity :reserved :shortfall :fulfilled :deferred]
   :rows (mapv (fn [s]
                 [(str (->> snapshots (take-while #(not= s %)) count))
                  (:phase s)
                  (str (:total-principal s) " USDC")
                  (str (:available-liquidity s) " USDC")
                  (str (:reserved s) " USDC")
                  (if (:shortfall? s) "YES" "no")
                  (if (:fulfilled-total s) (str (:fulfilled-total s) " USDC") "—")
                  (if (:deferred-total s) (str (:deferred-total s) " USDC") "—")])
               snapshots)
   :row-keys [:t :phase :total-principal :available-liquidity :reserved :shortfall :fulfilled :deferred]})

(defn withdrawal-outcomes
  "Extract per-user withdrawal outcome rows from the outcomes vector.
   Returns a Clerk-compatible {:head [...] :rows [[...] ...]} map.
   When a :user filter is given, only returns outcomes for those users."
  ([outcomes] (withdrawal-outcomes outcomes nil))
  ([outcomes user-filter]
   (let [xs (if user-filter
              (filter #(contains? (set user-filter) (:user %)) outcomes)
              outcomes)
         sorted (sort-by (juxt :user :wave) xs)]
     {:head [:user :requested :fulfilled :fill-pct :deferred :status :shortfall-affected]
      :rows (mapv (fn [m]
                    [(name (:user m))
                     (str (:requested m) " USDC")
                     (str (:filled m) " USDC")
                     (str (format "%.0f" (* 100 (:fill-pct m))) "%")
                     (str (:deferred m) " USDC")
                     (name (:status m))
                     (if (:shortfall-affected? m) "YES" "no")])
                  sorted)
      :row-keys [:user :requested :fulfilled :fill-pct :deferred :status :shortfall-affected]})))

(defn shortfall-summary
  "Build a final shortfall summary map from the outcomes vector."
  [result]
  (let [outcomes (:outcomes result)
        total-req (reduce + 0 (map :requested outcomes))
        total-filled (reduce + 0 (map :filled outcomes))
        total-deferred (reduce + 0 (map :deferred outcomes))
        users (count (distinct (map :user outcomes)))]
    {"Total requested" (str total-req " USDC")
     "Total fulfilled" (str total-filled " USDC")
     "Overall fill rate" (str (format "%.1f" (double (* 100 (/ total-filled (max 1 total-req))))) "%")
     "Total deferred" (str total-deferred " USDC")
     "Deferred rate" (str (format "%.1f" (double (* 100 (/ total-deferred (max 1 total-req))))) "%")
     "Shortfall-affected outcomes" (str (count (filter :shortfall-affected? outcomes)) " of " (count outcomes))
     "Unresolved amount" (str total-deferred " USDC")
     "Recovery possible?" "Yes — deferred amounts tracked for later claim"}))

(defn event-timeline
  "Build event timeline table rows from event log.
   Returns a Clerk-compatible {:head [...] :rows [[...] ...]} map."
  [events]
  (let [rows (mapv (fn [e i]
                     (let [label (case (:event e)
                                   :deposit "Deposit"
                                   :accrue "Accrue yield"
                                   :set-liquidity-shortfall "Set shortfall"
                                   :withdraw-request "Withdraw request"
                                   (name (:event e)))
                           actor (if (:user e) (name (:user e)) "module")
                           detail (case (:event e)
                                    :deposit (str "+" (:amount e) " USDC")
                                    :accrue "5% APY for 1 year"
                                    :set-liquidity-shortfall (str "available-ratio = " (:available-ratio e))
                                    :withdraw-request (str "requested " (:requested e) " USDC")
                                    (str e))]
                       [(str i) label actor detail]))
                   events (range))]
    {:head [:t :event :actor :detail]
     :rows rows
     :row-keys [:t :event :actor :detail]}))
