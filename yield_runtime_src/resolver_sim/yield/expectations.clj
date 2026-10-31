(ns resolver-sim.yield.expectations
  "World-level expectation checkers for yield scenarios."
  (:require [resolver-sim.yield.evidence :as yield-evi]))

(defn- check-yield-expectation [world exp terminal?]
  (let [total-yield (reduce + (vals (:total-yield-generated world {})))
        min-y (:min-accrued-yield exp 0)
        max-y (:max-accrued-yield exp Double/MAX_VALUE)]
    (cond
      ;; Max yield is an 'always' check
      (> total-yield max-y)
      {:holds? false :error :yield-outside-tolerance :actual total-yield :expected max-y :direction :above}

      ;; Min yield is a 'finally' check
      (and terminal? (< total-yield min-y))
      {:holds? false :error :yield-outside-tolerance :actual total-yield :expected min-y :direction :below}

      :else {:holds? true})))

(defn- check-partial-liquidity-expectation [world exp terminal?]
  (let [positions (:yield/positions world {})
        shortfalls (keep :shortfall (vals positions))
        reclaimed (reduce + (keep :reclaimed-amount (vals positions)))
        actual-shortfall (+ (reduce + (map :deferred-amount shortfalls))
                            reclaimed)
        expected-sf (:expected-shortfall exp {})
        min-sf (:min expected-sf 0)
        max-sf (:max expected-sf Double/MAX_VALUE)]
    (cond
      ;; Max shortfall is an 'always' check
      (> actual-shortfall max-sf)
      {:holds? false :error :shortfall-outside-tolerance :actual actual-shortfall :expected max-sf :direction :above}

      ;; Min shortfall is a 'finally' check
      (and terminal? (< actual-shortfall min-sf))
      {:holds? false :error :shortfall-outside-tolerance :actual actual-shortfall :expected min-sf :direction :below}

      :else {:holds? true})))

(defn- all-terminal?
  "Check if all entities in the world are in terminal states.
   A world is terminal if all entities (escrows, yield positions) are resolved."
  [world]
  (let [transfers (:escrow-transfers world)
        positions (:yield/positions world)
        ;; Check for Sew escrows if they exist
        escrows-done? (if (seq transfers)
                        (let [terminal-states #{:none :released :refunded :resolved}]
                          (every? #(contains? terminal-states (:escrow-state (val %))) transfers))
                        true)
        ;; Check for yield positions
        yields-done?  (if (seq positions)
                        (not-any? #(= (:status (val %)) :unwinding) positions)
                        true)]
    (and escrows-done? yields-done?)))

(defn- check-loss-expectation [world exp terminal?]
  (let [tokens (keys (:total-principal-deposited world {}))
        actual-loss (reduce + (map #(yield-evi/sum-recognized-losses world %) tokens))
        min-loss (:min-recognized-principal-loss exp 0)
        max-loss (:max-recognized-principal-loss exp Double/MAX_VALUE)]
    (cond
      (> actual-loss max-loss)
      {:holds? false :error :loss-outside-tolerance :actual actual-loss :expected max-loss :direction :above}

      (and terminal? (< actual-loss min-loss))
      {:holds? false :error :loss-outside-tolerance :actual actual-loss :expected min-loss :direction :below}

      :else {:holds? true})))

(defn check-expectations
  "Check all yield expectations for the current world state."
  [world]
  (let [exp (get-in world [:params :expectations])
        terminal? (all-terminal? world)]
    (if (nil? exp)
      {:ok? true}
      (let [y-res  (when (:yield exp) (check-yield-expectation world (:yield exp) terminal?))
            pl-res (when (:partial-liquidity exp) (check-partial-liquidity-expectation world (:partial-liquidity exp) terminal?))
            l-res  (when (:losses exp) (check-loss-expectation world (:losses exp) terminal?))
            results (cond-> {}
                      y-res (assoc :yield y-res)
                      pl-res (assoc :partial-liquidity pl-res)
                      l-res (assoc :losses l-res))]
        {:ok? (every? :holds? (vals results))
         :results results}))))