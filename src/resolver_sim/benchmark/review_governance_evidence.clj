(ns resolver-sim.benchmark.review-governance-evidence
  "Canonical temporal evidence for governed review authority."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(defn- root [domain value] (ref/sha256-ref (hc/domain-hash domain value)))
(defn acceptance-root [x] (root :review-position-acceptance-v1 (dissoc x :review-position-acceptance/root)))
(defn valid-acceptance? [x]
  (and (= "review-position-acceptance.v1" (:schema-version x))
       (ref/valid-sha256-ref? (:review-round/root x))
       (ref/valid-sha256-ref? (:position/root x))
       (string? (:accepted-at x)) (integer? (:acceptance-sequence x))
       (ref/valid-sha256-ref? (:acceptance-source/root x))
       (= (:review-position-acceptance/root x) (acceptance-root x))))
(defn position-time-basis-root [x] (root :position-time-basis-v1 (dissoc x :position-time-basis/root)))
(defn valid-position-time-basis? [x acceptances]
  (let [rs (mapv :review-position-acceptance/root (sort-by :position/root acceptances))]
    (and (= "position-time-basis.v1" (:schema-version x))
         (every? valid-acceptance? acceptances)
         (= (count rs) (count (set rs)))
         (= rs (:position-acceptance-roots x))
         (= (:position-time-basis/root x) (position-time-basis-root x)))))
(defn admissibility-root [x] (root :review-governance-admissibility-v1 (dissoc x :review-governance-admissibility/root)))
(defn valid-admissibility? [x]
  (let [ok? (and (= (:round-chain-configuration/root x) (:authoritative-chain-configuration/root x))
                 (= (:round-governance/root x) (:authoritative-review-governance/root x)))]
    (and (= "review-governance-admissibility.v1" (:schema-version x))
         (every? ref/valid-sha256-ref? (vals (select-keys x [:review-round/root :round-chain-configuration/root :round-governance/root :authoritative-chain-configuration/root :authoritative-review-governance/root :control-plane-evidence/root])))
         (string? (:checked-at x)) (= ok? (:admissible? x))
         (= (:review-governance-admissibility/root x) (admissibility-root x)))))
