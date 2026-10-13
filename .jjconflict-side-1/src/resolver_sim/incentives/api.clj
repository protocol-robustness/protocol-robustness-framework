(ns resolver-sim.incentives.api
  "Stable in-tree researcher-incentive contract surface.

   This façade delegates to the authoritative reward implementation. It does
   not validate research claims independently, create payment authority, or
   define a second reward lifecycle."
  (:require [resolver-sim.benchmark.researcher-reward :as reward]))

(def build-reward-policy reward/build-policy)
(def evaluate-reward-eligibility reward/evaluate)
(def valid-reward-eligibility? reward/valid-eligibility?)
(def build-reward-proposal reward/build-proposal)
(def valid-reward-proposal? reward/valid-proposal?)
(def build-reward-obligation reward/build-obligation)
(def valid-reward-obligation? reward/valid-obligation?)
(def verify-adversarial-finding-lineage reward/verify-adversarial-finding-lineage)
