(ns resolver-sim.protocols.sew.advisory
  "Sew-specific advisory analysis functions.

   These functions read canonical world state and return lightweight analysis
   results (action suggestions, risk signals, payoff projections) without
   executing any state transitions.

   All functions accept a world map and a context map; the session layer is
   responsible for looking up the session and extracting world + context before
   calling these.

   Exposed via DisputeProtocol/advisory in resolver-sim.protocols.sew."
  (:require [resolver-sim.protocols.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; suggest-actions
;; ---------------------------------------------------------------------------

(defn suggest-actions
  "Return action template suggestions for actor-id given the current world.

   context keys:
     :actor-id    — the acting agent's string identifier
     :agent-index — the full agent-index map from the session context

   Returns {:active-workflow-ids [...] :suggested-actions [...]}"
  [world {:keys [actor-id agent-index]}]
  (let [transfers        (get world :escrow-transfers {})
        ids              (vec (keys transfers))
        pending-settlements (get world :pending-settlements {})
        pending-slashes  (get world :pending-fraud-slashes {})
        actor            (get agent-index actor-id)
        actor-addr       (:address actor)
        actor-type       (some-> (or (:role actor) (:type actor)) name)
        governance?      (or (= actor-id "governance") (= actor-type "governance"))
        keeper?          (or (= actor-id "keeper")     (= actor-type "keeper"))
        resolver?        (or (= actor-id "resolver")   (= actor-type "resolver"))
        templates
        (vec
         (concat
          (when actor
            (let [counterparty (->> agent-index vals
                                    (remove #(= (:id %) actor-id))
                                    first)]
              [{:actor-id actor-id
                :action "create_escrow"
                :params {:token "USDC"
                         :to (or (:address counterparty) "0xseller")
                         :amount 5000}}]))

          (mapcat
           (fn [[wf et]]
             (let [st            (:escrow-state et)
                   from          (:from et)
                   to            (:to et)
                   resolver-addr (:dispute-resolver et)]
               (cond
                 (= st :pending)
                 (concat
                  (when (= actor-addr from)
                    [{:actor-id actor-id :action "sender_cancel"    :params {:id wf}}
                     {:actor-id actor-id :action "release"          :params {:id wf}}
                     {:actor-id actor-id :action "raise_dispute"    :params {:id wf}}])
                  (when (= actor-addr to)
                    [{:actor-id actor-id :action "recipient_cancel" :params {:id wf}}
                     {:actor-id actor-id :action "raise_dispute"    :params {:id wf}}]))

                 (= st :disputed)
                 (concat
                  (when (or (= actor-addr resolver-addr) resolver?)
                    [{:actor-id actor-id :action "execute_resolution"
                      :params {:workflow-id wf :is-release true  :resolution-hash "0xadv"}}
                     {:actor-id actor-id :action "execute_resolution"
                      :params {:workflow-id wf :is-release false :resolution-hash "0xadv"}}])
                  (when governance?
                    [{:actor-id actor-id :action "propose_fraud_slash"
                      :params {:workflow-id wf
                               :resolver-addr (or resolver-addr "0xresolver")
                               :amount (max 1 (quot (long (or (:amount-after-fee et) 0)) 10))}}])
                  (when keeper?
                    [{:actor-id actor-id :action "execute_pending_settlement"
                      :params {:workflow-id wf}}]))

                 :else [])))
           transfers)

          (when governance?
            (mapcat
             (fn [[slash-id slash]]
               (let [status (:status slash)]
                 (cond
                   (= status :pending)
                   [{:actor-id actor-id :action "execute_fraud_slash"
                     :params {:workflow-id slash-id}}
                    {:actor-id actor-id :action "resolve_appeal"
                     :params {:workflow-id slash-id :upheld? true}}
                    {:actor-id actor-id :action "resolve_appeal"
                     :params {:workflow-id slash-id :upheld? false}}]
                   (= status :appealed)
                   [{:actor-id actor-id :action "resolve_appeal"
                     :params {:workflow-id slash-id :upheld? true}}
                    {:actor-id actor-id :action "resolve_appeal"
                     :params {:workflow-id slash-id :upheld? false}}]
                   :else [])))
             pending-slashes))

          (when resolver?
            (mapcat
             (fn [[slash-id slash]]
               (when (= (:status slash) :pending)
                 [{:actor-id actor-id :action "appeal_slash"
                   :params {:workflow-id slash-id}}]))
             pending-slashes))

          (when keeper?
            (for [[wf pending] pending-settlements
                  :when (:exists pending)]
              {:actor-id actor-id
               :action "execute_pending_settlement"
               :params {:workflow-id wf}}))))]
    {:active-workflow-ids ids
     :suggested-actions   templates}))

;; ---------------------------------------------------------------------------
;; session-signals
;; ---------------------------------------------------------------------------

(defn session-signals
  "Return read-only risk/economic signals from world state.

   Returns a flat map of Sew-specific signal fields."
  [world _context]
  (let [transfers     (get world :escrow-transfers {})
        pending-slashes (get world :pending-fraud-slashes {})]
    {:block-time            (get world :block-time 0)
     :active-workflow-ids   (vec (keys transfers))
     :pending-count         (get world :pending-count 0)
     :pending-fraud-slashes (into {}
                                  (map (fn [[slash-id v]]
                                         [slash-id {:resolver      (:resolver v)
                                                    :amount        (get v :amount 0)
                                                    :status        (some-> (:status v) name)
                                                    :appeal-deadline (get v :appeal-deadline 0)
                                                    :proposed-at   (get v :proposed-at 0)}])
                                       pending-slashes))
     :resolver-slash-total  (get world :resolver-slash-total {})
     :resolver-frozen-until (get world :resolver-frozen-until {})
     :total-fees            (get world :total-fees {})
     :total-held            (get world :total-held {})
     :resolver-stakes       (get world :resolver-stakes {})}))

;; ---------------------------------------------------------------------------
;; evaluate-payoff
;; ---------------------------------------------------------------------------

(defn evaluate-payoff
  "Return a realised payoff projection for actor-id.

   context keys: {:actor-id string}

   Returns {:stake-locked :slash-loss-realized :claimable :bond-locked :net-pnl}"
  [world {:keys [actor-id]}]
  (let [stakes    (get world :resolver-stakes {})
        claimable (get world :claimable {})
        bonds     (get world :bond-balances {})
        staked    (get stakes actor-id 0)
        claim     (reduce + 0 (for [[_ wc] claimable] (get wc actor-id 0)))
        bonded    (reduce + 0 (for [[_ wb] bonds] (get wb actor-id 0)))]
    {:actor-id           actor-id
     :stake-locked       staked
     :slash-loss-realized (get (get world :resolver-slash-total {}) actor-id 0)
     :claimable          claim
     :bond-locked        bonded
     :net-pnl            (+ staked claim bonded)}))

;; ---------------------------------------------------------------------------
;; evaluate-attack-objective
;; ---------------------------------------------------------------------------

(defn evaluate-attack-objective
  "Evaluate objective-oriented score for adversarial strategy search.

   context keys: {:actor-id string :objective string-or-nil}
   objective defaults to \"resolver_fraud_profit\".

   Returns {:score n :decomposition {...}}"
  [world {:keys [actor-id objective]}]
  (let [objective'  (or objective "resolver_fraud_profit")
        stakes      (get world :resolver-stakes {})
        slashed     (get world :resolver-slash-total {})
        claimable   (get world :claimable {})
        bonds       (get world :bond-balances {})
        staked      (get stakes actor-id 0)
        slash-loss  (get slashed actor-id 0)
        claim       (reduce + 0 (for [[_ wc] claimable] (get wc actor-id 0)))
        bonded      (reduce + 0 (for [[_ wb] bonds] (get wb actor-id 0)))
        net-profit  (- (+ staked claim bonded) slash-loss)
        slash-pending (reduce + 0
                              (for [[_ p] (get world :pending-fraud-slashes {})
                                    :when (= actor-id (:resolver p))]
                                (get p :amount 0)))
        score (case objective'
                "resolver_fraud_profit" net-profit
                net-profit)]
    {:actor-id   actor-id
     :objective  objective'
     :score      score
     :decomposition {:stake-locked        staked
                     :claimable           claim
                     :bond-locked         bonded
                     :slash-loss-realized slash-loss
                     :slash-loss-pending  slash-pending
                     :net-resolver-profit net-profit}}))
