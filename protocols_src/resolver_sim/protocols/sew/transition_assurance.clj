(ns resolver-sim.protocols.sew.transition-assurance
  "Canonical model-state commitments and invariant-backed Sew replay assurance.

   This is a model replay claim. It does not establish live-chain execution,
   external signatures, or custody. Replay clock fields are committed through
   event roots rather than state roots so consecutive action frames join.
   Runtime callback markers name a defining function class; they do not commit
   executable bytecode or closure captures."
  (:require [resolver-sim.hash.canonical :as canonical]
            [clojure.string :as str]
            [resolver-sim.hash.reference :as ref])
  (:import [java.math BigDecimal]
           [java.time.temporal TemporalAccessor]
           [java.util Date]))

(def schema :prf/sew-transition-assurance-basis.v1)

(declare canonical-compare)

(defn- project-value [value]
  (cond
    (fn? value) {:runtime/function-class
                 (str/replace (.getName (class value)) #"\$fn__\d+.*$" "")}
    (or (instance? Double value) (instance? Float value))
    (let [number (double value)]
      (if (Double/isFinite number)
        {:runtime/float (.toPlainString (BigDecimal/valueOf number))}
        {:runtime/float (str number)}))
    (instance? TemporalAccessor value) (.toString ^TemporalAccessor value)
    (instance? Date value) (.toString (.toInstant ^Date value))
    (map? value) (into {} (map (fn [[key item]] [(project-value key) (project-value item)])) value)
    (set? value) (->> value (map project-value) (sort canonical-compare) vec)
    (vector? value) (mapv project-value value)
    (sequential? value) (mapv project-value value)
    :else value))

(defn- sha256-ref [domain value]
  (ref/sha256-ref (canonical/domain-hash domain (project-value value))))

(defn- canonical-compare [left right]
  (java.util.Arrays/compareUnsigned (canonical/canonical-bytes left)
                                    (canonical/canonical-bytes right)))

(defn- transition-root [basis]
  (sha256-ref :sew-dispute-transition-basis-v1
              (select-keys basis [:transition/schema :state-before/root
                                  :state-after/root :event/root])))

(defn- build-expected [world-before world-after event]
  (let [basis {:transition/schema schema
               ;; Replay advances its logical clock between action frames. Event
               ;; roots carry that timestamp; state roots cover protocol state.
               :state-before/root (sha256-ref :sew-dispute-transition-state-v1
                                              (dissoc world-before :context/time :block-time))
               :state-after/root (sha256-ref :sew-dispute-transition-state-v1
                                             (dissoc world-after :context/time :block-time))
               :event/root (sha256-ref :sew-dispute-transition-event-v1 event)}
        root (transition-root basis)]
    (assoc basis :transition/root root :root root)))

(defn build [world-before world-after event]
  (build-expected world-before world-after event))

(defn validate
  [world-before world-after event basis _before-check after-check transition-check]
  (let [expected (build-expected world-before world-after event)
        violations (cond-> []
                     (not= expected basis) (conj {:reason :basis-reconstruction-mismatch})
                     (not (:ok? after-check))
                     (conj {:reason :state-after-invariants-failed
                            :violations (:violations after-check)})
                     (not (:ok? transition-check))
                     (conj {:reason :transition-invariants-failed
                            :violations (:violations transition-check)}))]
    {:ok? (empty? violations)
     :violations violations
     :not-evaluated {:state-before :bound-only
                     :state-after (:not-evaluated-invariants after-check)}}))
