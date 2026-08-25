(ns resolver-sim.benchmark.governed-authority-state-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.benchmark.governed-authority-state :as state]))

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(defn- material [_state-root]
  {:chain-instance-genesis/root (hash-ref "1")
   :chain-configuration/root (hash-ref "2")
   :review-governance/root (hash-ref "3")
   :review-governance-activation/root (hash-ref "4")
   :control-plane-evidence/root (hash-ref "5")
   :review-round/hash (hash-ref "6")
   :review-round/root (hash-ref "7")
   :position-time-basis/root (hash-ref "8")
   :review-governance-admissibility/root (hash-ref "9")})

(defn- envelope [state-root predecessor sequence]
  {:chain-instance-genesis/root (hash-ref "1")
   :execution/state-root state-root
   :chain-configuration/root (hash-ref "2")
   :review-governance/root (hash-ref "3")
   :review-governance-activation/root (hash-ref "4")
   :configuration-head/root (hash-ref "a")
   :control-plane-evidence/root (hash-ref "5")
   :publication/sequence sequence
   :publication/predecessor-root predecessor})

(defn- basis [purpose state-root anchor]
  (resolution/build-resolution-basis
   {:resolution/purpose purpose :chain-instance-genesis/root (hash-ref "1")
    :resolution/state-before-root state-root :resolution/anchor-root anchor
    :review-round/hash (hash-ref "6")}))

(deftest authenticated-state-resolution
  (let [s0 (hash-ref "b")
        e0 (state/build-envelope (envelope s0 nil 0))
        store (state/new-store e0 (material s0))
        b0 (basis :current-admission s0 (:authoritative-state-envelope/root e0))]
    (is (:resolved? (state/resolve-governed-authority-context store b0)))
    (let [s1 (hash-ref "c")
          e1 (state/build-envelope
              (envelope s1 (:authoritative-state-envelope/root e0) 1))]
      (is (:published? (state/publish-successor!
                        store (:authoritative-state-envelope/root e0) e1 (material s1))))
      (is (= :state-not-at-required-head
             (:reason (state/resolve-governed-authority-context store b0))))
      (is (:resolved? (state/resolve-governed-authority-context
                       store (basis :transition-replay s0
                                    (:authoritative-state-envelope/root e1))))))))

(deftest rejects-state-and-material-substitution
  (let [s0 (hash-ref "b")
        e0 (state/build-envelope (envelope s0 nil 0))
        store (state/new-store e0 (material s0))]
    (is (= :state-unavailable
           (:reason (state/resolve-governed-authority-context
                     store (basis :historical-audit (hash-ref "f")
                                  (:authoritative-state-envelope/root e0))))))
    (let [different-round-store (state/new-store
                                 e0 (assoc (material s0) :review-round/hash (hash-ref "e")))]
      (is (= :round-not-found-at-state
             (:reason (state/resolve-governed-authority-context
                       different-round-store
                       (basis :historical-audit s0
                              (:authoritative-state-envelope/root e0)))))))))
