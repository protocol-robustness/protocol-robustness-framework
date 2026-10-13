(ns resolver-sim.evidence.strategy
  "Domain-specific constructors for strategy-related evidence."
  (:require [resolver-sim.evidence.aggregate :as agg]))

(defn build-strategy-transition-evidence
  [{:keys [world
           resolver-id
           from-strategy
           to-strategy
           reason
           trigger-event
           attribution]}]
  (agg/build-evidence-aggregate
   {:evidence-type :strategy/transition
    :schema-version "strategy-transition.v1"

    :world world
    :frame (agg/extract-decision-frame world)
    :subject {:resolver-id resolver-id}

    :inputs {:from-strategy from-strategy
             :to-strategy to-strategy
             :reason reason
             :trigger-event trigger-event}

    :result {:active-strategy to-strategy}

    :dependencies []
    :attribution attribution}))
