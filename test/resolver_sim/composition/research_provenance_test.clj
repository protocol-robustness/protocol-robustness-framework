(ns resolver-sim.composition.research-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.composition.command-lineage :as lineage]
            [resolver-sim.composition.research-provenance :as provenance]))

(def s0 "sha256:0000000000000000000000000000000000000000000000000000000000000000")
(def s1 "sha256:1111111111111111111111111111111111111111111111111111111111111111")
(def s2 "sha256:2222222222222222222222222222222222222222222222222222222222222222")

(defn- command
  [action input resulting]
  (lineage/build-command
   {:command/action action
    :command/input-state-root input
    :command/resulting-state-root resulting
    :command/built-with-includes [{:kind :shared-state :ref input}]}))

(defn- fixture []
  (let [a (command :research/a s0 s1)
        b (command :research/b s1 s2)
        c (command :research/c s2 s0)
        commands [a b c]]
    {:commands commands
     :concatenations (lineage/build-concatenation-chain commands)
     :combination (lineage/build-combination (:command/built-with-includes c))}))

(defn- failure-reason
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:reason (ex-data e)))))

(deftest verified-provenance-returns-canonical-command-and-lineage-roots
  (let [{:keys [commands concatenations] :as input} (fixture)
        result (provenance/verified-executable-command-provenance! input)
        terminal (peek commands)]
    (is (= (:command/root terminal) (:command/root result)))
    (is (= (lineage/combination-root (:command/built-with-includes terminal))
           (:command/combination-root result)))
    (is (= (:command/input-state-root terminal) (:command/input-state-root result)))
    (is (= (:command/resulting-state-root terminal) (:command/resulting-state-root result)))
    (is (= (mapv :concatenation/root concatenations)
           (:command/concatenation-roots result)))
    (is (= (lineage/concatenation-chain-root (mapv :concatenation/root concatenations))
           (:command/concatenation-chain-root result)))))

(deftest provenance-fails-closed-on-substituted-include-combination
  (let [{:keys [combination] :as input} (fixture)
        substituted (assoc combination
                           :combination/built-with-includes [{:kind :shared-state :ref s1}]
                           :combination/root
                           (lineage/combination-root [{:kind :shared-state :ref s1}]))]
    (is (= :include-composition-substitution
           (failure-reason
            #(provenance/verified-executable-command-provenance!
              (assoc input :combination substituted)))))))

(deftest provenance-fails-closed-on-reordered-consecutive-artifacts
  (let [{:keys [concatenations] :as input} (fixture)]
    (is (= :reordered-concatenations
           (failure-reason
            #(provenance/verified-executable-command-provenance!
              (assoc input :concatenations (vec (reverse concatenations)))))))))

(deftest provenance-fails-closed-on-broken-concatenation-continuity
  (let [{:keys [concatenations] :as input} (fixture)
        broken (assoc (first concatenations) :concatenation/join-state s2)
        broken (assoc broken :concatenation/root (lineage/concatenation-root broken))]
    (is (= :broken-concatenation-continuity
           (failure-reason
            #(provenance/verified-executable-command-provenance!
              (assoc input :concatenations (assoc concatenations 0 broken))))))))
