(ns resolver-sim.notebook-support.speds.semantics
  "SPEDS-local semantic mappings built on top of shared outcome semantics."
  (:require [clojure.string :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.scenario.outcome-semantics :as ose]))

(defn purpose->kind [purpose]
  (defs/purpose->kind (ose/normalize-purpose purpose)))

(defn purpose->story-family-str [purpose]
  (-> (defs/purpose->default-story-family (ose/normalize-purpose purpose))
      name
      (str/replace "-" "_")))

(defn purpose->story-family-kw [purpose]
  (defs/purpose->default-story-family (ose/normalize-purpose purpose)))

(defn classification-for-purpose [purpose]
  (defs/purpose->classification (ose/normalize-purpose purpose)))
