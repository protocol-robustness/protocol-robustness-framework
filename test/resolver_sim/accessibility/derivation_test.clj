(ns resolver-sim.accessibility.derivation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accessibility.derivation :as sut]
            [resolver-sim.benchmark.distributed.fixed-chunks :as fixed]
            [resolver-sim.pro-rata.canonical-effects :as effects]))

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
