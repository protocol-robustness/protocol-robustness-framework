(ns resolver-sim.accessibility.derivation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accessibility.derivation :as sut]
            [resolver-sim.benchmark.distributed.fixed-chunks :as fixed]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-semantics :as compilation-semantics]
            [resolver-sim.pro-rata.effect-compilation-v3 :as compilation-v3]
            [resolver-sim.pro-rata.target-map :as target-map]))

(defn- root [digit] (str "sha256:" (apply str (repeat 64 digit))))

(def plan
  [{:execution/ordinal 1 :execution/id "a" :execution/descriptor {:id "a"}
    :scenario/input-root (root "a")}
   {:execution/ordinal 2 :execution/id "b" :execution/descriptor {:id "b"}
    :scenario/input-root (root "b")}])

(deftest addresses-preserve-root-and-state-domains
  (let [r (sut/root-ref {:subject/schema :example/artifact :subject/root (root "a")})
        s (sut/state-ref {:state/type :example/state :state/root (root "a")})]
    (is (sut/address? r))
    (is (sut/address? s))
    (is (not= r s))
    (is (= :root (:ref/kind r)))
    (is (= :state (:ref/kind s)))))

(deftest fixed-chunks-expose-committed-reproduction-inputs
  (let [chunk-set (fixed/derive-fixed-chunk-set
                   plan {:chunk-size 2 :sensitivity-root (root "c")
                         :executable-distribution-root (root "d")})
        basis (sut/basis-of chunk-set)]
    (is (= :fixed-chunk-set (:derivation/kind basis)))
    (is (:derivation/reproducible? basis))
    (is (= (:chunk-set/root chunk-set) (get-in basis [:derivation/output :subject/root])))
    (is (= 2 (:value (nth (:derivation/inputs basis) 1))))
    (is (= (root "d") (get-in (nth (:derivation/inputs basis) 3) [:ref :subject/root])))
    (is (= (:chunk-set/root chunk-set) (fixed/chunk-set-root chunk-set)))))

(deftest state-transition-replays-with-verified-addresses
  (let [liquidity (root "1")
        filled (root "2")
        state-before {liquidity 100 filled 0}
        raw-effects [(effects/delta liquidity -25) (effects/delta filled 25)]
        transition (effects/transition state-before raw-effects)
        state-before-ref (sut/state-ref {:state/type effects/state-schema
                                         :state/root (:state-before/root transition)})
        effects-ref (sut/root-ref {:subject/schema effects/effect-schema
                                   :subject/root (:effects/root transition)})
        resolver {state-before-ref state-before effects-ref (:effects transition)}]
    (is (= :valid (:status (sut/verify-ref state-before-ref state-before))))
    (is (= :valid (:status (sut/verify-ref effects-ref (:effects transition)))))
    (is (= :valid (:status (sut/verify-derived transition #(get resolver %)))))))

(deftest retrieval-is-not-identity-verification
  (let [address (sut/state-ref {:state/type effects/state-schema :state/root (root "1")})
        candidate {(root "2") 10}]
    (is (= candidate (sut/retrieve (constantly candidate) address)))
    (is (not= :valid (:status (sut/verify-ref address candidate))))))

(deftest effect-compilation-identifies-its-uncommitted-target-mapping-gap
  (let [allocation {:allocation/hash (root "1") :rows []}
        compilation (effects/build-pro-rata-effect-compilation allocation
                                                               {:liquidity/root (root "2")}
                                                               (root "3"))
        basis (sut/basis-of compilation)]
    (is (= :pro-rata-effects (:derivation/kind basis)))
    (is (false? (:derivation/reproducible? basis)))
    (is (= :uncommitted-target-mapping (:derivation/contract-gap basis)))))

(deftest v3-effect-compilation-replays-from-committed-target-mapping
  (let [allocation (allocation/allocate {:allocation/id :test/allocation
                                         :available 10
                                         :rows [{:row/id :claim/alice :requested 10 :weight 1}]})
        targets (target-map/build-target-map
                 {:allocation-subjects-root (root "a")
                  :scope-root (root "b")
                  :mapping-profile-root (root "c")
                  :targets [{:allocation/subject-id :allocation/liquidity
                             :mapping/role :available :quantity/root (root "d")}
                            {:allocation/subject-id :claim/alice
                             :mapping/role :filled :quantity/root (root "e")}
                            {:allocation/subject-id :claim/alice
                             :mapping/role :outstanding :quantity/root (root "f")}]})
        semantics (compilation-semantics/build :all-active)
        compilation (compilation-v3/compile
                     {:allocation allocation
                      :target-map targets
                      :semantics semantics
                      :allocation-policy-root (root "1")})
        basis (sut/basis-of compilation)
        resolver {(get-in basis [:derivation/inputs 0 :ref]) allocation
                  (get-in basis [:derivation/inputs 1 :ref]) targets
                  (get-in basis [:derivation/inputs 2 :ref]) semantics}]
    (is (= :pro-rata-effects-v3 (:derivation/kind basis)))
    (is (:derivation/reproducible? basis))
    (is (= :valid (:status (sut/verify-derived compilation #(get resolver %)))))
    (is (= :basis-address-invalid
           (:reason (sut/verify-derived
                     compilation
                     #(get (assoc resolver
                                  (get-in basis [:derivation/inputs 1 :ref])
                                  (assoc-in targets [:targets 0 :quantity/root] (root "2")))
                           %)))))))

(deftest trace-back-reports-derivation-cycles
  (let [state {(root "1") 1}
        transition (effects/transition state [])
        state-ref (sut/state-ref {:state/type effects/state-schema
                                  :state/root (:state-before/root transition)})
        effects-ref (sut/root-ref {:subject/schema effects/effect-schema
                                   :subject/root (:effects/root transition)})
        resolver {state-ref transition effects-ref []}
        trace (sut/trace-back transition #(get resolver %))]
    (is (= :cycle (get-in trace [:inputs 0 :status])))))
