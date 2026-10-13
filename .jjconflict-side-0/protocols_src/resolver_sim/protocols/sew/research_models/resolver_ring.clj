(ns resolver-sim.protocols.sew.research-models.resolver-ring
  "Resolver collusion ring simulation (Phase F).
   
   Models a coordinated group of resolvers:
   - All lie on all disputes (100% collusion rate)
   - Share profit/loss within the ring
   - Waterfall slashing depletes senior coverage
   
   Metrics:
   - Ring profit = sum of profits across all members
   - Catch rate = % of disputes where ring is caught
   - Member states = individual bond remaining per resolver"
  (:require [resolver-sim.protocols.sew.research-models.delegation :as delegation]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.economics :as econ]))

(defn create-ring
  "Create a resolver ring with given structure.
   
   Example:
   (create-ring {:senior {:bond 10000 :name \"senior-1\"}
                 :juniors [{:bond 1000 :name \"junior-1\"}
                           {:bond 1000 :name \"junior-2\"}
                           {:bond 1000 :name \"junior-3\"}]})"
  [ring-spec]
  (let [senior-spec (:senior ring-spec)
        junior-specs (:juniors ring-spec)
        senior-name (:name senior-spec)
        junior-names (mapv :name junior-specs)

        resolver-specs
        (reduce
         (fn [specs junior-spec]
           (assoc specs (:name junior-spec)
                  {:bond (:bond junior-spec)
                   :delegated-to senior-name
                   :tier :junior}))
         {senior-name {:bond (:bond senior-spec) :tier :senior}}
         junior-specs)]

    {:senior-id senior-name
     :junior-ids junior-names
     :registry (delegation/create-resolver-registry resolver-specs)
     :total-profit 0
     :disputes-total 0
     :disputes-caught 0
     :individual-profits {}}))

(defn simulate-ring-dispute
  "Simulate one dispute for a resolver ring (all collude).
   
   Accepts optional & rest kwargs that are forwarded to resolve-dispute,
   enabling oracle fixtures, :fixed-or, escalation assumptions, etc.
   
   Returns:
   {:ring (updated ring state)
    :caught? (whether ring was caught)
    :profit (profit/loss for ring in this dispute)}"
  [rng ring escrow-wei fee-bps bond-bps slash-mult
   appeal-prob-correct appeal-prob-wrong detection-prob
   & rest-kwargs]

  (let [;; Simulate one resolver (arbitrarily pick senior for now)
        resolver-id (:senior-id ring)

        ;; All collude: use malicious strategy
        result (apply dispute/resolve-dispute
                      rng escrow-wei fee-bps bond-bps slash-mult
                      :malicious
                      appeal-prob-correct appeal-prob-wrong detection-prob
                      rest-kwargs)

        caught? (:slashed? result)
        profit (:profit-malice result)

        ;; If caught, apply waterfall slashing
        updated-registry
        (if caught?
          ;; Calculate slash amount (simplified: use profit-malice loss)
          (let [slash-amount (- (:profit-malice result))]
            ;; Distribute slashing across the ring
            ;; Simple approach: slash the resolver who got caught proportionally
            ;; In reality, junior would be slashed first, then senior coverage
            (delegation/waterfall-slash (:registry ring) resolver-id slash-amount))
          (:registry ring))]

    {:ring
     (assoc ring
            :registry updated-registry
            :total-profit (+ (:total-profit ring) profit)
            :disputes-total (inc (:disputes-total ring))
            :disputes-caught (if caught? (inc (:disputes-caught ring)) (:disputes-caught ring))
            :individual-profits
            (update (:individual-profits ring) resolver-id
                    (fnil + 0) profit))

     :caught? caught?
     :profit profit}))

(defn ring-profitability
  "Analyze ring profitability and member status.
   
   Returns:
   {:total-profit (ring's total profit across all disputes)
    :average-profit-per-dispute
    :catch-rate (% of disputes caught)
    :member-states [{:resolver-id, :bond-remaining, :slashed, :status}]
    :viable? (is ring still profitable?)}"
  [ring]
  (let [total-profit (:total-profit ring)
        num-disputes (:disputes-total ring)
        avg-profit (if (zero? num-disputes) 0 (/ total-profit num-disputes))
        catch-rate (if (zero? num-disputes) 0 (/ (:disputes-caught ring) num-disputes))

        member-states
        (mapv
         (fn [[resolver-id resolver]]
           {:resolver-id resolver-id
            :original-bond (:original-bond resolver)
            :bond-remaining (:bond resolver)
            :slashed-amount (:slashed-amount resolver)
            :tier (:tier resolver)
            :status (:status resolver)})
         (:registry ring))

        ;; Ring is viable if at least one member still has positive bond
        viable? (some #(> (:bond-remaining %) 0) member-states)]

    {:total-profit total-profit
     :average-profit-per-dispute avg-profit
     :catch-rate catch-rate
     :num-disputes num-disputes
     :member-states member-states
     :viable? viable?
     :ring-profitable? (pos? total-profit)
     :ring-solvent? viable?
     :senior-exhausted? (let [senior (get (:registry ring) (:senior-id ring))]
                          (<= (:bond senior) 0))}))
